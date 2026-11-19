package com.fortivance.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Creates or updates the permanent (non-expiring) accounts at startup: {@code bhavya} (owner) and
 * {@code judge}. Both can switch between Analyst and Approver; the role they last chose is kept across
 * restarts. Passwords are read from the environment (OWNER_PASSWORD, JUDGE_PASSWORD) and stored only as
 * bcrypt hashes; an empty password leaves that account untouched.
 */
@Component
public class AccountBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AccountBootstrap.class);
    private static final String TEAM = "Third-Party Risk";

    record Account(String username, String displayName, String initialRole) { }

    static final Account OWNER = new Account("bhavya", "Bhavya Pant", "APPROVER");
    static final Account JUDGE = new Account("judge", "Judge", "ANALYST");

    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final String ownerPassword;
    private final String judgePassword;

    public AccountBootstrap(JdbcTemplate jdbc, PasswordEncoder encoder,
                            @Value("${fortivance.accounts.owner-password:}") String ownerPassword,
                            @Value("${fortivance.accounts.judge-password:}") String judgePassword) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.ownerPassword = ownerPassword;
        this.judgePassword = judgePassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        ensure(OWNER, ownerPassword, "OWNER_PASSWORD");
        ensure(JUDGE, judgePassword, "JUDGE_PASSWORD");
    }

    void ensure(Account a, String password, String envName) {
        if (password == null || password.isBlank()) {
            log.info("{} not set; permanent account '{}' left unchanged", envName, a.username());
            return;
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT password_hash, active, expires_at, role FROM users WHERE username = ?", a.username());
        if (rows.isEmpty()) {
            jdbc.update("INSERT INTO users (username, display_name, password_hash, role, team, active) "
                            + "VALUES (?, ?, ?, ?, ?, TRUE)",
                    a.username(), a.displayName(), encoder.encode(password), a.initialRole(), TEAM);
            log.info("Created permanent account {}", a.username());
            return;
        }
        Map<String, Object> r = rows.get(0);
        boolean switchableRole = UserPrincipal.SWITCHABLE_ROLES.contains(String.valueOf(r.get("role")));
        boolean upToDate = Boolean.TRUE.equals(r.get("active"))
                && r.get("expires_at") == null
                && switchableRole
                && encoder.matches(password, (String) r.get("password_hash"));
        if (upToDate) {
            return;
        }
        // Keep the role the user last switched to; only repair it if it is not a switchable role.
        String role = switchableRole ? String.valueOf(r.get("role")) : a.initialRole();
        jdbc.update("UPDATE users SET password_hash = ?, display_name = ?, role = ?, team = ?, "
                        + "active = TRUE, expires_at = NULL, demo_request_id = NULL WHERE username = ?",
                encoder.encode(password), a.displayName(), role, TEAM, a.username());
        log.info("Updated permanent account {}", a.username());
    }
}
