package com.fortivance.demo;

import com.fortivance.audit.AuditService;
import com.fortivance.security.UserPrincipal;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Lets a signed-in demo user change the password of their own account (analyst and approver are separate
 * accounts, each changed on its own). Permanent accounts are excluded: the judge accounts are shared, and
 * owner/judge passwords are reset from .env at every startup, so a change here would not last.
 */
@Service
public class PasswordChangeService {

    public enum Status { CHANGED, INVALID, WRONG_CURRENT, NOT_ALLOWED, RATE_LIMITED }

    public record Result(Status status, String message) { }

    static final int MIN_LENGTH = 10;
    static final int MAX_LENGTH = 64;   // bcrypt uses at most 72 bytes

    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final AuditService audit;
    private final SlidingWindowRateLimiter limiter;

    @Autowired
    public PasswordChangeService(JdbcTemplate jdbc, PasswordEncoder encoder, AuditService audit) {
        this(jdbc, encoder, audit, Clock.systemUTC());
    }

    PasswordChangeService(JdbcTemplate jdbc, PasswordEncoder encoder, AuditService audit, Clock clock) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.audit = audit;
        this.limiter = new SlidingWindowRateLimiter(5, Duration.ofMinutes(15), clock);
    }

    public Result change(UserPrincipal user, String current, String next) {
        if (user.getDemoRequestId() == null) {
            return new Result(Status.NOT_ALLOWED,
                    "The password for this account is managed by the administrator.");
        }
        if (!limiter.allow("user:" + user.getId())) {
            return new Result(Status.RATE_LIMITED, "Too many attempts. Please wait 15 minutes and try again.");
        }
        String problem = strengthProblem(current, next);
        if (problem != null) {
            return new Result(Status.INVALID, problem);
        }
        List<String> hashes = jdbc.queryForList(
                "SELECT password_hash FROM users WHERE id = ? AND demo_request_id IS NOT NULL AND active = TRUE",
                String.class, user.getId());
        if (hashes.isEmpty()) {
            return new Result(Status.NOT_ALLOWED, "This account can no longer be changed.");
        }
        if (current == null || !encoder.matches(current, hashes.get(0))) {
            return new Result(Status.WRONG_CURRENT, "Your current password is incorrect.");
        }
        jdbc.update("UPDATE users SET password_hash = ? WHERE id = ?", encoder.encode(next), user.getId());
        audit.log(user.getUsername(), "PASSWORD_CHANGED", "USER", user.getId(), Map.of());
        return new Result(Status.CHANGED, "Your password has been changed.");
    }

    /** Returns a message describing what is wrong with the new password, or null if it is acceptable. */
    static String strengthProblem(String current, String next) {
        if (next == null || next.length() < MIN_LENGTH || next.length() > MAX_LENGTH) {
            return "The new password must be " + MIN_LENGTH + " to " + MAX_LENGTH + " characters long.";
        }
        if (!next.chars().anyMatch(Character::isLetter) || !next.chars().anyMatch(Character::isDigit)) {
            return "The new password must contain at least one letter and one number.";
        }
        if (next.equals(current)) {
            return "The new password must be different from the current one.";
        }
        return null;
    }
}
