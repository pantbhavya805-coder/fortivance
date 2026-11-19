package com.fortivance.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.List;

/**
 * Every Claude call goes through here:
 *  1. result cache (same prompt version + model + input = no API call),
 *  2. daily spend cap,
 *  3. usage and estimated cost logged to llm_usage,
 *  4. output stored in ai_outputs for human review.
 */
@Service
public class AiGateway {

    public record AiResult(long aiOutputId, JsonNode content, String model, boolean fromCache) { }

    public record Request(String outputType, String model, String promptVersion,
                          String system, String user, String toolName, String toolDescription,
                          JsonNode schema, int maxTokens, Long incidentId, Long documentId) { }

    private final ClaudeClient claude;
    private final JdbcTemplate jdbc;
    private final ObjectMapper om;
    private final double dailyCapUsd;
    private final double fastIn, fastOut, smartIn, smartOut;

    public AiGateway(ClaudeClient claude, JdbcTemplate jdbc, ObjectMapper om,
                     @Value("${fortivance.daily-cap-usd:3.00}") double dailyCapUsd,
                     @Value("${fortivance.price.fast-in:1.0}") double fastIn,
                     @Value("${fortivance.price.fast-out:5.0}") double fastOut,
                     @Value("${fortivance.price.smart-in:3.0}") double smartIn,
                     @Value("${fortivance.price.smart-out:15.0}") double smartOut) {
        this.claude = claude; this.jdbc = jdbc; this.om = om;
        this.dailyCapUsd = dailyCapUsd;
        this.fastIn = fastIn; this.fastOut = fastOut; this.smartIn = smartIn; this.smartOut = smartOut;
    }

    public AiResult run(Request r) {
        String hash = sha256(r.promptVersion() + "|" + r.model() + "|" + r.system() + "|" + r.user());

        List<Object[]> cached = jdbc.query(
                "SELECT id, content_json FROM ai_outputs WHERE output_type=? AND model=? AND input_hash=?",
                (rs, i) -> new Object[]{rs.getLong(1), rs.getString(2)}, r.outputType(), r.model(), hash);
        if (!cached.isEmpty()) {
            long cachedId = (Long) cached.get(0)[0];
            // After a demo reset the cached row is detached; re-link it to the new incident as a fresh draft.
            if (r.incidentId() != null) {
                jdbc.update("UPDATE ai_outputs SET incident_id = ?, review_status = 'PENDING', reviewed_by = NULL, " +
                        "reviewed_at = NULL, review_comment = NULL WHERE id = ? AND incident_id IS NULL", r.incidentId(), cachedId);
            }
            logUsage(r, 0, 0, 0, 0, 0, 0, true, true, null);
            return new AiResult((Long) cached.get(0)[0], readJson((String) cached.get(0)[1]), r.model(), true);
        }

        Double spentToday = jdbc.queryForObject(
                "SELECT COALESCE(SUM(estimated_cost_usd),0) FROM llm_usage WHERE DATE(created_at) = CURRENT_DATE",
                Double.class);
        if (spentToday != null && spentToday >= dailyCapUsd) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Daily AI budget of $" + dailyCapUsd + " reached");
        }

        ClaudeClient.ToolResult res;
        try {
            res = claude.callTool(r.model(), r.system(), r.user(), r.toolName(), r.toolDescription(),
                    r.schema(), r.maxTokens());
        } catch (ClaudeClient.AiException e) {
            logUsage(r, 0, 0, 0, 0, 0, 0, false, false, e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, e.getMessage());
        }

        double cost = estimateCost(r.model(), res);
        logUsage(r, res.inputTokens(), res.outputTokens(), res.cacheWriteTokens(), res.cacheReadTokens(),
                cost, (int) res.latencyMs(), false, true, null);

        String json = res.input().toString();
        GeneratedKeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO ai_outputs (output_type, incident_id, document_id, model, prompt_version, input_hash, content_json) " +
                    "VALUES (?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, r.outputType());
            ps.setObject(2, r.incidentId());
            ps.setObject(3, r.documentId());
            ps.setString(4, r.model());
            ps.setString(5, r.promptVersion());
            ps.setString(6, hash);
            ps.setString(7, json);
            return ps;
        }, kh);
        return new AiResult(kh.getKey().longValue(), res.input(), r.model(), false);
    }

    private double estimateCost(String model, ClaudeClient.ToolResult r) {
        boolean fast = model.contains("haiku");
        double in = fast ? fastIn : smartIn, out = fast ? fastOut : smartOut;
        return (r.inputTokens() * in + r.cacheWriteTokens() * in * 1.25 + r.cacheReadTokens() * in * 0.1
                + r.outputTokens() * out) / 1_000_000d;
    }

    private void logUsage(Request r, int in, int out, int cw, int cr, double cost, int latency,
                          boolean fromCache, boolean ok, String err) {
        jdbc.update("INSERT INTO llm_usage (call_type, model, input_tokens, output_tokens, cache_creation_tokens, " +
                        "cache_read_tokens, estimated_cost_usd, latency_ms, served_from_cache, success, error_message, " +
                        "incident_id, document_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                r.outputType(), r.model(), in, out, cw, cr, BigDecimal.valueOf(cost), latency, fromCache, ok,
                err == null ? null : err.substring(0, Math.min(500, err.length())), r.incidentId(), r.documentId());
    }

    public JsonNode readJson(String s) {
        try { return om.readTree(s); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    public static String sha256(String s) {
        return sha256(s.getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
