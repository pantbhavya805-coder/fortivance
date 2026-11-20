package com.fortivance.admin;

import com.fortivance.audit.AuditService;
import com.fortivance.security.AccountRevocations;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static com.fortivance.admin.AdminAccountService.Status.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AdminAccountServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-20T06:00:00Z");

    private JdbcTemplate jdbc;
    private AuditService audit;
    private AccountRevocations revocations;
    private AdminAccountService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        audit = mock(AuditService.class);
        revocations = new AccountRevocations();
        TransactionTemplate tx = mock(TransactionTemplate.class);
        when(tx.execute(any())).thenAnswer(inv -> ((TransactionCallback<Object>) inv.getArgument(0)).doInTransaction(null));
        doAnswer(inv -> {
            ((Consumer<Object>) inv.getArgument(0)).accept(null);
            return null;
        }).when(tx).executeWithoutResult(any());
        service = new AdminAccountService(jdbc, tx, new BCryptPasswordEncoder(4), revocations, audit, 2,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private Map<String, Object> demoRow(boolean active, int adminHours, Instant expires) {
        Map<String, Object> r = new HashMap<>();
        r.put("user_id", 7L);
        r.put("username", "jane@acme.com");
        r.put("display_name", "Acme");
        r.put("role", "ANALYST");
        r.put("active", active);
        r.put("deleted_at", null);
        r.put("last_login_at", null);
        r.put("request_id", 3L);
        r.put("email", "jane@acme.com");
        r.put("name", "Acme");
        r.put("phone", null);
        r.put("purpose", "Evaluating.");
        r.put("status", "PROVISIONED");
        r.put("created_at", Timestamp.from(NOW.minusSeconds(3600)));
        r.put("access_expires_at", Timestamp.from(expires));
        r.put("extension_count", 0);
        r.put("admin_extension_hours", adminHours);
        return r;
    }

    private void accountIs(Map<String, Object> row) {
        when(jdbc.queryForList(contains("AND u.id = ?"), eq(7L))).thenReturn(List.of(row));
    }

    private void lockedRequestIs(Instant expires, int adminHours) {
        Map<String, Object> d = new HashMap<>();
        d.put("access_expires_at", Timestamp.from(expires));
        d.put("admin_extension_hours", adminHours);
        when(jdbc.queryForList(contains("FOR UPDATE"), eq(3L))).thenReturn(List.of(d));
    }

    @Test
    void disableEndsSessionsAndEnableRestores() {
        accountIs(demoRow(true, 0, NOW.plusSeconds(3600)));
        assertThat(service.disable(7L, "bhavya").status()).isEqualTo(OK);
        verify(jdbc).update("UPDATE users SET active = FALSE WHERE id = ?", 7L);
        assertThat(revocations.isRevoked(7L)).isTrue();

        accountIs(demoRow(false, 0, NOW.plusSeconds(3600)));
        assertThat(service.enable(7L, "bhavya").status()).isEqualTo(OK);
        assertThat(revocations.isRevoked(7L)).isFalse();
    }

    @Test
    void disablingTwiceIsAConflict() {
        accountIs(demoRow(false, 0, NOW.plusSeconds(3600)));
        assertThat(service.disable(7L, "bhavya").status()).isEqualTo(CONFLICT);
    }

    @Test
    void adminExtensionAddsFromCurrentExpiry() {
        Instant expires = NOW.plusSeconds(3600);
        accountIs(demoRow(true, 0, expires));
        lockedRequestIs(expires, 0);
        assertThat(service.extend(7L, 24, "bhavya").status()).isEqualTo(OK);
        verify(jdbc).update(contains("admin_extension_hours = admin_extension_hours + ?"),
                eq(Timestamp.from(expires.plus(Duration.ofHours(24)))), eq(24), eq(3L));
    }

    @Test
    void adminExtensionRevivesAnExpiredAccountFromNow() {
        Instant expired = NOW.minusSeconds(3600);
        accountIs(demoRow(true, 0, expired));
        lockedRequestIs(expired, 0);
        assertThat(service.extend(7L, 48, "bhavya").status()).isEqualTo(OK);
        verify(jdbc).update(contains("admin_extension_hours = admin_extension_hours + ?"),
                eq(Timestamp.from(NOW.plus(Duration.ofHours(48)))), eq(48), eq(3L));
    }

    @Test
    void adminExtensionIsCappedAt48HoursInTotal() {
        accountIs(demoRow(true, 24, NOW.plusSeconds(3600)));
        lockedRequestIs(NOW.plusSeconds(3600), 24);
        AdminAccountService.Result r = service.extend(7L, 48, "bhavya");
        assertThat(r.status()).isEqualTo(CONFLICT);
        assertThat(r.error()).contains("24 more hours");

        lockedRequestIs(NOW.plusSeconds(3600), 48);
        assertThat(service.extend(7L, 24, "bhavya").error()).contains("used up");
        verify(jdbc, never()).update(contains("admin_extension_hours = admin_extension_hours + ?"), any(), any(), any());
    }

    @Test
    void onlyDayMultiplesUpTo48AreAccepted() {
        assertThat(service.extend(7L, 72, "bhavya").status()).isEqualTo(BAD_REQUEST);
        assertThat(service.extend(7L, 12, "bhavya").status()).isEqualTo(BAD_REQUEST);
        assertThat(service.extend(7L, null, "bhavya").status()).isEqualTo(BAD_REQUEST);
    }

    @Test
    void testAccountsCannotBeExtendedOrDeleted() {
        Map<String, Object> test = demoRow(true, 0, NOW);
        test.put("request_id", null);
        accountIs(test);
        assertThat(service.extend(7L, 24, "bhavya").status()).isEqualTo(BAD_REQUEST);
        assertThat(service.delete(7L, "bhavya").status()).isEqualTo(BAD_REQUEST);
    }

    @Test
    void deleteBlocksTheAccountAndKeepsTheRequest() {
        accountIs(demoRow(true, 0, NOW.plusSeconds(3600)));
        assertThat(service.delete(7L, "bhavya").status()).isEqualTo(OK);
        verify(jdbc).update(contains("SET active = FALSE, deleted_at = ?, password_hash = ?"),
                any(Timestamp.class), argThat((Object h) -> h instanceof String s && s.startsWith("$2")), eq(7L));
        verify(jdbc).update("UPDATE demo_requests SET status = 'DELETED' WHERE id = ?", 3L);
        verify(jdbc, never()).update(startsWith("DELETE"), any(Object[].class));
        assertThat(revocations.isRevoked(7L)).isTrue();
        verify(audit).log(eq("bhavya"), eq("ADMIN_ACCOUNT_DELETED"), eq("USER"), eq(7L), anyMap());
    }

    @Test
    void states() {
        Instant soon = NOW.plusSeconds(3600);
        assertThat(AdminAccountService.state(true, "PROVISIONED", true, null, NOW.plusSeconds(7 * 3600), NOW)).isEqualTo("ACTIVE");
        assertThat(AdminAccountService.state(true, "PROVISIONED", true, null, soon, NOW)).isEqualTo("EXPIRING_SOON");
        assertThat(AdminAccountService.state(true, "PROVISIONED", true, null, NOW, NOW)).isEqualTo("EXPIRED");
        assertThat(AdminAccountService.state(true, "PROVISIONED", false, null, soon, NOW)).isEqualTo("DISABLED");
        assertThat(AdminAccountService.state(true, "MAIL_FAILED", false, null, soon, NOW)).isEqualTo("NOT_ACTIVATED");
        assertThat(AdminAccountService.state(true, "DELETED", false, Timestamp.from(NOW), soon, NOW)).isEqualTo("DELETED");
        assertThat(AdminAccountService.state(false, null, true, null, null, NOW)).isEqualTo("ACTIVE");
    }
}
