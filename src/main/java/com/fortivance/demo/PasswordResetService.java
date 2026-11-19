package com.fortivance.demo;

import com.fortivance.audit.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * Forgot-password flow for demo accounts: a one-time link (valid 30 minutes) is emailed to the account's
 * address, and the password changes only when the owner of that mailbox follows it. Only a SHA-256 hash of
 * the token is stored. The request step always answers the same way, so it never reveals whether an email
 * address has an account. Owner and judge accounts are excluded (their passwords come from .env).
 */
@Service
public class PasswordResetService {

    public enum ResetStatus { CHANGED, INVALID_TOKEN, WEAK_PASSWORD, RATE_LIMITED }

    public record ResetResult(ResetStatus status, String message) { }

    static final Duration TOKEN_LIFETIME = Duration.ofMinutes(30);
    static final Pattern TOKEN_FORMAT = Pattern.compile("^[0-9a-f]{64}$");
    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final PasswordEncoder encoder;
    private final DemoMailer mailer;
    private final AuditService audit;
    private final Clock clock;
    /** Sends reset emails off the request thread, so response time does not reveal whether an account exists. */
    private final Executor mailExecutor;
    private final SlidingWindowRateLimiter requestsPerIp;
    private final SlidingWindowRateLimiter requestsPerEmail;
    private final SlidingWindowRateLimiter resetsPerIp;

    @Autowired
    public PasswordResetService(JdbcTemplate jdbc, PlatformTransactionManager txManager, PasswordEncoder encoder,
                                DemoMailer mailer, AuditService audit) {
        this(jdbc, new TransactionTemplate(txManager), encoder, mailer, audit, Clock.systemUTC(),
                Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "password-reset-mail");
                    t.setDaemon(true);
                    return t;
                }));
    }

    PasswordResetService(JdbcTemplate jdbc, TransactionTemplate tx, PasswordEncoder encoder, DemoMailer mailer,
                         AuditService audit, Clock clock, Executor mailExecutor) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.encoder = encoder;
        this.mailer = mailer;
        this.audit = audit;
        this.clock = clock;
        this.mailExecutor = mailExecutor;
        this.requestsPerIp = new SlidingWindowRateLimiter(5, Duration.ofHours(1), clock);
        this.requestsPerEmail = new SlidingWindowRateLimiter(3, Duration.ofHours(1), clock);
        this.resetsPerIp = new SlidingWindowRateLimiter(10, Duration.ofHours(1), clock);
    }

    /**
     * Returns false only when rate-limited. Otherwise the caller answers with the same generic message
     * whether or not an email was sent.
     */
    public boolean requestReset(String rawEmail, String clientIp) {
        if (!requestsPerIp.allow(clientIp)) {
            return false;
        }
        String email = rawEmail.trim().toLowerCase(Locale.ROOT);
        if (!requestsPerEmail.allow(email)) {
            return true; // stay silent: the answer must not differ for existing accounts
        }
        List<Map<String, Object>> users = jdbc.queryForList(
                "SELECT id, display_name, expires_at FROM users "
                        + "WHERE username = ? AND demo_request_id IS NOT NULL AND active = TRUE", email);
        if (users.isEmpty()) {
            return true;
        }
        Map<String, Object> u = users.get(0);
        Instant now = clock.instant();
        Object expires = u.get("expires_at");
        if (expires instanceof Timestamp ts && !ts.toInstant().isAfter(now)) {
            return true; // access already expired; a new password would not help
        }
        long userId = ((Number) u.get("id")).longValue();
        String token = newToken();
        tx.executeWithoutResult(status -> {
            // Only the newest link works.
            jdbc.update("UPDATE password_reset_tokens SET used_at = ? WHERE user_id = ? AND used_at IS NULL",
                    Timestamp.from(now), userId);
            jdbc.update("INSERT INTO password_reset_tokens (user_id, token_hash, expires_at, requested_ip) "
                            + "VALUES (?, ?, ?, ?)",
                    userId, sha256(token), Timestamp.from(now.plus(TOKEN_LIFETIME)), clientIp);
        });
        String name = (String) u.get("display_name");
        mailExecutor.execute(() -> {
            try {
                mailer.sendPasswordReset(email, name,
                        mailer.publicUrl() + "/reset-password.html?token=" + token, (int) TOKEN_LIFETIME.toMinutes());
                audit.log(email, "PASSWORD_RESET_REQUESTED", "USER", userId, Map.of());
            } catch (Exception e) {
                log.error("Password reset email for user {} failed: {}", userId, e.getMessage());
            }
        });
        return true;
    }

    public ResetResult reset(String token, String newPassword, String clientIp) {
        if (!resetsPerIp.allow(clientIp)) {
            return new ResetResult(ResetStatus.RATE_LIMITED, "Too many attempts. Please try again later.");
        }
        if (token == null || !TOKEN_FORMAT.matcher(token).matches()) {
            return invalidToken();
        }
        String problem = PasswordChangeService.strengthProblem(null, newPassword);
        if (problem != null) {
            return new ResetResult(ResetStatus.WEAK_PASSWORD, problem);
        }
        Instant now = clock.instant();
        Long userId = tx.execute(status -> {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT t.id, t.user_id, t.expires_at, t.used_at FROM password_reset_tokens t "
                            + "JOIN users u ON u.id = t.user_id "
                            + "WHERE t.token_hash = ? AND u.active = TRUE AND u.demo_request_id IS NOT NULL FOR UPDATE",
                    sha256(token));
            if (rows.isEmpty()) {
                return null;
            }
            Map<String, Object> t = rows.get(0);
            Timestamp expires = (Timestamp) t.get("expires_at");
            if (t.get("used_at") != null || expires == null || !expires.toInstant().isAfter(now)) {
                return null;
            }
            long uid = ((Number) t.get("user_id")).longValue();
            jdbc.update("UPDATE users SET password_hash = ? WHERE id = ?", encoder.encode(newPassword), uid);
            jdbc.update("UPDATE password_reset_tokens SET used_at = ? WHERE user_id = ? AND used_at IS NULL",
                    Timestamp.from(now), uid);
            return uid;
        });
        if (userId == null) {
            return invalidToken();
        }
        audit.log("password-reset", "PASSWORD_RESET", "USER", userId, Map.of());
        return new ResetResult(ResetStatus.CHANGED, "Your password has been changed. You can now sign in.");
    }

    private static ResetResult invalidToken() {
        return new ResetResult(ResetStatus.INVALID_TOKEN,
                "This reset link is invalid, has expired or has already been used. Request a new one.");
    }

    static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    static String sha256(String token) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
