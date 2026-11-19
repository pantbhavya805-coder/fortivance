package com.fortivance.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DemoExpiryFilterTest {

    private static final Instant NOW = Instant.parse("2026-11-19T10:00:00Z");

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final FilterChain chain = mock(FilterChain.class);
    private final DemoExpiryFilter filter = new DemoExpiryFilter(jdbc, Clock.fixed(NOW, ZoneOffset.UTC));

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private UserPrincipal login(Instant expiresAt) {
        UserPrincipal p = new UserPrincipal(7L, "acme-analyst", "Acme (Analyst)", "x", "ANALYST", "Acme", true,
                List.of(), expiresAt, 3L);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(p, null, p.getAuthorities()));
        return p;
    }

    private void dbExpiry(Timestamp value) {
        when(jdbc.queryForList(anyString(), eq(Timestamp.class), any(Object[].class)))
                .thenReturn(value == null ? Collections.emptyList() : List.of(value));
    }

    @Test
    void permanentAndUnexpiredAccountsPassWithoutDatabase() throws Exception {
        login(null);
        filter.doFilter(new MockHttpServletRequest("GET", "/api/vendors"), new MockHttpServletResponse(), chain);
        login(NOW.plusSeconds(60));
        filter.doFilter(new MockHttpServletRequest("GET", "/api/vendors"), new MockHttpServletResponse(), chain);
        verify(chain, times(2)).doFilter(any(), any());
        verifyNoInteractions(jdbc);
    }

    @Test
    void expiredApiCallGets401AndSessionEnds() throws Exception {
        login(NOW.minusSeconds(1));
        dbExpiry(Timestamp.from(NOW.minusSeconds(1)));
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/vendors");
        req.getSession(true);
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(res.getContentAsString()).contains("expired");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void expiredPageRedirectsToLogin() throws Exception {
        login(NOW.minusSeconds(1));
        dbExpiry(null); // account deactivated
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/index.html"), res, chain);
        assertThat(res.getRedirectedUrl()).isEqualTo("/login.html?error=expired");
    }

    @Test
    void extensionInDatabaseKeepsSessionAlive() throws Exception {
        UserPrincipal p = login(NOW.minusSeconds(1));
        Instant extended = NOW.plusSeconds(24 * 3600);
        dbExpiry(Timestamp.from(extended));

        filter.doFilter(new MockHttpServletRequest("GET", "/api/vendors"), new MockHttpServletResponse(), chain);

        verify(chain).doFilter(any(), any());
        assertThat(p.getExpiresAt()).isEqualTo(extended);
    }
}
