package com.fortivance.demo;

import com.fortivance.audit.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DemoAccessServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-20T06:00:00Z");   // 11:30 IST

    private JdbcTemplate jdbc;
    private AuditService audit;
    private DemoAccessService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        audit = mock(AuditService.class);
        TransactionTemplate tx = mock(TransactionTemplate.class);
        when(tx.execute(any())).thenAnswer(inv -> ((TransactionCallback<Object>) inv.getArgument(0)).doInTransaction(null));
        service = new DemoAccessService(jdbc, tx, audit, Duration.ofHours(24), 2, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void row(int extensionsUsed, Instant expires, String status) {
        Map<String, Object> r = new HashMap<>();
        r.put("email", "jane@acme.com");
        r.put("name", "Acme");
        r.put("phone", null);
        r.put("purpose", "Evaluating incident triage for our bank.");
        r.put("status", status);
        r.put("created_at", Timestamp.from(NOW.minusSeconds(3600)));
        r.put("access_expires_at", Timestamp.from(expires));
        r.put("username", "jane@acme.com");
        r.put("extension_count", extensionsUsed);
        when(jdbc.queryForList(anyString(), eq(7L))).thenReturn(List.of(r));
    }

    @Test
    void viewShowsIstTimesAndExtensionState() {
        row(0, NOW.plusSeconds(3600), "PROVISIONED");
        Map<String, Object> v = service.view(7L).orElseThrow();
        assertThat(v.get("accessExpiresAt")).isEqualTo("2026-11-20T12:30:00+05:30");
        assertThat(v.get("canExtend")).isEqualTo(true);
        assertThat(v.get("maxExtensions")).isEqualTo(2);
        assertThat(v.get("username")).isEqualTo("jane@acme.com");
        assertThat(v).containsKey("phone").doesNotContainKeys("password", "analystUsername", "approverUsername");
    }

    @Test
    void extendAdds24HoursToCurrentExpiryForBothAccounts() {
        Instant expires = NOW.plusSeconds(3600);
        row(0, expires, "PROVISIONED");

        DemoAccessService.ExtendResult r = service.extend(7L, "acme-analyst");

        assertThat(r.status()).isEqualTo(DemoAccessService.ExtendStatus.EXTENDED);
        assertThat(r.view().get("extensionCount")).isEqualTo(1);
        assertThat(r.view().get("canExtend")).isEqualTo(true);
        Timestamp expected = Timestamp.from(expires.plus(Duration.ofHours(24)));
        verify(jdbc).update(contains("UPDATE demo_requests"), eq(expected), eq(7L));
        verify(jdbc).update(contains("UPDATE users"), eq(expected), eq(7L));
        verify(audit).log(eq("acme-analyst"), eq("DEMO_ACCESS_EXTENDED"), eq("DEMO_REQUEST"), eq(7L), anyMap());
    }

    @Test
    void secondExtensionUsesUpTheAllowance() {
        row(1, NOW.plusSeconds(3600), "PROVISIONED");
        DemoAccessService.ExtendResult r = service.extend(7L, "acme-analyst");
        assertThat(r.view().get("extensionCount")).isEqualTo(2);
        assertThat(r.view().get("canExtend")).isEqualTo(false);
    }

    @Test
    void maximumReachedChangesNothing() {
        row(2, NOW.plusSeconds(3600), "PROVISIONED");
        DemoAccessService.ExtendResult r = service.extend(7L, "acme-analyst");
        assertThat(r.status()).isEqualTo(DemoAccessService.ExtendStatus.MAX_REACHED);
        verify(jdbc, never()).update(anyString(), any(), any());
        verifyNoInteractions(audit);
    }

    @Test
    void unknownRequestIsNotFound() {
        when(jdbc.queryForList(anyString(), eq(7L))).thenReturn(List.of());
        assertThat(service.extend(7L, "x").status()).isEqualTo(DemoAccessService.ExtendStatus.NOT_FOUND);
        assertThat(service.view(7L)).isEmpty();
    }
}
