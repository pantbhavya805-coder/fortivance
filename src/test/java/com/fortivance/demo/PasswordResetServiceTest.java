package com.fortivance.demo;

import com.fortivance.audit.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static com.fortivance.demo.PasswordResetService.ResetStatus.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PasswordResetServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-20T06:00:00Z");
    private static final String EMAIL = "jane@acme.com";

    private final PasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private JdbcTemplate jdbc;
    private DemoMailer mailer;
    private PasswordResetService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        mailer = mock(DemoMailer.class);
        when(mailer.publicUrl()).thenReturn("https://fortivance.in");
        TransactionTemplate tx = mock(TransactionTemplate.class);
        when(tx.execute(any())).thenAnswer(inv -> ((TransactionCallback<Object>) inv.getArgument(0)).doInTransaction(null));
        doAnswer(inv -> {
            ((Consumer<Object>) inv.getArgument(0)).accept(null);
            return null;
        }).when(tx).executeWithoutResult(any());
        service = new PasswordResetService(jdbc, tx, encoder, mailer, mock(AuditService.class),
                Clock.fixed(NOW, ZoneOffset.UTC), Runnable::run);
    }

    private void demoUser(Instant expires) {
        Map<String, Object> u = new HashMap<>();
        u.put("id", 9L);
        u.put("display_name", "Acme");
        u.put("expires_at", Timestamp.from(expires));
        when(jdbc.queryForList(contains("FROM users"), eq(EMAIL))).thenReturn(List.of(u));
    }

    private String requestAndCaptureToken() throws Exception {
        assertThat(service.requestReset("  Jane@Acme.com ", "1.1.1.1")).isTrue();
        ArgumentCaptor<String> link = ArgumentCaptor.forClass(String.class);
        verify(mailer).sendPasswordReset(eq(EMAIL), eq("Acme"), link.capture(), eq(30));
        assertThat(link.getValue()).startsWith("https://fortivance.in/reset-password.html?token=");
        return link.getValue().substring(link.getValue().indexOf('=') + 1);
    }

    @Test
    void unknownEmailGetsTheSameAnswerAndNoEmail() throws Exception {
        when(jdbc.queryForList(contains("FROM users"), eq(EMAIL))).thenReturn(List.of());
        assertThat(service.requestReset(EMAIL, "1.1.1.1")).isTrue();
        verify(mailer, never()).sendPasswordReset(any(), any(), any(), anyInt());
        verify(jdbc, never()).update(contains("INSERT INTO password_reset_tokens"), any(Object[].class));
    }

    @Test
    void storesOnlyTheHashOfTheEmailedToken() throws Exception {
        demoUser(NOW.plusSeconds(3600));
        String token = requestAndCaptureToken();

        assertThat(token).matches("[0-9a-f]{64}");
        ArgumentCaptor<Object> stored = ArgumentCaptor.forClass(Object.class);
        verify(jdbc).update(contains("INSERT INTO password_reset_tokens"), eq(9L), stored.capture(),
                any(Timestamp.class), eq("1.1.1.1"));
        assertThat(stored.getValue()).isEqualTo(PasswordResetService.sha256(token)).isNotEqualTo(token);
        // older links are invalidated first
        verify(jdbc).update(contains("SET used_at"), any(Timestamp.class), eq(9L));
    }

    @Test
    void expiredDemoAccessGetsNoLink() throws Exception {
        demoUser(NOW.minusSeconds(1));
        assertThat(service.requestReset(EMAIL, "1.1.1.1")).isTrue();
        verify(mailer, never()).sendPasswordReset(any(), any(), any(), anyInt());
    }

    @Test
    void requestsAreRateLimitedPerIp() {
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
        for (int i = 0; i < 5; i++) {
            assertThat(service.requestReset("user" + i + "@acme.com", "1.1.1.1")).isTrue();
        }
        assertThat(service.requestReset("user9@acme.com", "1.1.1.1")).isFalse();
    }

    private void tokenRow(String token, Instant expires, Instant usedAt) {
        Map<String, Object> t = new HashMap<>();
        t.put("id", 1L);
        t.put("user_id", 9L);
        t.put("expires_at", Timestamp.from(expires));
        t.put("used_at", usedAt == null ? null : Timestamp.from(usedAt));
        when(jdbc.queryForList(contains("FROM password_reset_tokens"), eq(PasswordResetService.sha256(token))))
                .thenReturn(List.of(t));
    }

    @Test
    void validTokenChangesPasswordOnce() {
        String token = PasswordResetService.newToken();
        tokenRow(token, NOW.plusSeconds(600), null);

        assertThat(service.reset(token, "NewSecret!2026", "1.1.1.1").status()).isEqualTo(CHANGED);
        verify(jdbc).update(contains("UPDATE users SET password_hash"),
                argThat((Object h) -> h instanceof String s && encoder.matches("NewSecret!2026", s)), eq(9L));
        verify(jdbc).update(contains("SET used_at"), any(Timestamp.class), eq(9L));
    }

    @Test
    void expiredOrUsedOrMalformedTokensAreRejected() {
        String expired = PasswordResetService.newToken();
        tokenRow(expired, NOW.minusSeconds(1), null);
        assertThat(service.reset(expired, "NewSecret!2026", "1.1.1.1").status()).isEqualTo(INVALID_TOKEN);

        String used = PasswordResetService.newToken();
        tokenRow(used, NOW.plusSeconds(600), NOW.minusSeconds(60));
        assertThat(service.reset(used, "NewSecret!2026", "1.1.1.1").status()).isEqualTo(INVALID_TOKEN);

        assertThat(service.reset("not-a-token", "NewSecret!2026", "1.1.1.1").status()).isEqualTo(INVALID_TOKEN);
        assertThat(service.reset(null, "NewSecret!2026", "1.1.1.1").status()).isEqualTo(INVALID_TOKEN);
        verify(jdbc, never()).update(contains("UPDATE users SET password_hash"), any(Object[].class));
    }

    @Test
    void weakPasswordIsRejectedBeforeTheTokenIsUsed() {
        String token = PasswordResetService.newToken();
        assertThat(service.reset(token, "short1", "1.1.1.1").status()).isEqualTo(WEAK_PASSWORD);
        verify(jdbc, never()).queryForList(anyString(), any(Object[].class));
    }
}
