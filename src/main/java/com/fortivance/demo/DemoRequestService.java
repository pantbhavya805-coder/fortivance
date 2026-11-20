package com.fortivance.demo;

import com.fortivance.audit.AuditService;
import com.fortivance.security.AccountRevocations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Handles a public demo request: stores it (one per email) and creates a personal account (username = the
 * email, password chosen by the requester) that expires after the access period, then sends a welcome email.
 *
 * The password is never stored or sent in plain text: the database keeps a bcrypt hash and the welcome email
 * does not repeat it. The account stays inactive until the welcome email has been handed to the SMTP server,
 * which confirms the address. A request whose email failed can be submitted again with a new password.
 */
@Service
public class DemoRequestService {

    public enum Outcome { CREATED, IGNORED, WEAK_PASSWORD, DUPLICATE, RATE_LIMITED, NOT_CONFIGURED, MAIL_FAILED }

    private static final Logger log = LoggerFactory.getLogger(DemoRequestService.class);
    static final String STATUS_PENDING = "PENDING";
    static final String STATUS_PROVISIONED = "PROVISIONED";
    static final String STATUS_MAIL_FAILED = "MAIL_FAILED";
    /** Deleted by the admin: the old account can never sign in again, but the email may request anew. */
    static final String STATUS_DELETED = "DELETED";

    /** The single demo account: the username is the requester's email address. */
    record Provisioned(long requestId, String username, Instant expiresAt) { }

    /** {@code credentials} is set only when the outcome is CREATED. */
    public record SubmitResult(Outcome outcome, Provisioned credentials) {
        static SubmitResult of(Outcome o) {
            return new SubmitResult(o, null);
        }
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final PasswordEncoder encoder;
    private final DemoMailer mailer;
    private final AuditService audit;
    private final AccountRevocations revocations;
    private final SlidingWindowRateLimiter limiter;
    private final Duration accessPeriod;
    private final Clock clock;

    @Autowired
    public DemoRequestService(JdbcTemplate jdbc, PlatformTransactionManager txManager, PasswordEncoder encoder,
                              DemoMailer mailer, AuditService audit, AccountRevocations revocations,
                              @Value("${fortivance.demo.access-hours:24}") int accessHours,
                              @Value("${fortivance.demo.requests-per-hour:3}") int requestsPerHour) {
        this(jdbc, new TransactionTemplate(txManager), encoder, mailer, audit, revocations,
                Duration.ofHours(accessHours), requestsPerHour, Clock.systemUTC());
    }

    DemoRequestService(JdbcTemplate jdbc, TransactionTemplate tx, PasswordEncoder encoder, DemoMailer mailer,
                       AuditService audit, AccountRevocations revocations, Duration accessPeriod,
                       int requestsPerHour, Clock clock) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.encoder = encoder;
        this.mailer = mailer;
        this.audit = audit;
        this.revocations = revocations;
        this.accessPeriod = accessPeriod;
        this.clock = clock;
        this.limiter = new SlidingWindowRateLimiter(requestsPerHour, Duration.ofHours(1), clock);
    }

    public SubmitResult submit(DemoRequestForm form, String clientIp) {
        if (form.website() != null && !form.website().isBlank()) {
            log.info("Demo request honeypot triggered; dropped");
            return SubmitResult.of(Outcome.IGNORED);
        }
        if (passwordProblem(form) != null) {
            return SubmitResult.of(Outcome.WEAK_PASSWORD);
        }
        if (!mailer.isConfigured()) {
            log.warn("Demo request received but mail is not configured (MAIL_USERNAME)");
            return SubmitResult.of(Outcome.NOT_CONFIGURED);
        }
        if (!limiter.allow(clientIp)) {
            return SubmitResult.of(Outcome.RATE_LIMITED);
        }

        String email = form.email().toLowerCase(Locale.ROOT);
        String phone = form.phone() == null || form.phone().isBlank() ? null : form.phone();

        Provisioned p;
        try {
            p = tx.execute(status -> provision(email, form.name(), phone, form.purpose(), form.password(), clientIp));
        } catch (DuplicateKeyException e) {
            return SubmitResult.of(Outcome.DUPLICATE); // two submissions for the same email at the same moment
        }
        if (p == null) {
            return SubmitResult.of(Outcome.DUPLICATE);
        }

        try {
            mailer.sendWelcome(email, form.name(), p.username(), p.expiresAt());
        } catch (Exception e) {
            log.error("Demo welcome email to request {} failed: {}", p.requestId(), e.getMessage());
            jdbc.update("UPDATE demo_requests SET status = ? WHERE id = ?", STATUS_MAIL_FAILED, p.requestId());
            audit.log("demo-request", "DEMO_REQUEST_MAIL_FAILED", "DEMO_REQUEST", p.requestId(),
                    Map.of("email", email));
            return SubmitResult.of(Outcome.MAIL_FAILED);
        }

        tx.executeWithoutResult(status -> {
            jdbc.update("UPDATE demo_requests SET status = ?, mail_sent_at = ? WHERE id = ?",
                    STATUS_PROVISIONED, Timestamp.from(clock.instant()), p.requestId());
            jdbc.update("UPDATE users SET active = TRUE WHERE demo_request_id = ? AND deleted_at IS NULL",
                    p.requestId());
        });
        // A re-used account (previously deleted by the admin) must not stay blocked for open sessions.
        jdbc.queryForList("SELECT id FROM users WHERE demo_request_id = ? AND deleted_at IS NULL", Long.class,
                p.requestId()).forEach(revocations::restore);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("email", email);
        detail.put("username", p.username());
        detail.put("expiresAt", p.expiresAt().toString());
        audit.log("demo-request", "DEMO_ACCESS_PROVISIONED", "DEMO_REQUEST", p.requestId(), detail);

        mailer.notifyOwner(email, form.name(), phone, form.purpose(), p.username(), p.expiresAt());
        return new SubmitResult(Outcome.CREATED, p);
    }

    /**
     * Runs inside a transaction. Returns null when the email already has a request that is not retryable.
     * Creates one account (username = email, role ANALYST; the user can switch to APPROVER), inactive until
     * the welcome email has been sent.
     */
    Provisioned provision(String email, String name, String phone, String purpose, String password,
                          String clientIp) {
        Instant expiresAt = clock.instant().plus(accessPeriod);
        Timestamp expiresTs = Timestamp.from(expiresAt);

        List<Map<String, Object>> existing = jdbc.queryForList(
                "SELECT id, status, username, analyst_username FROM demo_requests WHERE email = ? FOR UPDATE",
                email);

        if (!existing.isEmpty()) {
            Map<String, Object> row = existing.get(0);
            Object status = row.get("status");
            if (!STATUS_MAIL_FAILED.equals(status) && !STATUS_DELETED.equals(status)) {
                return null;
            }
            // The earlier welcome email never left, or the admin deleted the account: set the new password on
            // the same account and start a fresh access period.
            long id = ((Number) row.get("id")).longValue();
            String username = row.get("username") != null
                    ? (String) row.get("username") : (String) row.get("analyst_username");
            jdbc.update("UPDATE demo_requests SET name = ?, phone = ?, purpose = ?, client_ip = ?, status = ?, "
                            + "access_expires_at = ?, extension_count = 0, admin_extension_hours = 0 WHERE id = ?",
                    name, phone, purpose, clientIp, STATUS_PENDING, expiresTs, id);
            jdbc.update("UPDATE users SET password_hash = ?, active = FALSE, expires_at = ?, deleted_at = NULL "
                            + "WHERE username = ?",
                    encoder.encode(password), expiresTs, username);
            return new Provisioned(id, username, expiresAt);
        }

        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO demo_requests (email, name, phone, purpose, status, access_expires_at, client_ip, "
                            + "username) VALUES (?, ?, ?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, email);
            ps.setString(2, name);
            ps.setString(3, phone);
            ps.setString(4, purpose);
            ps.setString(5, STATUS_PENDING);
            ps.setTimestamp(6, expiresTs);
            ps.setString(7, clientIp);
            ps.setString(8, email);
            return ps;
        }, key);
        long id = key.getKey().longValue();

        // A clash on users.username (unique) raises DuplicateKeyException, reported as DUPLICATE.
        jdbc.update("INSERT INTO users (username, display_name, password_hash, role, team, active, expires_at, "
                        + "demo_request_id) VALUES (?, ?, ?, 'ANALYST', ?, FALSE, ?, ?)",
                email, truncate(name, 100), encoder.encode(password), truncate(name, 100), expiresTs, id);
        return new Provisioned(id, email, expiresAt);
    }

    /** Returns what is wrong with the chosen password, or null if it is acceptable. */
    public static String passwordProblem(DemoRequestForm form) {
        String problem = PasswordChangeService.strengthProblem(null, form.password());
        if (problem != null) {
            return problem;
        }
        String local = form.email() == null ? "" : form.email().split("@", 2)[0].toLowerCase(Locale.ROOT);
        if (local.length() >= 4 && form.password().toLowerCase(Locale.ROOT).contains(local)) {
            return "The password must not contain your email address.";
        }
        return null;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
