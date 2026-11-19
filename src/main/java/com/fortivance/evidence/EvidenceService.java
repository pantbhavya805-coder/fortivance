package com.fortivance.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fortivance.ai.AiGateway;
import com.fortivance.ai.Prompts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Obligation-to-evidence mapping. Claude cites passage keys only; this class checks every key exists,
 * joins the real text, stores findings for human review, and builds the gap report and risk score.
 */
@Service
public class EvidenceService {

    private static final List<String> CONTRACT_CODES = List.of("VS-CON-01", "VS-CON-02", "VS-CON-03", "VS-BCP-01");
    private static final List<String> ASSURANCE_CODES = List.of("VS-SEC-02", "VS-BCP-01");
    private static final Map<String, Integer> STATUS_RANK = Map.of("MET", 3, "PARTIAL", 2, "NOT_MET", 1, "NOT_APPLICABLE", 0);

    private final JdbcTemplate jdbc;
    private final ObjectMapper om;
    private final AiGateway ai;
    private final PassageService passages;
    private final String smartModel;

    public EvidenceService(JdbcTemplate jdbc, ObjectMapper om, AiGateway ai, PassageService passages,
                           @Value("${anthropic.model:claude-sonnet-5-5}") String smartModel) {
        this.jdbc = jdbc; this.om = om; this.ai = ai; this.passages = passages; this.smartModel = smartModel;
    }

    // =================================================================== analyse one document
    public List<Map<String, Object>> analyse(long documentId, boolean force, String username) {
        Map<String, Object> doc = document(documentId);
        Integer existing = jdbc.queryForObject("SELECT COUNT(*) FROM evidence_mappings WHERE document_id = ?", Integer.class, documentId);
        if (!force && existing != null && existing > 0) return findingsForDocument(documentId);

        List<PassageService.Passage> ps = passages.forDocument(documentId);
        Map<String, PassageService.Passage> byKey = ps.stream()
                .collect(Collectors.toMap(PassageService.Passage::key, p -> p, (a, b) -> a, LinkedHashMap::new));

        String docType = (String) doc.get("doc_type");
        List<String> codes = new ArrayList<>("CONTRACT".equals(docType) ? CONTRACT_CODES : ASSURANCE_CODES);
        Map<String, Long> reqIds = new HashMap<>();
        jdbc.query("SELECT id, code FROM control_requirements WHERE in_demo = TRUE",
                rs -> { reqIds.put(rs.getString("code"), rs.getLong("id")); });
        codes.retainAll(reqIds.keySet());

        long vendorId = ((Number) doc.get("vendor_id")).longValue();
        StringBuilder user = new StringBuilder("<document_meta>\n")
                .append("vendor: ").append(doc.get("vendor_name")).append('\n')
                .append("document_type: ").append(docType).append('\n')
                .append("title: ").append(doc.get("title")).append('\n')
                .append("issued_on: ").append(doc.get("issued_on")).append('\n')
                .append("valid_until: ").append(doc.get("valid_until")).append('\n')
                .append(subcontractorContext(vendorId))
                .append("</document_meta>\n\nAssess these requirements: ").append(String.join(", ", codes))
                .append("\n\n<document>\n");
        for (PassageService.Passage p : ps) {
            user.append('[').append(p.key()).append("] ");
            if (p.sectionRef() != null) user.append('(').append(p.sectionRef()).append(") ");
            user.append(p.text()).append('\n');
        }
        user.append("</document>");

        AiGateway.AiResult res = ai.run(new AiGateway.Request("EVIDENCE_MAPPING", smartModel, Prompts.EVIDENCE_VERSION,
                systemPrompt(), user.toString(), Prompts.EVIDENCE_TOOL, Prompts.EVIDENCE_TOOL_DESC,
                ai.readJson(Prompts.EVIDENCE_SCHEMA), 4000, null, documentId));

        jdbc.update("DELETE FROM evidence_mappings WHERE document_id = ? AND review_status = 'PENDING' " +
                "AND id NOT IN (SELECT evidence_mapping_id FROM actions WHERE evidence_mapping_id IS NOT NULL)", documentId);

        int rejectedCitations = 0;
        for (JsonNode f : res.content().path("findings")) {
            String code = f.path("requirement_code").asText();
            if (!codes.contains(code)) continue;                       // ignore anything not asked for

            List<PassageService.Passage> cited = new ArrayList<>();
            for (JsonNode k : f.path("passage_keys")) {
                PassageService.Passage p = byKey.get(k.asText().trim().replace("[", "").replace("]", ""));
                if (p != null) cited.add(p); else rejectedCitations++;
            }
            String status = f.path("status").asText("NOT_MET");
            String rationale = f.path("rationale").asText("");
            if (("MET".equals(status) || "PARTIAL".equals(status)) && cited.isEmpty()) {
                status = "PARTIAL";
                rationale = "[Citation could not be verified; needs review] " + rationale;
            }
            String gap = f.path("gap").isNull() ? null : f.path("gap").asText(null);
            String excerpt = cited.stream().map(p -> "[" + p.key() + "] " + p.text()).collect(Collectors.joining("\n"));
            String keys = cited.stream().map(PassageService.Passage::key).collect(Collectors.joining(","));
            if (keys.length() > 100) keys = keys.substring(0, keys.lastIndexOf(',', 100));

            jdbc.update("INSERT INTO evidence_mappings (vendor_id, control_requirement_id, document_id, passage_id, status, " +
                            "evidence_excerpt, location_in_doc, ai_rationale, ai_confidence, review_status) VALUES (?,?,?,?,?,?,?,?,?, 'PENDING')",
                    vendorId, reqIds.get(code), documentId, cited.isEmpty() ? null : cited.get(0).id(), status,
                    excerpt.isEmpty() ? null : excerpt, keys.isEmpty() ? null : keys,
                    gap == null || gap.isBlank() ? rationale : rationale + "\nGap: " + gap,
                    BigDecimal.valueOf(Math.max(0, Math.min(1, f.path("confidence").asDouble(0.5)))));
        }

        jdbc.update("UPDATE documents SET processing_status = 'ANALYSED' WHERE id = ?", documentId);
        jdbc.update("UPDATE ai_outputs SET document_id = ? WHERE id = ?", documentId, res.aiOutputId());
        audit(username, "EVIDENCE_ANALYSED", documentId,
                Map.of("aiOutputId", res.aiOutputId(), "fromCache", res.fromCache(), "rejectedCitations", rejectedCitations));
        return findingsForDocument(documentId);
    }

    /** Analyse every contract and assurance report that has no findings yet. */
    public List<Map<String, Object>> analyseAll(String username) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Long id : jdbc.queryForList("SELECT id FROM documents WHERE doc_type IN ('CONTRACT','SOC2_REPORT','AUDIT_REPORT') " +
                "AND id NOT IN (SELECT DISTINCT document_id FROM evidence_mappings WHERE document_id IS NOT NULL) " +
                "AND vendor_id IN (SELECT id FROM vendors WHERE outsourcing_scope = 'IN_SCOPE') ORDER BY id", Long.class)) {
            out.add(Map.of("documentId", id, "findings", analyse(id, false, username)));
        }
        return out;
    }

    // =================================================================== reads
    public List<Map<String, Object>> findingsForDocument(long documentId) {
        return findings("em.document_id = ?", documentId);
    }

    public Map<String, Object> vendorReport(long vendorId) {
        Map<String, Object> v = jdbc.queryForMap("SELECT id, name, risk_tier, outsourcing_scope, scope_note FROM vendors WHERE id = ?", vendorId);
        List<Map<String, Object>> all = findings("em.vendor_id = ?", vendorId);

        List<Map<String, Object>> rows = new ArrayList<>();
        int met = 0, verifiedMet = 0;
        for (Map<String, Object> req : jdbc.queryForList(
                "SELECT id, code, title, source_reference, source_framework FROM control_requirements WHERE in_demo = TRUE ORDER BY code")) {
            String code = (String) req.get("code");
            List<Map<String, Object>> fs = all.stream()
                    .filter(f -> code.equals(f.get("requirementCode")))
                    .filter(f -> !"REJECTED".equals(f.get("reviewStatus")))
                    .filter(f -> !"NOT_APPLICABLE".equals(f.get("status")))
                    .toList();
            Map<String, Object> best = fs.stream()
                    .max(Comparator.comparingInt(f -> STATUS_RANK.getOrDefault((String) f.get("status"), 0)))
                    .orElse(null);
            String status = best == null ? "NOT_ASSESSED" : (String) best.get("status");
            String display = switch (status) {
                case "MET" -> "FOUND";
                case "PARTIAL" -> "PARTIAL";
                case "NOT_MET" -> "MISSING";
                default -> "NOT_ASSESSED";
            };
            if ("MET".equals(status)) {
                met++;
                if (!"PENDING".equals(best.get("reviewStatus"))) verifiedMet++;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("requirementCode", code);
            row.put("requirementTitle", req.get("title"));
            row.put("sourceReference", req.get("source_reference"));
            row.put("sourceFramework", req.get("source_framework"));
            row.put("status", display);
            row.put("needsReview", best != null && "PENDING".equals(best.get("reviewStatus")));
            row.put("bestFinding", best);
            row.put("allFindings", fs);
            rows.add(row);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("vendor", v);
        out.put("requirements", rows);
        out.put("coveragePct", rows.isEmpty() ? null : Math.round(1000.0 * met / rows.size()) / 10.0);
        out.put("verifiedCoveragePct", rows.isEmpty() ? null : Math.round(1000.0 * verifiedMet / rows.size()) / 10.0);
        out.put("risk", riskScore(vendorId, rows));
        return out;
    }

    public List<Map<String, Object>> summary() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Long id : jdbc.queryForList("SELECT DISTINCT vendor_id FROM documents WHERE doc_type IN ('CONTRACT','SOC2_REPORT','AUDIT_REPORT') ORDER BY vendor_id", Long.class)) {
            Map<String, Object> r = vendorReport(id);
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("vendor", r.get("vendor"));
            s.put("coveragePct", r.get("coveragePct"));
            s.put("verifiedCoveragePct", r.get("verifiedCoveragePct"));
            Map<String, Long> counts = new TreeMap<>();
            for (Object o : (List<?>) r.get("requirements")) counts.merge((String) ((Map<?, ?>) o).get("status"), 1L, Long::sum);
            s.put("statusCounts", counts);
            s.put("risk", r.get("risk"));
            out.add(s);
        }
        return out;
    }

    // =================================================================== human review
    public Map<String, Object> verify(long mappingId, String comment, String username) {
        Map<String, Object> m = mapping(mappingId);
        jdbc.update("UPDATE evidence_mappings SET review_status = 'APPROVED', reviewed_by = ?, reviewed_at = ?, reviewer_comment = ? WHERE id = ?",
                username, Timestamp.valueOf(LocalDateTime.now()), comment, mappingId);
        String status = (String) m.get("status");
        if ("NOT_MET".equals(status) || "PARTIAL".equals(status)) {
            Integer has = jdbc.queryForObject("SELECT COUNT(*) FROM actions WHERE evidence_mapping_id = ?", Integer.class, mappingId);
            if (has == null || has == 0) {
                jdbc.update("INSERT INTO actions (vendor_id, evidence_mapping_id, title, owner_team, priority, source, status, due_at) " +
                                "VALUES (?,?,?,?,?,'AI','OPEN',?)",
                        m.get("vendor_id"), mappingId,
                        trim("Close evidence gap: " + m.get("title") + " (" + m.get("vendor_name") + ")", 300),
                        "Third-Party Risk", "NOT_MET".equals(status) ? "HIGH" : "MEDIUM",
                        Timestamp.valueOf(LocalDateTime.now().plusDays(7)));
            }
        }
        audit(username, "EVIDENCE_VERIFIED", mappingId, Map.of("status", status, "comment", comment == null ? "" : comment));
        return findings("em.id = ?", mappingId).get(0);
    }

    public Map<String, Object> dispute(long mappingId, String newStatus, String comment, String username) {
        mapping(mappingId);
        if (newStatus != null && !STATUS_RANK.containsKey(newStatus)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Status must be MET, PARTIAL, NOT_MET or NOT_APPLICABLE");
        }
        if (newStatus == null) {
            jdbc.update("UPDATE evidence_mappings SET review_status = 'REJECTED', reviewed_by = ?, reviewed_at = ?, reviewer_comment = ? WHERE id = ?",
                    username, Timestamp.valueOf(LocalDateTime.now()), comment, mappingId);
        } else {
            jdbc.update("UPDATE evidence_mappings SET status = ?, review_status = 'EDITED', reviewed_by = ?, reviewed_at = ?, reviewer_comment = ? WHERE id = ?",
                    newStatus, username, Timestamp.valueOf(LocalDateTime.now()), comment, mappingId);
        }
        audit(username, "EVIDENCE_DISPUTED", mappingId,
                Map.of("newStatus", newStatus == null ? "REJECTED" : newStatus, "comment", comment == null ? "" : comment));
        return findings("em.id = ?", mappingId).get(0);
    }

    // =================================================================== risk score (explainable, no AI)
    Map<String, Object> riskScore(long vendorId, List<Map<String, Object>> requirementRows) {
        Map<String, Object> v = jdbc.queryForMap(
                "SELECT risk_tier, data_access, next_review_due, is_material FROM vendors WHERE id = ?", vendorId);
        List<Map<String, Object>> factors = new ArrayList<>();
        int score = 0;

        int tier = switch ((String) v.get("risk_tier")) { case "CRITICAL" -> 40; case "HIGH" -> 30; case "MEDIUM" -> 20; default -> 10; };
        score += factor(factors, "Inherent tier: " + v.get("risk_tier"), tier);

        int data = switch ((String) v.get("data_access")) { case "FINANCIAL" -> 15; case "CUSTOMER_PII" -> 10; case "INTERNAL" -> 5; default -> 0; };
        score += factor(factors, "Data access: " + v.get("data_access"), data);

        long missing = requirementRows.stream().filter(r -> "MISSING".equals(r.get("status"))).count();
        long partial = requirementRows.stream().filter(r -> "PARTIAL".equals(r.get("status"))).count();
        score += factor(factors, missing + " requirement(s) with missing evidence", (int) Math.min(30, missing * 8));
        score += factor(factors, partial + " requirement(s) with partial evidence", (int) Math.min(16, partial * 4));

        Object due = v.get("next_review_due");
        LocalDate dueDate = due instanceof java.sql.Date d ? d.toLocalDate() : (due instanceof LocalDate ld ? ld : null);
        if (dueDate != null && dueDate.isBefore(LocalDate.now())) score += factor(factors, "Periodic review overdue since " + dueDate, 10);

        Integer openIncidents = jdbc.queryForObject("SELECT COUNT(*) FROM incident_notices WHERE status <> 'CLOSED' " +
                "AND (vendor_id = ? OR reported_by_vendor_id = ? OR vendor_id IN (SELECT id FROM vendors WHERE parent_vendor_id = ?))",
                Integer.class, vendorId, vendorId, vendorId);
        if (openIncidents != null && openIncidents > 0)
            score += factor(factors, openIncidents + " open incident(s) in this vendor's chain", Math.min(10, openIncidents * 5));

        Integer subsWithoutAssurance = jdbc.queryForObject("SELECT COUNT(*) FROM vendors s WHERE s.parent_vendor_id = ? AND NOT EXISTS " +
                "(SELECT 1 FROM documents d WHERE d.vendor_id = s.id AND d.doc_type IN ('SOC2_REPORT','ISO_CERTIFICATE','AUDIT_REPORT'))",
                Integer.class, vendorId);
        if (subsWithoutAssurance != null && subsWithoutAssurance > 0)
            score += factor(factors, subsWithoutAssurance + " subcontractor(s) without assurance on file", 5 * subsWithoutAssurance);

        score = Math.min(100, score);
        return Map.of("score", score, "band", score >= 70 ? "HIGH" : score >= 40 ? "MEDIUM" : "LOW", "factors", factors,
                "method", "Rule-based score; each factor and its points are listed. No AI involved.");
    }

    private static int factor(List<Map<String, Object>> factors, String label, int points) {
        if (points > 0) factors.add(Map.of("factor", label, "points", points));
        return points;
    }

    // =================================================================== helpers
    private String systemPrompt() {
        StringBuilder sb = new StringBuilder(Prompts.EVIDENCE_SYSTEM_RULES);
        jdbc.query("SELECT code, title, requirement_text, expected_evidence FROM control_requirements WHERE in_demo = TRUE ORDER BY code",
                rs -> {
                    sb.append("- ").append(rs.getString(1)).append(" | ").append(rs.getString(2)).append(": ")
                            .append(rs.getString(3)).append(" Expected evidence: ").append(rs.getString(4)).append('\n');
                });
        return sb.toString();
    }

    private String subcontractorContext(long vendorId) {
        StringBuilder sb = new StringBuilder();
        jdbc.query("SELECT s.name, (SELECT COUNT(*) FROM documents d WHERE d.vendor_id = s.id " +
                        "AND d.doc_type IN ('SOC2_REPORT','ISO_CERTIFICATE','AUDIT_REPORT')) AS n " +
                        "FROM vendors s WHERE s.parent_vendor_id = ?",
                rs -> {
                    sb.append("known_subcontractor: ").append(rs.getString(1))
                            .append(" (assurance documents held by the bank: ").append(rs.getInt(2)).append(")\n");
                }, vendorId);
        if (sb.isEmpty()) sb.append("known_subcontractors: none recorded\n");
        return sb.toString();
    }

    private Map<String, Object> document(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT d.*, v.name AS vendor_name FROM documents d JOIN vendors v ON v.id = d.vendor_id WHERE d.id = ?", id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Document " + id + " not found");
        String t = (String) rows.get(0).get("doc_type");
        if (!List.of("CONTRACT", "SOC2_REPORT", "AUDIT_REPORT").contains(t)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Evidence mapping supports contracts and assurance reports only");
        }
        return rows.get(0);
    }

    private Map<String, Object> mapping(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT em.*, cr.title, v.name AS vendor_name FROM evidence_mappings em " +
                "JOIN control_requirements cr ON cr.id = em.control_requirement_id JOIN vendors v ON v.id = em.vendor_id WHERE em.id = ?", id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Finding " + id + " not found");
        return rows.get(0);
    }

    private List<Map<String, Object>> findings(String where, Object arg) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList(
                "SELECT em.id, em.status, em.review_status, em.ai_rationale, em.ai_confidence, em.location_in_doc, " +
                "em.reviewed_by, em.reviewed_at, em.reviewer_comment, em.document_id, d.title AS document_title, d.doc_type, " +
                "cr.code, cr.title AS requirement_title, cr.source_reference " +
                "FROM evidence_mappings em JOIN control_requirements cr ON cr.id = em.control_requirement_id " +
                "LEFT JOIN documents d ON d.id = em.document_id WHERE " + where + " ORDER BY cr.code, em.id", arg)) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("id", r.get("id"));
            f.put("requirementCode", r.get("code"));
            f.put("requirementTitle", r.get("requirement_title"));
            f.put("sourceReference", r.get("source_reference"));
            f.put("status", r.get("status"));
            f.put("reviewStatus", r.get("review_status"));
            f.put("rationale", r.get("ai_rationale"));
            f.put("confidence", r.get("ai_confidence"));
            f.put("documentId", r.get("document_id"));
            f.put("documentTitle", r.get("document_title"));
            f.put("documentType", r.get("doc_type"));
            f.put("reviewedBy", r.get("reviewed_by"));
            f.put("reviewedAt", r.get("reviewed_at"));
            f.put("reviewerComment", r.get("reviewer_comment"));
            Object docId = r.get("document_id");
            f.put("citations", docId == null ? List.of() : citations(((Number) docId).longValue(), (String) r.get("location_in_doc")));
            out.add(f);
        }
        return out;
    }

    private List<Map<String, Object>> citations(long documentId, String keys) {
        if (keys == null || keys.isBlank()) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (String k : keys.split(",")) {
            jdbc.query("SELECT passage_key, page_no, section_ref, text FROM document_passages WHERE document_id = ? AND passage_key = ?",
                    rs -> {
                        Map<String, Object> c = new LinkedHashMap<>();
                        c.put("key", rs.getString(1));
                        c.put("page", rs.getInt(2));
                        c.put("sectionRef", rs.getString(3));
                        c.put("text", rs.getString(4));
                        out.add(c);
                    }, documentId, k.trim());
        }
        return out;
    }

    private void audit(String actor, String action, long entityId, Map<String, Object> detail) {
        try {
            jdbc.update("INSERT INTO audit_log (actor, action, entity_type, entity_id, detail) VALUES (?,?,?,?,?)",
                    actor == null ? "SYSTEM" : actor, action, action.startsWith("EVIDENCE_ANALYSED") ? "DOCUMENT" : "EVIDENCE_MAPPING",
                    entityId, om.writeValueAsString(detail));
        } catch (Exception ignored) { }
    }

    private static String trim(String s, int n) {
        return s.length() > n ? s.substring(0, n) : s;
    }
}
