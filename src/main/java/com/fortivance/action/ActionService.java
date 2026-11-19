package com.fortivance.action;

import com.fortivance.audit.AuditService;
import com.fortivance.security.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class ActionService {

    private static final Set<String> VALID_STATUSES = Set.of("OPEN", "IN_PROGRESS", "DONE", "CANCELLED");

    private final JdbcTemplate jdbcTemplate;
    private final AuditService auditService;

    public ActionService(JdbcTemplate jdbcTemplate, AuditService auditService) {
        this.jdbcTemplate = jdbcTemplate;
        this.auditService = auditService;
    }

    public List<ActionRecord> findActions(Long incidentId, Long vendorId, String status) {
        StringBuilder sql = new StringBuilder("SELECT id, incident_id, vendor_id, evidence_mapping_id, title, owner_team, assigned_to, status, priority, source, due_at, created_at, closed_at FROM actions WHERE 1=1");
        List<Object> params = new ArrayList<>();

        if (incidentId != null) {
            sql.append(" AND incident_id = ?");
            params.add(incidentId);
        }
        if (vendorId != null) {
            sql.append(" AND vendor_id = ?");
            params.add(vendorId);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ?");
            params.add(status.toUpperCase());
        }
        sql.append(" ORDER BY id DESC");

        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> new ActionRecord(
                rs.getLong("id"),
                (Long) rs.getObject("incident_id"),
                (Long) rs.getObject("vendor_id"),
                (Long) rs.getObject("evidence_mapping_id"),
                rs.getString("title"),
                rs.getString("owner_team"),
                (Long) rs.getObject("assigned_to"),
                rs.getString("status"),
                rs.getString("priority"),
                rs.getString("source"),
                rs.getTimestamp("due_at") != null ? rs.getTimestamp("due_at").toLocalDateTime() : null,
                rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toInstant() : null,
                rs.getTimestamp("closed_at") != null ? rs.getTimestamp("closed_at").toLocalDateTime() : null
        ), params.toArray());
    }

    public ActionRecord createManualAction(CreateActionRequest req) {
        String priority = req.priority() != null ? req.priority().toUpperCase() : "MEDIUM";
        KeyHolder keyHolder = new GeneratedKeyHolder();

        String sql = "INSERT INTO actions (incident_id, vendor_id, evidence_mapping_id, title, owner_team, assigned_to, status, priority, source, due_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, 'OPEN', ?, 'MANUAL', ?)";

        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setObject(1, req.incidentId());
            ps.setObject(2, req.vendorId());
            ps.setObject(3, req.evidenceMappingId());
            ps.setString(4, req.title());
            ps.setString(5, req.ownerTeam());
            ps.setObject(6, req.assignedTo());
            ps.setString(7, priority);
            ps.setTimestamp(8, req.dueAt() != null ? Timestamp.valueOf(req.dueAt()) : null);
            return ps;
        }, keyHolder);

        Number key = keyHolder.getKey();
        Long id = key != null ? key.longValue() : null;

        Map<String, Object> detail = new HashMap<>();
        detail.put("title", req.title());
        detail.put("ownerTeam", req.ownerTeam());
        detail.put("priority", priority);
        detail.put("source", "MANUAL");
        if (req.incidentId() != null) detail.put("incidentId", req.incidentId());
        if (req.vendorId() != null) detail.put("vendorId", req.vendorId());

        auditService.log(CurrentUser.username(), "CREATE_ACTION", "ACTION", id, detail);

        return findById(id).orElse(null);
    }

    public ActionRecord createAiAction(Long incidentId, String title, String ownerTeam, String priority) {
        String actPriority = priority != null ? priority.toUpperCase() : "MEDIUM";
        KeyHolder keyHolder = new GeneratedKeyHolder();

        String sql = "INSERT INTO actions (incident_id, title, owner_team, status, priority, source) " +
                "VALUES (?, ?, ?, 'OPEN', ?, 'AI')";

        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setObject(1, incidentId);
            ps.setString(2, title);
            ps.setString(3, ownerTeam);
            ps.setString(4, actPriority);
            return ps;
        }, keyHolder);

        Number key = keyHolder.getKey();
        Long id = key != null ? key.longValue() : null;

        Map<String, Object> detail = new HashMap<>();
        detail.put("title", title);
        detail.put("ownerTeam", ownerTeam);
        detail.put("priority", actPriority);
        detail.put("source", "AI");
        if (incidentId != null) detail.put("incidentId", incidentId);

        auditService.log("SYSTEM", "CREATE_ACTION", "ACTION", id, detail);

        return findById(id).orElse(null);
    }

    public Optional<ActionRecord> findById(Long id) {
        String sql = "SELECT id, incident_id, vendor_id, evidence_mapping_id, title, owner_team, assigned_to, status, priority, source, due_at, created_at, closed_at FROM actions WHERE id = ?";
        List<ActionRecord> list = jdbcTemplate.query(sql, (rs, rowNum) -> new ActionRecord(
                rs.getLong("id"),
                (Long) rs.getObject("incident_id"),
                (Long) rs.getObject("vendor_id"),
                (Long) rs.getObject("evidence_mapping_id"),
                rs.getString("title"),
                rs.getString("owner_team"),
                (Long) rs.getObject("assigned_to"),
                rs.getString("status"),
                rs.getString("priority"),
                rs.getString("source"),
                rs.getTimestamp("due_at") != null ? rs.getTimestamp("due_at").toLocalDateTime() : null,
                rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toInstant() : null,
                rs.getTimestamp("closed_at") != null ? rs.getTimestamp("closed_at").toLocalDateTime() : null
        ), id);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public Optional<ActionRecord> updateAction(Long id, UpdateActionRequest req) {
        Optional<ActionRecord> existingOpt = findById(id);
        if (existingOpt.isEmpty()) {
            return Optional.empty();
        }

        StringBuilder sql = new StringBuilder("UPDATE actions SET ");
        List<Object> params = new ArrayList<>();
        Map<String, Object> detail = new HashMap<>();

        boolean needsComma = false;
        if (req.status() != null && !req.status().isBlank()) {
            String newStatus = req.status().toUpperCase();
            if (!VALID_STATUSES.contains(newStatus)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid action status: " + req.status());
            }
            sql.append("status = ?");
            params.add(newStatus);
            detail.put("status", newStatus);
            needsComma = true;
            if ("DONE".equals(newStatus)) {
                sql.append(", closed_at = ?");
                params.add(Timestamp.valueOf(LocalDateTime.now()));
                detail.put("closedAt", LocalDateTime.now().toString());
            }
        }
        if (req.assignedTo() != null) {
            if (needsComma) sql.append(", ");
            sql.append("assigned_to = ?");
            params.add(req.assignedTo());
            detail.put("assignedTo", req.assignedTo());
            needsComma = true;
        }
        if (req.ownerTeam() != null) {
            if (needsComma) sql.append(", ");
            sql.append("owner_team = ?");
            params.add(req.ownerTeam());
            detail.put("ownerTeam", req.ownerTeam());
            needsComma = true;
        }
        if (req.dueAt() != null) {
            if (needsComma) sql.append(", ");
            sql.append("due_at = ?");
            params.add(Timestamp.valueOf(req.dueAt()));
            detail.put("dueAt", req.dueAt().toString());
            needsComma = true;
        }

        if (needsComma) {
            sql.append(" WHERE id = ?");
            params.add(id);
            jdbcTemplate.update(sql.toString(), params.toArray());

            auditService.log(CurrentUser.username(), "UPDATE_ACTION", "ACTION", id, detail);
        }

        return findById(id);
    }
}
