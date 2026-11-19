package com.fortivance.demo;

import com.fortivance.audit.AuditService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Lets a signed-in demo user see their own request and extend their access. The request id always
 * comes from the session (never from the URL), so a user can only read or extend their own access.
 */
@Service
public class DemoAccessService {

    public enum ExtendStatus { EXTENDED, MAX_REACHED, NOT_FOUND }

    public record ExtendResult(ExtendStatus status, Map<String, Object> view) { }

    private static final String SELECT = "SELECT email, name, phone, purpose, status, created_at, access_expires_at, "
            + "COALESCE(username, analyst_username) AS username, extension_count FROM demo_requests WHERE id = ?";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final AuditService audit;
    private final Duration extension;
    private final int maxExtensions;
    private final Clock clock;

    @Autowired
    public DemoAccessService(JdbcTemplate jdbc, PlatformTransactionManager txManager, AuditService audit,
                             @Value("${fortivance.demo.access-hours:24}") int extensionHours,
                             @Value("${fortivance.demo.max-extensions:2}") int maxExtensions) {
        this(jdbc, new TransactionTemplate(txManager), audit, Duration.ofHours(extensionHours), maxExtensions,
                Clock.systemUTC());
    }

    DemoAccessService(JdbcTemplate jdbc, TransactionTemplate tx, AuditService audit, Duration extension,
                      int maxExtensions, Clock clock) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.audit = audit;
        this.extension = extension;
        this.maxExtensions = maxExtensions;
        this.clock = clock;
    }

    public Optional<Map<String, Object>> view(long requestId) {
        List<Map<String, Object>> rows = jdbc.queryForList(SELECT, requestId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(toView(rows.get(0)));
    }

    public ExtendResult extend(long requestId, String actor) {
        ExtendResult result = tx.execute(status -> {
            List<Map<String, Object>> rows = jdbc.queryForList(SELECT + " FOR UPDATE", requestId);
            if (rows.isEmpty()) {
                return new ExtendResult(ExtendStatus.NOT_FOUND, null);
            }
            Map<String, Object> row = rows.get(0);
            int used = ((Number) row.get("extension_count")).intValue();
            if (used >= maxExtensions || !DemoRequestService.STATUS_PROVISIONED.equals(row.get("status"))) {
                return new ExtendResult(ExtendStatus.MAX_REACHED, toView(row));
            }
            Instant now = clock.instant();
            Instant current = toInstant(row.get("access_expires_at"));
            Instant from = current != null && current.isAfter(now) ? current : now;
            Timestamp next = Timestamp.from(from.plus(extension));

            jdbc.update("UPDATE demo_requests SET access_expires_at = ?, extension_count = extension_count + 1 "
                    + "WHERE id = ?", next, requestId);
            jdbc.update("UPDATE users SET expires_at = ? WHERE demo_request_id = ?", next, requestId);

            row.put("access_expires_at", next);
            row.put("extension_count", used + 1);
            return new ExtendResult(ExtendStatus.EXTENDED, toView(row));
        });
        if (result != null && result.status() == ExtendStatus.EXTENDED) {
            audit.log(actor, "DEMO_ACCESS_EXTENDED", "DEMO_REQUEST", requestId, Map.of(
                    "extensionCount", result.view().get("extensionCount"),
                    "accessExpiresAt", result.view().get("accessExpiresAt")));
        }
        return result;
    }

    Map<String, Object> toView(Map<String, Object> row) {
        int used = ((Number) row.get("extension_count")).intValue();
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("email", row.get("email"));
        v.put("name", row.get("name"));
        v.put("phone", row.get("phone"));
        v.put("purpose", row.get("purpose"));
        v.put("requestedAt", iso(toInstant(row.get("created_at"))));
        v.put("accessExpiresAt", iso(toInstant(row.get("access_expires_at"))));
        v.put("username", row.get("username"));
        v.put("extensionCount", used);
        v.put("maxExtensions", maxExtensions);
        v.put("canExtend", used < maxExtensions && DemoRequestService.STATUS_PROVISIONED.equals(row.get("status")));
        return v;
    }

    private static Instant toInstant(Object value) {
        if (value instanceof Timestamp ts) {
            return ts.toInstant();
        }
        if (value instanceof java.time.LocalDateTime ldt) {
            return ldt.atZone(DemoMailer.IST).toInstant();
        }
        return null;
    }

    private static String iso(Instant t) {
        return t == null ? null : DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(t.atZone(DemoMailer.IST));
    }
}
