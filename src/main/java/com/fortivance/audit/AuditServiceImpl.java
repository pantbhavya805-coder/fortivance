package com.fortivance.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class AuditServiceImpl implements AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditServiceImpl.class);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public AuditServiceImpl(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void log(String actor, String action, String entityType, Long entityId, Map<String, Object> detail) {
        String detailJson = null;
        if (detail != null && !detail.isEmpty()) {
            try {
                detailJson = objectMapper.writeValueAsString(detail);
            } catch (JsonProcessingException e) {
                log.warn("Failed to serialize audit detail to JSON", e);
                detailJson = "{}";
            }
        }

        String sql = "INSERT INTO audit_log (actor, action, entity_type, entity_id, detail) VALUES (?, ?, ?, ?, ?)";
        jdbcTemplate.update(sql, actor != null ? actor : "SYSTEM", action, entityType, entityId, detailJson);
    }
}
