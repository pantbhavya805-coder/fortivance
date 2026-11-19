package com.fortivance.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class CustomUserDetailsService implements UserDetailsService {

    private final JdbcTemplate jdbcTemplate;

    public CustomUserDetailsService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        String sql = "SELECT id, username, display_name, password_hash, role, team, active FROM users WHERE username = ?";
        List<UserPrincipal> users = jdbcTemplate.query(sql, (rs, rowNum) -> {
            Long id = rs.getLong("id");
            String uname = rs.getString("username");
            String displayName = rs.getString("display_name");
            String password = rs.getString("password_hash");
            String role = rs.getString("role");
            String team = rs.getString("team");
            boolean active = rs.getBoolean("active");
            String authority = "ROLE_" + (role != null ? role.toUpperCase() : "ANALYST");
            return new UserPrincipal(
                    id,
                    uname,
                    displayName,
                    password,
                    role,
                    team,
                    active,
                    List.of(new SimpleGrantedAuthority(authority))
            );
        }, username);

        if (users.isEmpty()) {
            throw new UsernameNotFoundException("User not found: " + username);
        }
        return users.get(0);
    }
}
