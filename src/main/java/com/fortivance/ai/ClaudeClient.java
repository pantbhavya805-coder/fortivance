package com.fortivance.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Minimal Messages API client. Every call forces a single tool so the reply is schema-shaped JSON.
 * The system block carries cache_control, so tools + system prompt are cached when long enough.
 */
@Component
public class ClaudeClient {

    private static final URI URL = URI.create("https://api.anthropic.com/v1/messages");

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private final ObjectMapper om;
    private final String apiKey;
    private volatile boolean sendTemperature = true;
    /** Thinking is off for these structured calls: it adds cost and blocks forced tool use. */
    private volatile boolean sendThinkingDisabled = true;
    /** Models that reject a forced tool_choice; for these we use "auto" plus an explicit instruction. */
    private final java.util.Set<String> noForcedTool = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public ClaudeClient(ObjectMapper om, @Value("${anthropic.api-key:}") String apiKey) {
        this.om = om;
        this.apiKey = apiKey;
    }

    public record ToolResult(JsonNode input, int inputTokens, int outputTokens,
                             int cacheWriteTokens, int cacheReadTokens, long latencyMs) { }

    public ToolResult callTool(String model, String system, String userContent,
                               String toolName, String toolDescription, JsonNode inputSchema, int maxTokens) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new AiException("ANTHROPIC_API_KEY is not set");
        }

        ObjectNode body = om.createObjectNode();
        body.put("model", model);
        body.put("max_tokens", maxTokens);
        if (sendTemperature) body.put("temperature", 0);
        if (sendThinkingDisabled) body.putObject("thinking").put("type", "disabled");

        ObjectNode sys = body.putArray("system").addObject();
        sys.put("type", "text");
        sys.put("text", system);
        sys.putObject("cache_control").put("type", "ephemeral");

        ObjectNode tool = body.putArray("tools").addObject();
        tool.put("name", toolName);
        tool.put("description", toolDescription);
        tool.set("input_schema", inputSchema);

        ObjectNode choice = body.putObject("tool_choice");
        if (noForcedTool.contains(model)) {
            choice.put("type", "auto");
        } else {
            choice.put("type", "tool");
            choice.put("name", toolName);
        }

        ObjectNode msg = body.putArray("messages").addObject();
        msg.put("role", "user");
        msg.put("content", userContent + "\n\nRespond only by calling the " + toolName + " tool.");

        long start = System.currentTimeMillis();
        HttpResponse<String> res = send(body, 0);
        long latency = System.currentTimeMillis() - start;

        try {
            JsonNode json = om.readTree(res.body());
            JsonNode input = null;
            for (JsonNode block : json.path("content")) {
                if ("tool_use".equals(block.path("type").asText())) {
                    input = block.path("input");
                    break;
                }
            }
            if (input == null) {
                // Fallback: model answered in text; accept it if it is a JSON object.
                for (JsonNode block : json.path("content")) {
                    if ("text".equals(block.path("type").asText())) {
                        String t = block.path("text").asText().trim();
                        int a = t.indexOf('{'), b = t.lastIndexOf('}');
                        if (a >= 0 && b > a) {
                            try { input = om.readTree(t.substring(a, b + 1)); break; } catch (Exception ignored) { }
                        }
                    }
                }
            }
            if (input == null || !input.isObject()) throw new AiException("Model returned no structured result");
            if ("max_tokens".equals(json.path("stop_reason").asText())) {
                throw new AiException("Claude output was cut off at max_tokens; result discarded");
            }
            JsonNode u = json.path("usage");
            return new ToolResult(input,
                    u.path("input_tokens").asInt(), u.path("output_tokens").asInt(),
                    u.path("cache_creation_input_tokens").asInt(), u.path("cache_read_input_tokens").asInt(),
                    latency);
        } catch (AiException e) {
            throw e;
        } catch (Exception e) {
            throw new AiException("Could not parse Claude response: " + e.getMessage());
        }
    }

    private HttpResponse<String> send(ObjectNode body, int attempt) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URL)
                    .timeout(Duration.ofSeconds(90))
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(om.writeValueAsString(body)))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            int s = res.statusCode();
            if (s == 200) return res;

            // If the API does not accept disabling thinking, stop sending it and retry.
            if (s == 400 && body.has("thinking") && res.body().contains("thinking")) {
                sendThinkingDisabled = false;
                body.remove("thinking");
                body.put("max_tokens", Math.max(body.path("max_tokens").asInt(), 8000));
                return send(body, attempt);
            }
            // Some models reject a forced tool_choice; switch to auto for this model and retry.
            if (s == 400 && res.body().contains("tool_choice") && "tool".equals(body.path("tool_choice").path("type").asText())) {
                noForcedTool.add(body.path("model").asText());
                ObjectNode c = body.putObject("tool_choice");
                c.put("type", "auto");
                return send(body, attempt);
            }
            // Some models reject sampling parameters; drop temperature once and retry.
            if (s == 400 && body.has("temperature") && res.body().contains("temperature")) {
                sendTemperature = false;
                body.remove("temperature");
                return send(body, attempt);
            }
            if ((s == 429 || s == 529 || s >= 500) && attempt < 2) {
                Thread.sleep(2000L * (attempt + 1));
                return send(body, attempt + 1);
            }
            String detail = res.body().length() > 300 ? res.body().substring(0, 300) : res.body();
            throw new AiException("Claude API error " + s + ": " + detail);
        } catch (AiException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiException("Interrupted calling Claude");
        } catch (Exception e) {
            throw new AiException("Network error calling Claude: " + e.getMessage());
        }
    }

    public static class AiException extends RuntimeException {
        public AiException(String m) { super(m); }
    }
}
