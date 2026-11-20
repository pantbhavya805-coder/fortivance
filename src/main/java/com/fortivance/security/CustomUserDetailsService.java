package com.fortivance.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;

@Service
public class CustomUserDetailsService implements UserDetailsService {

    private final JdbcTemplate jdbcTemplate;

    public CustomUserDetailsService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        String sql = "SELECT id, username, display_name, password_hash, role, team, active, expires_at, demo_request_id, deleted_at "
                + "FROM users WHERE username = ?";
        List<UserPrincipal> users = jdbcTemplate.query(sql, (rs, rowNum) -> map(rs),
                username == null ? "" : username.trim()); // the column's collation is case-insensitive

        if (users.isEmpty()) {
            throw new UsernameNotFoundException("User not found: " + username);
        }
        return users.get(0);
    }

    static UserPrincipal map(ResultSet rs) throws SQLException {
        String role = rs.getString("role");
        String authority = "ROLE_" + (role != null ? role.toUpperCase() : "ANALYST");
        Timestamp expires = rs.getTimestamp("expires_at");
        // getObject keeps SQL NULL as null. (getLong returns 0 for NULL, and wasNull() only reflects the most
        // recently read column, so reading it that way turned "no demo request" into id 0.)
        Long demoRequestId = rs.getObject("demo_request_id", Long.class);
        return new UserPrincipal(
                rs.getLong("id"),
                rs.getString("username"),
                rs.getString("display_name"),
                rs.getString("password_hash"),
                role,
                rs.getString("team"),
                rs.getBoolean("active") && rs.getTimestamp("deleted_at") == null, // deleted accounts never sign in
                List.of(new SimpleGrantedAuthority(authority)),
                expires != null ? expires.toInstant() : null,
                demoRequestId
        );
    }
}
