package com.fortivance.demo;

import com.fortivance.security.AdminAccess;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin panel data: every demo request with its account's expiry status. Read-only. Access is limited to the
 * accounts in AdminAccess, by SecurityConfig and again here (defence in depth). Passwords are never returned.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminDemoController {

    static final Duration EXPIRING_SOON = Duration.ofHours(6);

    private static final String SQL = """
            SELECT d.id, d.email, d.name, d.phone, d.purpose, d.status, d.created_at, d.access_expires_at,
                   d.extension_count, d.mail_sent_at,
                   COALESCE(d.username, d.analyst_username) AS username,
                   u.role, u.active
              FROM demo_requests d
              LEFT JOIN users u ON u.username = COALESCE(d.username, d.analyst_username)
             ORDER BY d.created_at DESC
            """;

    private final JdbcTemplate jdbc;
    private final AdminAccess adminAccess;
    private final int maxExtensions;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public AdminDemoController(JdbcTemplate jdbc, AdminAccess adminAccess,
                               @org.springframework.beans.factory.annotation.Value("${fortivance.demo.max-extensions:2}")
                               int maxExtensions) {
        this(jdbc, adminAccess, maxExtensions, Clock.systemUTC());
    }

    AdminDemoController(JdbcTemplate jdbc, AdminAccess adminAccess, int maxExtensions, Clock clock) {
        this.jdbc = jdbc;
        this.adminAccess = adminAccess;
        this.maxExtensions = maxExtensions;
        this.clock = clock;
    }

    @GetMapping("/demo-accounts")
    public ResponseEntity<?> demoAccounts(Authentication auth) {
        if (!adminAccess.isAdmin(auth)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Admins only."));
        }
        boolean showPersonal = adminAccess.isOwner(auth);
        Instant now = clock.instant();
        List<Map<String, Object>> accounts = new ArrayList<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String s : List.of("ACTIVE", "EXPIRING_SOON", "EXPIRED", "NOT_ACTIVATED")) {
            counts.put(s, 0);
        }
        for (Map<String, Object> r : jdbc.queryForList(SQL)) {
            Instant expires = instant(r.get("access_expires_at"));
            String state = accessState((String) r.get("status"), r.get("active"), expires, now);
            counts.merge(state, 1, Integer::sum);

            Map<String, Object> a = new LinkedHashMap<>();
            a.put("id", r.get("id"));
            a.put("email", r.get("email"));
            a.put("username", r.get("username"));
            a.put("name", r.get("name"));
            // Phone and purpose are personal details: owner only. They are left out of the response entirely.
            a.put("phone", showPersonal ? r.get("phone") : null);
            a.put("purpose", showPersonal ? r.get("purpose") : null);
            a.put("requestStatus", r.get("status"));
            a.put("accessState", state);
            a.put("role", r.get("role"));
            a.put("requestedAt", iso(instant(r.get("created_at"))));
            a.put("accessExpiresAt", iso(expires));
            a.put("minutesLeft", expires == null || !expires.isAfter(now) ? 0 : Duration.between(now, expires).toMinutes());
            a.put("extensionCount", ((Number) r.get("extension_count")).intValue());
            a.put("maxExtensions", maxExtensions);
            a.put("emailSentAt", iso(instant(r.get("mail_sent_at"))));
            accounts.add(a);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("generatedAt", iso(now));
        body.put("personalDetailsVisible", showPersonal);
        body.put("total", accounts.size());
        body.put("counts", counts);
        body.put("accounts", accounts);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    /**
     * NOT_ACTIVATED: the welcome email was never sent (or the account was switched off);
     * otherwise EXPIRED, EXPIRING_SOON (under 6 hours left) or ACTIVE.
     */
    static String accessState(String requestStatus, Object userActive, Instant expires, Instant now) {
        boolean active = Boolean.TRUE.equals(userActive)
                || (userActive instanceof Number n && n.intValue() == 1);
        if (!DemoRequestService.STATUS_PROVISIONED.equals(requestStatus) || !active) {
            return "NOT_ACTIVATED";
        }
        if (expires == null || !expires.isAfter(now)) {
            return "EXPIRED";
        }
        return Duration.between(now, expires).compareTo(EXPIRING_SOON) < 0 ? "EXPIRING_SOON" : "ACTIVE";
    }

    private static Instant instant(Object v) {
        if (v instanceof Timestamp ts) {
            return ts.toInstant();
        }
        if (v instanceof java.time.LocalDateTime ldt) {
            return ldt.atZone(DemoMailer.IST).toInstant();
        }
        return null;
    }

    private static String iso(Instant t) {
        return t == null ? null : DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(t.atZone(DemoMailer.IST));
    }
}
