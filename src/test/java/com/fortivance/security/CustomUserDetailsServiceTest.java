package com.fortivance.security;

import org.junit.jupiter.api.Test;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class CustomUserDetailsServiceTest {

    private static ResultSet row(String username, Long demoRequestId, Timestamp expires) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getLong("id")).thenReturn(9L);
        when(rs.getString("username")).thenReturn(username);
        when(rs.getString("display_name")).thenReturn("Name");
        when(rs.getString("password_hash")).thenReturn("$2a$10$hash");
        when(rs.getString("role")).thenReturn("APPROVER");
        when(rs.getString("team")).thenReturn("Team");
        when(rs.getBoolean("active")).thenReturn(true);
        when(rs.getTimestamp("expires_at")).thenReturn(expires);
        when(rs.getObject("demo_request_id", Long.class)).thenReturn(demoRequestId);
        // what getLong would report for SQL NULL; must not be used
        when(rs.getLong("demo_request_id")).thenReturn(demoRequestId == null ? 0L : demoRequestId);
        when(rs.wasNull()).thenReturn(false);
        return rs;
    }

    @Test
    void permanentAccountHasNoDemoRequest() throws Exception {
        UserPrincipal p = CustomUserDetailsService.map(row("bhavya", null, null));
        assertThat(p.getDemoRequestId()).isNull();
        assertThat(p.getExpiresAt()).isNull();
    }

    @Test
    void demoAccountKeepsItsRequestIdAndExpiry() throws Exception {
        Instant exp = Instant.parse("2026-11-21T00:00:00Z");
        UserPrincipal p = CustomUserDetailsService.map(row("jane@acme.com", 3L, Timestamp.from(exp)));
        assertThat(p.getDemoRequestId()).isEqualTo(3L);
        assertThat(p.getExpiresAt()).isEqualTo(exp);
        assertThat(p.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_APPROVER");
    }
}
