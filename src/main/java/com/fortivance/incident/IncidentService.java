package com.fortivance.incident;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fortivance.ai.AiGateway;
import com.fortivance.ai.Prompts;
import com.fortivance.dependency.ImpactReport;
import com.fortivance.dependency.ImpactService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * Incident flow: upload -> local text -> Haiku extraction -> vendor match (code) -> impact (SQL)
 * -> Sonnet response plan -> human approval -> actions.
 */
@Service
public class IncidentService {

    static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);
    static final int CLOCK_HOURS = 6;
    private static final List<String> FIXED_TEAMS =
            List.of("Third-Party Risk", "Information Security", "Legal & Compliance", "Corporate Communications");

    private final JdbcTemplate jdbc;
    private final ObjectMapper om;
    private final AiGateway ai;
    private final TextExtractor extractor;
    private final VendorMatcher matcher;
    private final ImpactService impact;
    private final String fastModel, smartModel, uploadDir;

    public IncidentService(JdbcTemplate jdbc, ObjectMapper om, AiGateway ai, TextExtractor extractor,
                           VendorMatcher matcher, ImpactService impact,
                           @Value("${anthropic.model-fast:claude-haiku-5-5}") String fastModel,
                           @Value("${anthropic.model:claude-sonnet-5-5}") String smartModel,
                           @Value("${fortivance.upload-dir:/tmp/fortivance-uploads}") String uploadDir) {
        this.jdbc = jdbc; this.om = om; this.ai = ai; this.extractor = extractor;
        this.matcher = matcher; this.impact = impact;
        this.fastModel = fastModel; this.smartModel = smartModel; this.uploadDir = uploadDir;
    }

    // =================================================================== upload
    public long ingest(String filename, byte[] bytes, String username, boolean draftPlan) {
        String hash = AiGateway.sha256(bytes);

        // Same file uploaded again: return the existing incident (no tokens spent).
        List<Long> existing = jdbc.queryForList(
                "SELECT i.id FROM incident_notices i JOIN documents d ON d.id = i.document_id WHERE d.content_hash = ?",
                Long.class, hash);
        if (!existing.isEmpty()) return existing.get(0);

        TextExtractor.Extracted text = extractor.extract(filename, bytes);
        String body = text.text().length() > 20000 ? text.text().substring(0, 20000) : text.text();

        // ---- 1. extraction (Haiku)
        String vendorList = String.join("\n", matcher.all().stream().map(v -> "- " + v.name()).toList());
        String user = "Bank vendor list (for spelling only):\n" + vendorList +
                "\n\n<notice filename=\"" + safe(filename) + "\">\n" + body + "\n</notice>";
        AiGateway.AiResult ext = ai.run(new AiGateway.Request("INCIDENT_EXTRACTION", fastModel,
                Prompts.EXTRACT_VERSION, Prompts.EXTRACT_SYSTEM, user, Prompts.EXTRACT_TOOL,
                Prompts.EXTRACT_TOOL_DESC, schema(Prompts.EXTRACT_SCHEMA), 2000, null, null));
        JsonNode x = ext.content();

        // ---- 2. vendor match (code)
        String sender = text(x, "sender_email");
        VendorMatcher.Match m = matcher.match(text(x, "reporting_vendor_name"), text(x, "affected_vendor_name"), sender);
        if (m.reporter() == null || m.affected() == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Could not match the notice to a vendor in the registry. Extracted: " + x);
        }

        LocalDateTime now = LocalDateTime.now(IST);
        LocalDateTime notified = parseTime(text(x, "notified_at"), now);
        // Clock start: earliest of stated detection and stated incident start; never later than the notice.
        LocalDateTime started = parseTime(text(x, "incident_started_at"), null);
        LocalDateTime detected = parseTime(text(x, "detected_at"), null);
        if (detected == null || (started != null && started.isBefore(detected))) detected = started;
        if (detected == null || detected.isAfter(notified)) detected = notified;
        LocalDateTime resolved = parseTime(text(x, "resolved_at"), null);

        // ---- 3. persist document + incident
        storeFile(hash, filename, bytes);
        long docId = insert("INSERT INTO documents (vendor_id, doc_type, title, file_path, content_hash, page_count, " +
                        "issued_on, extracted_text, processing_status) VALUES (?, 'INCIDENT_NOTICE', ?, ?, ?, ?, ?, ?, 'ANALYSED')",
                m.reporter().id(), "Incident notice: " + safe(filename), "uploads/" + hash + ext(filename), hash,
                text.pages().size(), notified.toLocalDate(), body);

        String ref = text(x, "reference_no");
        if (ref == null || ref.isBlank()) ref = "FTV-" + hash.substring(0, 8).toUpperCase();
        Integer dup = jdbc.queryForObject("SELECT COUNT(*) FROM incident_notices WHERE reference_no = ?", Integer.class, ref);
        if (dup != null && dup > 0) ref = ref + "-" + hash.substring(0, 4).toUpperCase();

        Long userId = userId(username);
        String summary = Optional.ofNullable(text(x, "summary")).orElse("(no summary)");
        long incidentId = insert("INSERT INTO incident_notices (vendor_id, reported_by_vendor_id, document_id, reference_no, " +
                        "incident_type, severity_reported, summary, detected_at, notified_at, received_at, resolved_at, " +
                        "sender_email, extraction_confidence, created_by, status) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?, 'TRIAGING')",
                m.affected().id(), m.reporter().id(), docId, ref, text(x, "incident_type"), text(x, "severity"),
                summary.length() > 1000 ? summary.substring(0, 1000) : summary,
                ts(detected), ts(notified), ts(now), ts(resolved), sender,
                x.path("confidence").isNumber() ? x.path("confidence").asDouble() : null, userId);

        jdbc.update("UPDATE ai_outputs SET incident_id = ?, document_id = ? WHERE id = ? AND incident_id IS NULL",
                incidentId, docId, ext.aiOutputId());

        // ---- 4. timeline
        String actor = username == null ? "SYSTEM" : username;
        event(incidentId, "DETECTED_BY_VENDOR", detected, m.affected().name(), "Incident detected by the vendor (as stated in the notice)");
        event(incidentId, "NOTICE_SENT_BY_VENDOR", notified, m.reporter().name(), "Notice sent to the bank");
        if (resolved != null) event(incidentId, "RESOLVED_BY_VENDOR", resolved, m.reporter().name(), "Vendor states the incident was resolved");
        event(incidentId, "NOTICE_RECEIVED", now, actor, "Notice uploaded to Fortivance");
        event(incidentId, "EXTRACTED", LocalDateTime.now(IST), "AI:" + ext.model(),
                "Fields extracted" + (ext.fromCache() ? " (cached result)" : ""));
        for (String f : m.flags()) event(incidentId, "SECURITY_FLAG", LocalDateTime.now(IST), "SYSTEM", f);
        if (x.path("suspicious_instructions_detected").asBoolean(false)) {
            event(incidentId, "SECURITY_FLAG", LocalDateTime.now(IST), "AI:" + ext.model(),
                    "Notice contains instructions aimed at automated processing; they were ignored. " + Optional.ofNullable(text(x, "notes")).orElse(""));
        }

        // ---- 5. impact (SQL, no tokens)
        ImpactReport r = impact.analyse(m.affected().id());
        String chain = r.vendorChain().get(r.vendorChain().size() - 1).path();
        for (ImpactReport.AffectedService s : r.services()) {
            jdbc.update("INSERT INTO incident_impacts (incident_id, business_service_id, impact_level, via_applications, vendor_path) VALUES (?,?,?,?,?)",
                    incidentId, s.id(), s.impactLevel(), String.join(", ", s.viaApplications()), chain);
        }
        event(incidentId, "IMPACT_COMPUTED", LocalDateTime.now(IST), "SYSTEM",
                r.summary().services() + " services affected, " + r.summary().disruptedServices() + " disrupted");
        audit(actor, "INCIDENT_CREATED", incidentId, Map.of("referenceNo", ref, "flags", m.flags()));

        // ---- 6. plan (Sonnet). Skipped when the notice looks like an injection attempt.
        boolean suspicious = x.path("suspicious_instructions_detected").asBoolean(false)
                || m.flags().stream().anyMatch(f -> f.startsWith("SENDER_DOMAIN_MISMATCH"));
        if (draftPlan && !suspicious) draftPlan(incidentId, username);
        else if (suspicious) event(incidentId, "PLAN_WITHHELD", LocalDateTime.now(IST), "SYSTEM",
                "Response plan not drafted automatically: verify the sender first");
        return incidentId;
    }

    // =================================================================== plan
    public void draftPlan(long incidentId, String username) {
        Map<String, Object> inc = incidentRow(incidentId);
        long vendorId = ((Number) inc.get("vendor_id")).longValue();
        ImpactReport r = impact.analyse(vendorId);
        LocalDateTime detected = (LocalDateTime) inc.get("detected_at");

        ObjectNode facts = om.createObjectNode();
        ObjectNode n = facts.putObject("incident");
        n.put("reference_no", (String) inc.get("reference_no"));
        n.put("type", (String) inc.get("incident_type"));
        n.put("severity_reported", (String) inc.get("severity_reported"));
        n.put("summary", (String) inc.get("summary"));
        n.put("affected_vendor", (String) inc.get("vendor_name"));
        n.put("reported_by", (String) inc.get("reporter_name"));
        latestOutput(incidentId, "INCIDENT_EXTRACTION").ifPresent(e -> {
            n.set("production_affected", e.path("production_affected"));
            n.set("customer_data_exposure", e.path("customer_data_exposure"));
            n.set("vendor_requested_actions", e.path("vendor_requested_actions"));
            n.set("systems_mentioned", e.path("systems_or_services_mentioned"));
        });

        ObjectNode clock = facts.putObject("rbi_clock");
        clock.put("starts_at_vendor_detection", iso(detected));
        clock.put("deadline", iso(detected.plusHours(CLOCK_HOURS)));
        clock.put("minutes_remaining_now", Duration.between(LocalDateTime.now(IST), detected.plusHours(CLOCK_HOURS)).toMinutes());

        ArrayNode chain = facts.putArray("vendor_chain");
        r.vendorChain().forEach(v -> chain.add(v.name() + (v.fourthParty() ? " (fourth party)" : "")));

        ArrayNode apps = facts.putArray("affected_applications");
        Set<String> teams = new LinkedHashSet<>(FIXED_TEAMS);
        for (ImpactReport.AffectedApplication a : r.applications()) {
            ObjectNode o = apps.addObject();
            o.put("code", a.code()); o.put("name", a.name()); o.put("criticality", a.criticality());
            o.put("depends_on", a.viaVendorName()); o.put("single_point_of_failure", a.singlePointOfFailure());
            o.put("alternative", a.alternative());
            o.put("rto_hours", a.rtoHours());
        }
        jdbc.queryForList("SELECT DISTINCT owner_team FROM applications WHERE owner_team IS NOT NULL", String.class)
                .forEach(teams::add);

        ArrayNode svcs = facts.putArray("affected_services");
        for (ImpactReport.AffectedService s : r.services()) {
            ObjectNode o = svcs.addObject();
            o.put("name", s.name()); o.put("criticality", s.criticality()); o.put("impact", s.impactLevel());
            o.put("impact_tolerance_hours", s.impactToleranceHours()); o.put("bcp_plan", s.bcpPlanRef());
            o.put("business_owner", s.businessOwner());
            o.put("via_applications", String.join(", ", s.viaApplications()));
            if (s.businessOwner() != null) teams.add(s.businessOwner());
        }
        ArrayNode t = facts.putArray("owner_teams");
        teams.forEach(t::add);

        AiGateway.AiResult plan = ai.run(new AiGateway.Request("RESPONSE_PLAN", smartModel, Prompts.PLAN_VERSION,
                Prompts.PLAN_SYSTEM, "<facts>\n" + facts.toPrettyString() + "\n</facts>", Prompts.PLAN_TOOL,
                Prompts.PLAN_TOOL_DESC, schema(Prompts.PLAN_SCHEMA), 4000, incidentId, null));

        event(incidentId, "PLAN_DRAFTED", LocalDateTime.now(IST), "AI:" + plan.model(),
                "Response plan drafted for review" + (plan.fromCache() ? " (cached result)" : ""));
        audit(username == null ? "SYSTEM" : username, "PLAN_DRAFTED", incidentId, Map.of("aiOutputId", plan.aiOutputId()));
    }

    public void review(long incidentId, boolean approve, String comment, String username) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, content_json FROM ai_outputs WHERE incident_id = ? AND output_type = 'RESPONSE_PLAN' " +
                "AND review_status = 'PENDING' ORDER BY id DESC LIMIT 1", incidentId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "No pending response plan for this incident");
        long outputId = ((Number) rows.get(0).get("id")).longValue();
        Long userId = userId(username);
        String actor = username == null ? "SYSTEM" : username;

        jdbc.update("UPDATE ai_outputs SET review_status = ?, reviewed_by = ?, reviewed_at = ?, review_comment = ? WHERE id = ?",
                approve ? "APPROVED" : "REJECTED", userId, ts(LocalDateTime.now(IST)), comment, outputId);

        if (approve) {
            Map<String, Object> inc = incidentRow(incidentId);
            JsonNode plan = ai.readJson((String) rows.get(0).get("content_json"));
            int created = 0;
            for (JsonNode a : plan.path("immediate_actions")) {
                int due = a.path("due_in_minutes").asInt(60);
                jdbc.update("INSERT INTO actions (incident_id, vendor_id, title, owner_team, priority, source, status, due_at) " +
                                "VALUES (?,?,?,?,?,'AI','OPEN',?)",
                        incidentId, inc.get("vendor_id"), a.path("title").asText(), a.path("owner_team").asText(),
                        a.path("priority").asText("MEDIUM"), ts(LocalDateTime.now(IST).plusMinutes(due)));
                created++;
            }
            jdbc.update("UPDATE incident_notices SET status = 'ASSESSED' WHERE id = ?", incidentId);
            event(incidentId, "PLAN_APPROVED", LocalDateTime.now(IST), actor, comment == null ? "Plan approved" : comment);
            event(incidentId, "ACTIONS_ASSIGNED", LocalDateTime.now(IST), actor, created + " actions created from the approved plan");
        } else {
            event(incidentId, "PLAN_REJECTED", LocalDateTime.now(IST), actor, comment == null ? "Plan rejected" : comment);
        }
        audit(actor, approve ? "PLAN_APPROVED" : "PLAN_REJECTED", incidentId,
                Map.of("aiOutputId", outputId, "comment", comment == null ? "" : comment));
    }

    // =================================================================== reads
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        jdbc.query("""
                SELECT i.id, i.reference_no, i.severity_reported, i.status, i.detected_at, i.received_at,
                       v.name AS vendor_name, r.name AS reporter_name,
                       (SELECT COUNT(*) FROM incident_impacts ii WHERE ii.incident_id = i.id AND ii.impact_level = 'DISRUPTED') AS disrupted
                FROM incident_notices i
                JOIN vendors v ON v.id = i.vendor_id
                JOIN vendors r ON r.id = i.reported_by_vendor_id
                ORDER BY i.received_at DESC
                """, rs -> {
            LocalDateTime det = rs.getObject("detected_at", LocalDateTime.class);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getLong("id"));
            m.put("referenceNo", rs.getString("reference_no"));
            m.put("incidentVendorName", rs.getString("vendor_name"));
            m.put("reportedByName", rs.getString("reporter_name"));
            m.put("severityReported", rs.getString("severity_reported"));
            m.put("status", rs.getString("status"));
            m.put("detectedAt", iso(det));
            m.put("clock", Map.of("deadline", iso(det.plusHours(CLOCK_HOURS))));
            m.put("disruptedServices", rs.getInt("disrupted"));
            out.add(m);
        });
        return out;
    }

    public Map<String, Object> detail(long id) {
        Map<String, Object> inc = incidentRow(id);
        LocalDateTime det = (LocalDateTime) inc.get("detected_at");
        LocalDateTime notif = (LocalDateTime) inc.get("notified_at");
        long vendorId = ((Number) inc.get("vendor_id")).longValue();

        Map<String, Object> d = new LinkedHashMap<>();
        d.put("id", id);
        d.put("referenceNo", inc.get("reference_no"));
        d.put("status", inc.get("status"));
        d.put("incidentVendor", Map.of("id", vendorId, "name", inc.get("vendor_name"),
                "fourthParty", inc.get("vendor_parent") != null));
        d.put("reportedBy", Map.of("id", inc.get("reported_by_vendor_id"), "name", inc.get("reporter_name")));
        d.put("senderEmail", inc.get("sender_email"));
        d.put("incidentType", inc.get("incident_type"));
        d.put("severityReported", inc.get("severity_reported"));
        d.put("summary", inc.get("summary"));
        d.put("detectedAt", iso(det));
        d.put("notifiedAt", iso(notif));
        d.put("receivedAt", iso((LocalDateTime) inc.get("received_at")));
        d.put("resolvedAt", iso((LocalDateTime) inc.get("resolved_at")));

        Map<String, Object> clock = new LinkedHashMap<>();
        clock.put("startsAt", iso(det));
        clock.put("deadline", iso(det.plusHours(CLOCK_HOURS)));
        clock.put("notificationDelayMinutes", Duration.between(det, notif).toMinutes());
        clock.put("note", "Analyst decision required. Fortivance does not determine reportability.");
        d.put("clock", clock);

        d.put("sourceText", inc.get("extracted_text"));

        Map<String, Object> extraction = new LinkedHashMap<>();
        latestRow(id, "INCIDENT_EXTRACTION").ifPresent(row -> {
            extraction.put("model", row.get("model"));
            extraction.put("reviewStatus", row.get("review_status"));
            extraction.put("fields", camel(ai.readJson((String) row.get("content_json"))));
        });
        extraction.put("confidence", inc.get("extraction_confidence"));
        d.put("extraction", extraction);

        d.put("flags", jdbc.queryForList("SELECT description FROM incident_events WHERE incident_id = ? AND event_type = 'SECURITY_FLAG' ORDER BY id", String.class, id));
        d.put("impact", impact.analyse(vendorId));

        latestRow(id, "RESPONSE_PLAN").ifPresentOrElse(row -> {
            Map<String, Object> plan = new LinkedHashMap<>();
            plan.put("aiOutputId", row.get("id"));
            plan.put("model", row.get("model"));
            plan.put("reviewStatus", row.get("review_status"));
            plan.put("reviewComment", row.get("review_comment"));
            plan.put("content", camel(ai.readJson((String) row.get("content_json"))));
            d.put("plan", plan);
        }, () -> d.put("plan", null));

        d.put("actions", jdbc.queryForList("SELECT id, title, owner_team AS ownerTeam, status, priority, source, due_at AS dueAt " +
                "FROM actions WHERE incident_id = ? ORDER BY id", id));
        List<Map<String, Object>> events = new ArrayList<>();
        jdbc.query("SELECT event_type, occurred_at, actor, description FROM incident_events WHERE incident_id = ? ORDER BY occurred_at, id",
                rs -> {
                    events.add(Map.of("type", rs.getString(1), "at", iso(rs.getObject(2, LocalDateTime.class)),
                            "actor", rs.getString(3), "description", Objects.toString(rs.getString(4), "")));
                }, id);
        d.put("events", events);
        return d;
    }

    // =================================================================== helpers
    private Map<String, Object> incidentRow(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT i.*, v.name AS vendor_name, v.parent_vendor_id AS vendor_parent, r.name AS reporter_name,
                       d.extracted_text
                FROM incident_notices i
                JOIN vendors v ON v.id = i.vendor_id
                JOIN vendors r ON r.id = i.reported_by_vendor_id
                LEFT JOIN documents d ON d.id = i.document_id
                WHERE i.id = ?""", id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Incident " + id + " not found");
        Map<String, Object> row = rows.get(0);
        // MySQL DATETIME may arrive as LocalDateTime or Timestamp depending on driver settings; normalise.
        for (String k : List.of("detected_at", "notified_at", "received_at", "resolved_at")) {
            Object v = row.get(k);
            if (v instanceof Timestamp t) row.put(k, t.toLocalDateTime());
        }
        return row;
    }

    private Optional<Map<String, Object>> latestRow(long incidentId, String type) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, model, review_status, review_comment, content_json FROM ai_outputs " +
                "WHERE incident_id = ? AND output_type = ? ORDER BY id DESC LIMIT 1", incidentId, type);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    private Optional<JsonNode> latestOutput(long incidentId, String type) {
        return latestRow(incidentId, type).map(r -> ai.readJson((String) r.get("content_json")));
    }

    private void event(long incidentId, String type, LocalDateTime at, String actor, String desc) {
        jdbc.update("INSERT INTO incident_events (incident_id, event_type, occurred_at, actor, description) VALUES (?,?,?,?,?)",
                incidentId, type, ts(at), actor, desc.length() > 1000 ? desc.substring(0, 1000) : desc);
    }

    private void audit(String actor, String action, long incidentId, Map<String, Object> detail) {
        try {
            jdbc.update("INSERT INTO audit_log (actor, action, entity_type, entity_id, detail) VALUES (?,?,?,?,?)",
                    actor, action, "INCIDENT", incidentId, om.writeValueAsString(detail));
        } catch (Exception ignored) { }
    }

    private long insert(String sql, Object... args) {
        GeneratedKeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            return ps;
        }, kh);
        return kh.getKey().longValue();
    }

    private Long userId(String username) {
        if (username == null) return null;
        List<Long> ids = jdbc.queryForList("SELECT id FROM users WHERE username = ?", Long.class, username);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private void storeFile(String hash, String filename, byte[] bytes) {
        try {
            Path dir = Path.of(uploadDir);
            Files.createDirectories(dir);
            Files.write(dir.resolve(hash + ext(filename)), bytes);
        } catch (Exception ignored) {
            // Text is already stored in the database; the raw file is a convenience copy.
        }
    }

    private JsonNode schema(String json) {
        return ai.readJson(json);
    }

    /** snake_case keys -> camelCase, recursively, so the UI gets one naming style. */
    private JsonNode camel(JsonNode n) {
        if (n.isObject()) {
            ObjectNode o = om.createObjectNode();
            n.fields().forEachRemaining(e -> {
                String[] parts = e.getKey().split("_");
                StringBuilder k = new StringBuilder(parts[0]);
                for (int i = 1; i < parts.length; i++)
                    if (!parts[i].isEmpty()) k.append(Character.toUpperCase(parts[i].charAt(0))).append(parts[i].substring(1));
                o.set(k.toString(), camel(e.getValue()));
            });
            return o;
        }
        if (n.isArray()) {
            ArrayNode a = om.createArrayNode();
            n.forEach(c -> a.add(camel(c)));
            return a;
        }
        return n;
    }

    private static String text(JsonNode x, String field) {
        JsonNode v = x.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }

    static LocalDateTime parseTime(String s, LocalDateTime fallback) {
        if (s == null || s.isBlank()) return fallback;
        try {
            return OffsetDateTime.parse(s).atZoneSameInstant(IST).toLocalDateTime();
        } catch (DateTimeParseException e) {
            try { return LocalDateTime.parse(s); } catch (DateTimeParseException e2) { return fallback; }
        }
    }

    static String iso(LocalDateTime t) {
        return t == null ? null : t.atOffset(IST).toString();
    }

    private static Timestamp ts(LocalDateTime t) {
        return t == null ? null : Timestamp.valueOf(t);
    }

    private static String ext(String filename) {
        if (filename == null || !filename.contains(".")) return "";
        String e = filename.substring(filename.lastIndexOf('.')).toLowerCase();
        return e.matches("\\.[a-z0-9]{1,5}") ? e : "";
    }

    private static String safe(String s) {
        return s == null ? "upload" : s.replaceAll("[^A-Za-z0-9._ -]", "_");
    }
}
