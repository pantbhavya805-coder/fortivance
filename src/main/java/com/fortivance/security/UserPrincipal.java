package com.fortivance.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.time.Instant;
import java.util.Collection;

public class UserPrincipal implements UserDetails {

    private final Long id;
    private final String username;
    private final String displayName;
    private final String password;
    private final String role;
    private final String team;
    private final boolean active;
    private final Collection<? extends GrantedAuthority> authorities;
    /** Null for permanent accounts. Refreshed by DemoExpiryFilter when an extension is found. */
    private volatile Instant expiresAt;
    private final Long demoRequestId;

    public UserPrincipal(Long id, String username, String displayName, String password,
                         String role, String team, boolean active,
                         Collection<? extends GrantedAuthority> authorities) {
        this(id, username, displayName, password, role, team, active, authorities, null, null);
    }

    public UserPrincipal(Long id, String username, String displayName, String password,
                         String role, String team, boolean active,
                         Collection<? extends GrantedAuthority> authorities,
                         Instant expiresAt, Long demoRequestId) {
        this.id = id;
        this.username = username;
        this.displayName = displayName;
        this.password = password;
        this.role = role;
        this.team = team;
        this.active = active;
        this.authorities = authorities;
        this.expiresAt = expiresAt;
        this.demoRequestId = demoRequestId;
    }

    public Long getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getRole() {
        return role;
    }

    public String getTeam() {
        return team;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Long getDemoRequestId() {
        return demoRequestId;
    }

    /** Roles a user can switch between from the header (ADMIN is not switchable). */
    public static final java.util.Set<String> SWITCHABLE_ROLES = java.util.Set.of("ANALYST", "APPROVER");

    public boolean canSwitchRole() {
        return role != null && SWITCHABLE_ROLES.contains(role);
    }

    /** A copy of this principal with a different role (and matching authority). */
    public UserPrincipal withRole(String newRole) {
        return new UserPrincipal(id, username, displayName, password, newRole, team, active,
                java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_" + newRole)),
                expiresAt, demoRequestId);
    }

    public boolean isExpiredAt(Instant now) {
        return expiresAt != null && !now.isBefore(expiresAt);
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public String getPassword() {
        return password;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isAccountNonExpired() {
        return !isExpiredAt(Instant.now());
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return active;
    }
}
