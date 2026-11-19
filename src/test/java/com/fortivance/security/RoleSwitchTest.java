package com.fortivance.security;

import com.fortivance.audit.AuditService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RoleSwitchTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AuditService audit = mock(AuditService.class);
    private final MeController controller = new MeController(jdbc, audit);

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static Authentication login(String role) {
        UserPrincipal p = new UserPrincipal(5L, "jane@acme.com", "Acme", "hash", role, "Acme", true,
                List.of(new SimpleGrantedAuthority("ROLE_" + role)), Instant.parse("2026-11-21T00:00:00Z"), 3L);
        Authentication auth = new UsernamePasswordAuthenticationToken(p, null, p.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);
        return auth;
    }

    @Test
    void switchUpdatesDatabaseSessionAndAuthorities() {
        Authentication auth = login("ANALYST");
        MockHttpServletRequest req = new MockHttpServletRequest();
        ResponseEntity<Map<String, String>> res = controller.switchRole(auth,
                new MeController.RoleRequest("approver"), req, new MockHttpServletResponse());

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        verify(jdbc).update(contains("UPDATE users SET role"), eq("APPROVER"), eq(5L));

        Authentication now = SecurityContextHolder.getContext().getAuthentication();
        UserPrincipal p = (UserPrincipal) now.getPrincipal();
        assertThat(p.getRole()).isEqualTo("APPROVER");
        assertThat(now.getAuthorities()).extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_APPROVER");
        // expiry and demo link survive the switch
        assertThat(p.getExpiresAt()).isEqualTo(Instant.parse("2026-11-21T00:00:00Z"));
        assertThat(p.getDemoRequestId()).isEqualTo(3L);

        SecurityContext saved = (SecurityContext) req.getSession()
                .getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertThat(((UserPrincipal) saved.getAuthentication().getPrincipal()).getRole()).isEqualTo("APPROVER");
        verify(audit).log(eq("jane@acme.com"), eq("ROLE_SWITCHED"), eq("USER"), eq(5L), anyMap());
    }

    @Test
    void unknownRoleIsRejected() {
        Authentication auth = login("ANALYST");
        assertThat(controller.switchRole(auth, new MeController.RoleRequest("ADMIN"),
                new MockHttpServletRequest(), new MockHttpServletResponse()).getStatusCode().value()).isEqualTo(400);
        verifyNoInteractions(jdbc);
    }

    @Test
    void adminCannotSwitch() {
        Authentication auth = login("ADMIN");
        assertThat(controller.switchRole(auth, new MeController.RoleRequest("ANALYST"),
                new MockHttpServletRequest(), new MockHttpServletResponse()).getStatusCode().value()).isEqualTo(403);
        verifyNoInteractions(jdbc);
    }

    @Test
    void meReportsCanSwitchRole() {
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) controller.me(login("APPROVER")).getBody();
        assertThat(body).containsEntry("canSwitchRole", true).containsEntry("role", "APPROVER");
    }
}
