package com.fortivance.demo;

import com.fortivance.audit.AuditService;
import com.fortivance.security.UserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static com.fortivance.demo.PasswordChangeService.Status.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PasswordChangeServiceTest {

    private static final String CURRENT = "Kp7mQx2RtW9aZe";

    private final PasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private JdbcTemplate jdbc;
    private AuditService audit;
    private PasswordChangeService service;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        audit = mock(AuditService.class);
        when(jdbc.queryForList(anyString(), eq(String.class), any(Object[].class)))
                .thenReturn(List.of(encoder.encode(CURRENT)));
        service = new PasswordChangeService(jdbc, encoder, audit,
                Clock.fixed(Instant.parse("2026-11-20T06:00:00Z"), ZoneOffset.UTC));
    }

    private static UserPrincipal user(Long demoRequestId) {
        return new UserPrincipal(9L, "acme-analyst", "Acme (Analyst)", "x", "ANALYST", "Acme", true,
                List.of(), null, demoRequestId);
    }

    @Test
    void changesPasswordAndStoresOnlyAHash() {
        PasswordChangeService.Result r = service.change(user(3L), CURRENT, "NewSecret!2026");
        assertThat(r.status()).isEqualTo(CHANGED);
        verify(jdbc).update(contains("UPDATE users SET password_hash"),
                argThat((Object h) -> h instanceof String s && s.startsWith("$2") && encoder.matches("NewSecret!2026", s)),
                eq(9L));
        verify(audit).log(eq("acme-analyst"), eq("PASSWORD_CHANGED"), eq("USER"), eq(9L), anyMap());
    }

    @Test
    void wrongCurrentPasswordIsRejected() {
        assertThat(service.change(user(3L), "notTheOne123", "NewSecret!2026").status()).isEqualTo(WRONG_CURRENT);
        verify(jdbc, never()).update(anyString(), any(), any());
    }

    @Test
    void permanentAccountsCannotChangeHere() {
        assertThat(service.change(user(null), CURRENT, "NewSecret!2026").status()).isEqualTo(NOT_ALLOWED);
        verifyNoInteractions(jdbc);
    }

    @Test
    void strengthRules() {
        assertThat(PasswordChangeService.strengthProblem(CURRENT, "Sh0rt!")).contains("8 to 64");
        assertThat(PasswordChangeService.strengthProblem(CURRENT, "onlyletterslong")).contains("uppercase");
        assertThat(PasswordChangeService.strengthProblem(CURRENT, "1234567890123")).contains("uppercase");
        assertThat(PasswordChangeService.strengthProblem(CURRENT, "NoSymbolHere2026")).contains("symbol");
        assertThat(PasswordChangeService.strengthProblem(CURRENT, "nouppercase!2026")).contains("uppercase");
        assertThat(PasswordChangeService.strengthProblem("NewSecret!2026", "NewSecret!2026")).contains("different");
        assertThat(PasswordChangeService.strengthProblem(CURRENT, "NewSecret!2026")).isNull();
    }

    @Test
    void attemptsAreRateLimited() {
        for (int i = 0; i < 5; i++) {
            service.change(user(3L), "wrongGuess" + i, "NewSecret!2026");
        }
        assertThat(service.change(user(3L), CURRENT, "NewSecret!2026").status()).isEqualTo(RATE_LIMITED);
    }
}
