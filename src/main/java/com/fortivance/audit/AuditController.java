package com.fortivance.audit;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/audit")
public class AuditController {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public AuditController(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    public List<AuditRecord> list(
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) Long entityId,
            @RequestParam(defaultValue = "50") int limit
    ) {
        StringBuilder sql = new StringBuilder("SELECT id, actor, action, entity_type, entity_id, detail, created_at FROM audit_log WHERE 1=1");
        List<Object> params = new ArrayList<>();

        if (entityType != null && !entityType.isBlank()) {
            sql.append(" AND entity_type = ?");
            params.add(entityType);
        }
        if (entityId != null) {
            sql.append(" AND entity_id = ?");
            params.add(entityId);
        }

        sql.append(" ORDER BY created_at DESC, id DESC LIMIT ?");
        params.add(Math.max(1, Math.min(limit, 500)));

        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> mapAuditRecord(rs), params.toArray());
    }

    private AuditRecord mapAuditRecord(ResultSet rs) throws SQLException {
        Long id = rs.getLong("id");
        String actor = rs.getString("actor");
        String action = rs.getString("action");
        String entityType = rs.getString("entity_type");
        Long entityId = (Long) rs.getObject("entity_id");
        String detailJson = rs.getString("detail");
        Instant createdAt = rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toInstant() : null;

        Object detailObj = null;
        if (detailJson != null && !detailJson.isBlank()) {
            try {
                detailObj = objectMapper.readValue(detailJson, new TypeReference<Map<String, Object>>() {});
            } catch (Exception e) {
                detailObj = detailJson;
            }
        }

        return new AuditRecord(id, actor, action, entityType, entityId, detailObj, createdAt);
    }

    public record AuditRecord(
            Long id,
            String actor,
            String action,
            String entityType,
            Long entityId,
            Object detail,
            Instant createdAt
    ) {}
}
