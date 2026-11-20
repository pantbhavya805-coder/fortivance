package com.fortivance.admin;

import com.fortivance.audit.AuditService;
import com.fortivance.security.AccountRevocations;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Admin console operations on user accounts (never on ADMIN accounts):
 * list, disable, enable, extend demo access (at most 48 hours in total per account, separate from the user's own
 * self-service extensions) and delete (permanent: the account can never sign in again; the request record is
 * kept and the email may request a new demo). Disabling or deleting ends the user's open session immediately.
 */
@Service
public class AdminAccountService {

    public enum Status { OK, NOT_FOUND, BAD_REQUEST, CONFLICT }

    public record Result(Status status, String error, Map<String, Object> account) {
        static Result ok(Map<String, Object> account) {
            return new Result(Status.OK, null, account);
        }

        static Result fail(Status status, String error) {
            return new Result(status, error, null);
        }
    }

    static final int ADMIN_EXTENSION_CAP_HOURS = 48;
    static final Set<Integer> ALLOWED_HOURS = Set.of(24, 48);
    static final Duration EXPIRING_SOON = Duration.ofHours(6);
    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final SecureRandom RANDOM = new SecureRandom();

    private static final String SELECT = """
            SELECT u.id AS user_id, u.username, u.display_name, u.role, u.active, u.expires_at, u.last_login_at,
                   u.deleted_at, u.demo_request_id,
                   d.id AS request_id, d.email, d.name, d.phone, d.purpose, d.status, d.created_at,
                   d.access_expires_at, d.extension_count, d.admin_extension_hours
              FROM users u
              LEFT JOIN demo_requests d ON d.id = u.demo_request_id
             WHERE u.role <> 'ADMIN'
            """;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final PasswordEncoder encoder;
    private final AccountRevocations revocations;
    private final AuditService audit;
    private final int maxUserExtensions;
    private final Clock clock;

    @Autowired
    public AdminAccountService(JdbcTemplate jdbc, PlatformTransactionManager txManager, PasswordEncoder encoder,
                               AccountRevocations revocations, AuditService audit,
                               @Value("${fortivance.demo.max-extensions:2}") int maxUserExtensions) {
        this(jdbc, new TransactionTemplate(txManager), encoder, revocations, audit, maxUserExtensions,
                Clock.systemUTC());
    }

    AdminAccountService(JdbcTemplate jdbc, TransactionTemplate tx, PasswordEncoder encoder,
                        AccountRevocations revocations, AuditService audit, int maxUserExtensions, Clock clock) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.encoder = encoder;
        this.revocations = revocations;
        this.audit = audit;
        this.maxUserExtensions = maxUserExtensions;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ queries

    public Map<String, Object> list(boolean includeDeleted) {
        Instant now = clock.instant();
        String sql = SELECT + (includeDeleted ? "" : " AND u.deleted_at IS NULL") + " ORDER BY u.id DESC";
        List<Map<String, Object>> accounts = new ArrayList<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String s : List.of("ACTIVE", "EXPIRING_SOON", "EXPIRED", "DISABLED", "NOT_ACTIVATED", "DELETED")) {
            counts.put(s, 0);
        }
        for (Map<String, Object> row : jdbc.queryForList(sql)) {
            Map<String, Object> a = toView(row, now);
            counts.merge((String) a.get("state"), 1, Integer::sum);
            accounts.add(a);
        }
        if (!includeDeleted) {
            Integer deleted = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM users WHERE role <> 'ADMIN' AND deleted_at IS NOT NULL", Integer.class);
            counts.put("DELETED", deleted == null ? 0 : deleted);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("generatedAt", iso(now));
        body.put("counts", counts);
        body.put("accounts", accounts);
        return body;
    }

    private Map<String, Object> load(long userId) {
        List<Map<String, Object>> rows = jdbc.queryForList(SELECT + " AND u.id = ?", userId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ------------------------------------------------------------------ actions

    public Result disable(long userId, String admin) {
        Map<String, Object> row = load(userId);
        if (row == null) {
            return Result.fail(Status.NOT_FOUND, "Account not found.");
        }
        if (row.get("deleted_at") != null) {
            return Result.fail(Status.CONFLICT, "This account has been deleted.");
        }
        if (!isTrue(row.get("active"))) {
            return Result.fail(Status.CONFLICT, "This account is already disabled.");
        }
        jdbc.update("UPDATE users SET active = FALSE WHERE id = ?", userId);
        revocations.revoke(userId);
        audit.log(admin, "ADMIN_ACCOUNT_DISABLED", "USER", userId, Map.of("username", row.get("username")));
        return Result.ok(toView(load(userId), clock.instant()));
    }

    public Result enable(long userId, String admin) {
        Map<String, Object> row = load(userId);
        if (row == null) {
            return Result.fail(Status.NOT_FOUND, "Account not found.");
        }
        if (row.get("deleted_at") != null) {
            return Result.fail(Status.CONFLICT, "This account has been deleted.");
        }
        if (isTrue(row.get("active"))) {
            return Result.fail(Status.CONFLICT, "This account is already enabled.");
        }
        if (row.get("request_id") != null && !"PROVISIONED".equals(row.get("status"))) {
            return Result.fail(Status.CONFLICT,
                    "This demo was never activated (the welcome email was not sent). Ask the person to request again.");
        }
        jdbc.update("UPDATE users SET active = TRUE WHERE id = ?", userId);
        revocations.restore(userId);
        audit.log(admin, "ADMIN_ACCOUNT_ENABLED", "USER", userId, Map.of("username", row.get("username")));
        return Result.ok(toView(load(userId), clock.instant()));
    }

    public Result extend(long userId, Integer hours, String admin) {
        if (hours == null || !ALLOWED_HOURS.contains(hours)) {
            return Result.fail(Status.BAD_REQUEST, "Hours must be 24 or 48.");
        }
        Map<String, Object> row0 = load(userId);
        if (row0 == null) {
            return Result.fail(Status.NOT_FOUND, "Account not found.");
        }
        if (row0.get("request_id") == null) {
            return Result.fail(Status.BAD_REQUEST, "Only demo accounts have an access period to extend.");
        }
        if (row0.get("deleted_at") != null) {
            return Result.fail(Status.CONFLICT, "This account has been deleted.");
        }
        long requestId = ((Number) row0.get("request_id")).longValue();
        Instant now = clock.instant();

        Result outcome = tx.execute(status -> {
            List<Map<String, Object>> locked = jdbc.queryForList(
                    "SELECT access_expires_at, admin_extension_hours FROM demo_requests WHERE id = ? FOR UPDATE",
                    requestId);
            if (locked.isEmpty()) {
                return Result.fail(Status.NOT_FOUND, "Account not found.");
            }
            int used = ((Number) locked.get(0).get("admin_extension_hours")).intValue();
            if (used + hours > ADMIN_EXTENSION_CAP_HOURS) {
                int left = ADMIN_EXTENSION_CAP_HOURS - used;
                return Result.fail(Status.CONFLICT, left <= 0
                        ? "The admin extension allowance (48 hours) for this account is used up."
                        : "Only " + left + " more hours can be added by the admin (48 hours in total; " + used + " used).");
            }
            Instant current = instant(locked.get(0).get("access_expires_at"));
            Instant from = current != null && current.isAfter(now) ? current : now;
            Timestamp next = Timestamp.from(from.plus(Duration.ofHours(hours)));
            jdbc.update("UPDATE demo_requests SET access_expires_at = ?, admin_extension_hours = admin_extension_hours + ? "
                    + "WHERE id = ?", next, hours, requestId);
            jdbc.update("UPDATE users SET expires_at = ? WHERE demo_request_id = ? AND deleted_at IS NULL",
                    next, requestId);
            return null;
        });
        if (outcome != null) {
            return outcome;
        }
        audit.log(admin, "ADMIN_ACCESS_EXTENDED", "USER", userId,
                Map.of("username", row0.get("username"), "hours", hours));
        return Result.ok(toView(load(userId), clock.instant()));
    }

    public Result delete(long userId, String admin) {
        Map<String, Object> row = load(userId);
        if (row == null) {
            return Result.fail(Status.NOT_FOUND, "Account not found.");
        }
        if (row.get("request_id") == null) {
            return Result.fail(Status.BAD_REQUEST, "Only demo accounts can be deleted. Disable a test account instead.");
        }
        if (row.get("deleted_at") != null) {
            return Result.fail(Status.CONFLICT, "This account is already deleted.");
        }
        long requestId = ((Number) row.get("request_id")).longValue();
        Timestamp now = Timestamp.from(clock.instant());
        // An unusable random password, so the old one can never work again even if the row were re-enabled.
        byte[] junk = new byte[24];
        RANDOM.nextBytes(junk);
        String scrambled = encoder.encode(HexFormat.of().formatHex(junk));
        tx.executeWithoutResult(status -> {
            jdbc.update("UPDATE users SET active = FALSE, deleted_at = ?, password_hash = ? WHERE id = ?",
                    now, scrambled, userId);
            jdbc.update("UPDATE demo_requests SET status = 'DELETED' WHERE id = ?", requestId);
            jdbc.update("UPDATE password_reset_tokens SET used_at = ? WHERE user_id = ? AND used_at IS NULL",
                    now, userId);
        });
        revocations.revoke(userId);
        audit.log(admin, "ADMIN_ACCOUNT_DELETED", "USER", userId, Map.of("username", row.get("username")));
        return Result.ok(null);
    }

    // ------------------------------------------------------------------ view

    Map<String, Object> toView(Map<String, Object> r, Instant now) {
        boolean demo = r.get("request_id") != null;
        Instant expires = demo ? instant(r.get("access_expires_at")) : null;
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("userId", r.get("user_id"));
        a.put("requestId", r.get("request_id"));
        a.put("type", demo ? "DEMO" : "TEST");
        a.put("username", r.get("username"));
        a.put("email", demo ? r.get("email") : null);
        a.put("name", demo ? r.get("name") : r.get("display_name"));
        a.put("phone", demo ? r.get("phone") : null);
        a.put("purpose", demo ? r.get("purpose") : null);
        a.put("role", r.get("role"));
        a.put("state", state(demo, (String) r.get("status"), r.get("active"), r.get("deleted_at"), expires, now));
        a.put("active", isTrue(r.get("active")));
        a.put("requestedAt", demo ? iso(instant(r.get("created_at"))) : null);
        a.put("accessExpiresAt", iso(expires));
        a.put("minutesLeft", expires == null ? null
                : (expires.isAfter(now) ? Duration.between(now, expires).toMinutes() : 0L));
        a.put("extensionCount", demo ? ((Number) r.get("extension_count")).intValue() : null);
        a.put("maxExtensions", demo ? maxUserExtensions : null);
        a.put("adminExtensionHours", demo ? ((Number) r.get("admin_extension_hours")).intValue() : null);
        a.put("lastLoginAt", iso(instant(r.get("last_login_at"))));
        a.put("deletedAt", iso(instant(r.get("deleted_at"))));
        return a;
    }

    static String state(boolean demo, String requestStatus, Object active, Object deletedAt, Instant expires,
                        Instant now) {
        if (deletedAt != null) {
            return "DELETED";
        }
        if (demo && !"PROVISIONED".equals(requestStatus)) {
            return "NOT_ACTIVATED";
        }
        if (!isTrue(active)) {
            return "DISABLED";
        }
        if (!demo) {
            return "ACTIVE";
        }
        if (expires == null || !expires.isAfter(now)) {
            return "EXPIRED";
        }
        return Duration.between(now, expires).compareTo(EXPIRING_SOON) < 0 ? "EXPIRING_SOON" : "ACTIVE";
    }

    private static boolean isTrue(Object v) {
        return Boolean.TRUE.equals(v) || (v instanceof Number n && n.intValue() == 1);
    }

    private static Instant instant(Object v) {
        if (v instanceof Timestamp ts) {
            return ts.toInstant();
        }
        if (v instanceof LocalDateTime ldt) {
            return ldt.atZone(IST).toInstant();
        }
        return null;
    }

    private static String iso(Instant t) {
        return t == null ? null : DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(t.atZone(IST));
    }
}
