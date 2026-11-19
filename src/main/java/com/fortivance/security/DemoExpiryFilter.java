package com.fortivance.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Ends sessions of demo accounts once their access has expired. Login already rejects expired
 * accounts; this covers sessions that were opened before the expiry time.
 * The database is consulted only after the cached expiry has passed, so an extension made in
 * the meantime is picked up and the session continues.
 */
public class DemoExpiryFilter extends OncePerRequestFilter {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public DemoExpiryFilter(JdbcTemplate jdbc) {
        this(jdbc, Clock.systemUTC());
    }

    DemoExpiryFilter(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof UserPrincipal p) {
            Instant now = clock.instant();
            if (p.isExpiredAt(now) && !refreshedStillValid(p, now)) {
                SecurityContextHolder.clearContext();
                HttpSession session = request.getSession(false);
                if (session != null) {
                    session.invalidate();
                }
                if (request.getRequestURI().startsWith("/api/")) {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType("application/json");
                    response.setCharacterEncoding("UTF-8");
                    response.getWriter().write("{\"error\":\"Your demo access has expired.\",\"expired\":true}");
                } else {
                    response.sendRedirect("/login.html?error=expired");
                }
                return;
            }
        }
        chain.doFilter(request, response);
    }

    /** Re-reads the account; if it was extended, updates the cached expiry and returns true. */
    private boolean refreshedStillValid(UserPrincipal p, Instant now) {
        List<Timestamp> rows = jdbc.queryForList(
                "SELECT expires_at FROM users WHERE id = ? AND active = TRUE", Timestamp.class, p.getId());
        if (rows.isEmpty()) {
            return false;
        }
        Timestamp current = rows.get(0);
        if (current == null) {           // converted to a permanent account
            p.setExpiresAt(null);
            return true;
        }
        if (current.toInstant().isAfter(now)) {
            p.setExpiresAt(current.toInstant());
            return true;
        }
        return false;
    }
}
