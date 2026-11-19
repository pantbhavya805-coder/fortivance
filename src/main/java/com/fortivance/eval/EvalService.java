package com.fortivance.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * Compares the latest stored AI outputs with the gold labels in docs/eval/gold.json (packaged as
 * classpath:eval/gold.json). Read-only and deterministic: no Claude calls.
 * Works from ai_outputs directly, so results survive a demo reset (which detaches outputs from incidents).
 */
@Service
public class EvalService {

    private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);
    private static final Map<String, Integer> RANK = Map.of("MET", 3, "PARTIAL", 2, "NOT_MET", 1);

    private final JdbcTemplate jdbc;
    private final ObjectMapper om;

    public EvalService(JdbcTemplate jdbc, ObjectMapper om) {
        this.jdbc = jdbc;
        this.om = om;
    }

    public Map<String, Object> report() {
        JsonNode gold = loadGold();
        Map<String, JsonNode> extractions = latestExtractionsByReference();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("generatedAt", LocalDateTime.now(IST).atOffset(IST).toString());
        out.put("extraction", extraction(gold.path("incidents"), extractions));
        out.put("injection", injection(gold.path("injection"), extractions));
        out.put("evidence", evidence(gold.path("evidence")));
        out.put("citations", citations());
        out.put("usage", usage());
        out.put("note", "Compares the latest stored AI output for each item with hand-labelled gold data. "
                + "clock_start is the earliest of the extracted incident start and detection times, as the app uses it. "
                + "Evidence uses the status Claude returned, before any analyst edit.");
        return out;
    }

    // =================================================================== extraction
    private Map<String, Object> extraction(JsonNode goldIncidents, Map<String, JsonNode> extractions) {
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, int[]> byField = new LinkedHashMap<>();   // field -> [correct, total]
        int correct = 0, total = 0, missing = 0;

        for (JsonNode g : goldIncidents) {
            String ref = g.path("reference_no").asText();
            JsonNode x = extractions.get(ref);
            if (x == null) {
                missing++;
                rows.add(row("referenceNo", ref, "status", "NOT_RUN", "fields", List.of()));
                continue;
            }
            List<Map<String, Object>> fields = new ArrayList<>();
            compare(fields, "reporter", g.path("reporter").asText(), text(x, "reporting_vendor_name"), nameMatch(g.path("reporter").asText(), text(x, "reporting_vendor_name")));
            compare(fields, "affected", g.path("affected").asText(), text(x, "affected_vendor_name"), nameMatch(g.path("affected").asText(), text(x, "affected_vendor_name")));
            // Clock start as the app computes it (IncidentService.ingest): earliest of stated start and stated detection.
            if (g.has("incident_started_at")) {
                String clock = earliest(text(x, "incident_started_at"), text(x, "detected_at"));
                compare(fields, "clock_start", g.path("incident_started_at").asText(), clock,
                        sameInstant(g.path("incident_started_at").asText(), clock));
            }
            for (String f : List.of("notified_at", "resolved_at")) {
                if (!g.has(f)) continue;
                compare(fields, f, g.path(f).asText(), text(x, f), sameInstant(g.path(f).asText(), text(x, f)));
            }
            compare(fields, "incident_type", g.path("incident_type").asText(), text(x, "incident_type"),
                    g.path("incident_type").asText().equalsIgnoreCase(Objects.toString(text(x, "incident_type"), "")));
            boolean sus = x.path("suspicious_instructions_detected").asBoolean(false);
            compare(fields, "suspicious", String.valueOf(g.path("suspicious").asBoolean()), String.valueOf(sus),
                    sus == g.path("suspicious").asBoolean());

            int ok = 0;
            for (Map<String, Object> f : fields) {
                boolean m = (Boolean) f.get("match");
                int[] c = byField.computeIfAbsent((String) f.get("field"), k -> new int[2]);
                c[1]++;
                if (m) { c[0]++; ok++; }
            }
            correct += ok;
            total += fields.size();
            rows.add(row("referenceNo", ref, "status", "EVALUATED", "correct", ok, "total", fields.size(), "fields", fields));
        }

        List<Map<String, Object>> fieldSummary = new ArrayList<>();
        byField.forEach((f, c) -> fieldSummary.add(row("field", f, "correct", c[0], "total", c[1], "accuracyPct", pct(c[0], c[1]))));

        return row("accuracyPct", pct(correct, total), "correct", correct, "total", total,
                "incidentsEvaluated", goldIncidents.size() - missing, "incidentsMissing", missing,
                "byField", fieldSummary, "incidents", rows);
    }

    private static void compare(List<Map<String, Object>> fields, String field, String expected, String actual, boolean match) {
        fields.add(row("field", field, "expected", expected, "actual", actual, "match", match));
    }

    // =================================================================== injection
    private Map<String, Object> injection(JsonNode g, Map<String, JsonNode> extractions) {
        String ref = g.path("reference_no").asText();
        JsonNode x = extractions.get(ref);
        Boolean suspicious = x == null ? null : x.path("suspicious_instructions_detected").asBoolean(false);

        List<Long> ids = jdbc.queryForList("SELECT id FROM incident_notices WHERE reference_no LIKE ? ORDER BY id DESC",
                Long.class, ref + "%");
        Boolean flagRaised = null, planWithheld = null, planDrafted = null;
        if (!ids.isEmpty()) {
            long id = ids.get(0);
            flagRaised = count("SELECT COUNT(*) FROM incident_events WHERE incident_id = ? AND event_type = 'SECURITY_FLAG' AND description LIKE ?",
                    id, g.path("flag").asText() + "%") > 0;
            planWithheld = count("SELECT COUNT(*) FROM incident_events WHERE incident_id = ? AND event_type = 'PLAN_WITHHELD'", id) > 0;
            planDrafted = count("SELECT COUNT(*) FROM incident_events WHERE incident_id = ? AND event_type = 'PLAN_DRAFTED' " +
                    "AND occurred_at <= (SELECT MIN(occurred_at) FROM incident_events WHERE incident_id = ? AND event_type = 'PLAN_WITHHELD')", id, id) > 0;
        }

        Boolean blocked;
        String basis;
        if (planWithheld != null) {
            blocked = planWithheld && !planDrafted;
            basis = "Incident on file: response plan withheld automatically";
        } else if (suspicious != null) {
            blocked = suspicious;
            basis = "Incident not on file (demo reset); based on the stored extraction only";
        } else {
            blocked = null;
            basis = "Injection test notice has not been uploaded";
        }
        return row("referenceNo", ref, "blocked", blocked, "basis", basis,
                "suspiciousDetected", suspicious, "expectedSuspicious", g.path("suspicious").asBoolean(),
                "senderFlagRaised", flagRaised, "planWithheld", planWithheld);
    }

    // =================================================================== evidence
    private Map<String, Object> evidence(JsonNode goldDocs) {
        List<Map<String, Object>> rows = new ArrayList<>();
        int exact = 0, withinOne = 0, total = 0, missing = 0;

        for (JsonNode d : goldDocs) {
            long docId = d.path("document_id").asLong();
            JsonNode out = latestOutput("EVIDENCE_MAPPING", docId);
            Map<String, String> ai = new HashMap<>();
            if (out != null) {
                for (JsonNode f : out.path("findings")) ai.put(f.path("requirement_code").asText(), f.path("status").asText());
            }
            Iterator<Map.Entry<String, JsonNode>> it = d.path("findings").fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                String expected = e.getValue().asText();
                String actual = ai.get(e.getKey());
                boolean ex = expected.equals(actual);
                boolean one = ex || (actual != null && RANK.containsKey(actual) && RANK.containsKey(expected)
                        && Math.abs(RANK.get(actual) - RANK.get(expected)) <= 1);
                if (out == null) missing++;
                total++;
                if (ex) exact++;
                if (one) withinOne++;
                rows.add(row("documentId", docId, "document", d.path("document").asText(), "requirement", e.getKey(),
                        "expected", expected, "actual", actual == null ? (out == null ? "NOT_RUN" : "NOT_RETURNED") : actual,
                        "exact", ex, "withinOneStep", one));
            }
        }
        return row("exactPct", pct(exact, total), "withinOneStepPct", pct(withinOne, total),
                "exact", exact, "withinOneStep", withinOne, "total", total, "notRun", missing, "items", rows);
    }

    // =================================================================== citations
    private Map<String, Object> citations() {
        int cited = 0, rejected = 0, documents = 0;
        List<Map<String, Object>> rejectedKeys = new ArrayList<>();
        for (Long docId : jdbc.queryForList("SELECT DISTINCT document_id FROM ai_outputs WHERE output_type = 'EVIDENCE_MAPPING' " +
                "AND document_id IS NOT NULL ORDER BY document_id", Long.class)) {
            JsonNode out = latestOutput("EVIDENCE_MAPPING", docId);
            if (out == null) continue;
            documents++;
            Set<String> valid = new HashSet<>(jdbc.queryForList(
                    "SELECT passage_key FROM document_passages WHERE document_id = ?", String.class, docId));
            for (JsonNode f : out.path("findings")) {
                for (JsonNode k : f.path("passage_keys")) {
                    String key = k.asText().trim().replace("[", "").replace("]", "");
                    cited++;
                    if (!valid.contains(key)) {
                        rejected++;
                        rejectedKeys.add(row("documentId", docId, "requirement", f.path("requirement_code").asText(), "key", k.asText()));
                    }
                }
            }
        }
        return row("documents", documents, "cited", cited, "rejected", rejected,
                "rejectedPct", pct(rejected, cited), "rejectedKeys", rejectedKeys);
    }

    // =================================================================== usage
    private List<Map<String, Object>> usage() {
        return jdbc.query("""
                SELECT call_type,
                       COUNT(*) AS calls,
                       SUM(CASE WHEN served_from_cache THEN 1 ELSE 0 END) AS cache_hits,
                       SUM(CASE WHEN NOT served_from_cache AND success THEN 1 ELSE 0 END) AS api_calls,
                       SUM(CASE WHEN success THEN 0 ELSE 1 END) AS failures,
                       AVG(CASE WHEN NOT served_from_cache AND success THEN input_tokens END) AS avg_in,
                       AVG(CASE WHEN NOT served_from_cache AND success THEN output_tokens END) AS avg_out,
                       AVG(CASE WHEN NOT served_from_cache AND success THEN estimated_cost_usd END) AS avg_cost,
                       SUM(estimated_cost_usd) AS total_cost,
                       AVG(CASE WHEN NOT served_from_cache AND success THEN latency_ms END) AS avg_latency,
                       MAX(model) AS model
                FROM llm_usage GROUP BY call_type ORDER BY call_type""", (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("callType", rs.getString("call_type"));
            m.put("model", rs.getString("model"));
            m.put("calls", rs.getInt("calls"));
            m.put("apiCalls", rs.getInt("api_calls"));
            m.put("cacheHits", rs.getInt("cache_hits"));
            m.put("failures", rs.getInt("failures"));
            m.put("avgInputTokens", round(rs.getObject("avg_in"), 0));
            m.put("avgOutputTokens", round(rs.getObject("avg_out"), 0));
            m.put("avgCostUsd", round(rs.getObject("avg_cost"), 4));
            m.put("totalCostUsd", round(rs.getObject("total_cost"), 4));
            m.put("avgLatencyMs", round(rs.getObject("avg_latency"), 0));
            return m;
        });
    }

    // =================================================================== helpers
    private JsonNode loadGold() {
        try (InputStream in = new ClassPathResource("eval/gold.json").getInputStream()) {
            return om.readTree(in);
        } catch (Exception e) {
            throw new IllegalStateException("Gold labels not found on the classpath (eval/gold.json)", e);
        }
    }

    /** Latest INCIDENT_EXTRACTION output per extracted reference number. */
    private Map<String, JsonNode> latestExtractionsByReference() {
        Map<String, JsonNode> map = new HashMap<>();
        jdbc.query("SELECT content_json FROM ai_outputs WHERE output_type = 'INCIDENT_EXTRACTION' ORDER BY id", rs -> {
            JsonNode n = read(rs.getString(1));
            String ref = text(n, "reference_no");
            if (ref != null) map.put(ref.trim(), n);        // later rows overwrite earlier ones
        });
        return map;
    }

    private JsonNode latestOutput(String type, long documentId) {
        List<String> rows = jdbc.queryForList("SELECT content_json FROM ai_outputs WHERE output_type = ? AND document_id = ? " +
                "ORDER BY id DESC LIMIT 1", String.class, type, documentId);
        return rows.isEmpty() ? null : read(rows.get(0));
    }

    private JsonNode read(String s) {
        try { return om.readTree(s); } catch (Exception e) { return om.createObjectNode(); }
    }

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    private static String text(JsonNode x, String field) {
        JsonNode v = x.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }

    /** Gold uses short names ("NimbusCloud"); the extraction may use the full legal name. */
    private static boolean nameMatch(String gold, String actual) {
        if (actual == null) return false;
        String a = actual.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        String g = gold.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return a.contains(g);
    }

    private static boolean sameInstant(String gold, String actual) {
        OffsetDateTime g = parse(gold), a = parse(actual);
        return g != null && a != null && g.toInstant().equals(a.toInstant());
    }

    private static String earliest(String a, String b) {
        OffsetDateTime pa = parse(a), pb = parse(b);
        if (pa == null) return pb == null ? null : b;
        if (pb == null) return a;
        return pb.toInstant().isBefore(pa.toInstant()) ? b : a;
    }

    private static OffsetDateTime parse(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return OffsetDateTime.parse(s);
        } catch (DateTimeParseException e) {
            try { return LocalDateTime.parse(s).atOffset(IST); } catch (DateTimeParseException e2) { return null; }
        }
    }

    private static Double pct(int n, int d) {
        return d == 0 ? null : Math.round(1000.0 * n / d) / 10.0;
    }

    private static Object round(Object v, int places) {
        if (v == null) return null;
        double d = ((Number) v).doubleValue();
        double f = Math.pow(10, places);
        return places == 0 ? (Object) Math.round(d) : (Object) (Math.round(d * f) / f);
    }

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }
}
