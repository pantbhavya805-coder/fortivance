package com.fortivance.security;

import com.fortivance.audit.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/api/me")
public class MeController {

    public record RoleRequest(String role) { }

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final AdminAccess adminAccess;
    private final SecurityContextRepository contextRepository = new HttpSessionSecurityContextRepository();

    public MeController(JdbcTemplate jdbc, AuditService audit, AdminAccess adminAccess) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.adminAccess = adminAccess;
    }

    @GetMapping
    public ResponseEntity<?> me(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof UserPrincipal principal)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Unauthorized"));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", principal.getId());
        body.put("username", principal.getUsername());
        body.put("displayName", principal.getDisplayName());
        body.put("role", principal.getRole());
        body.put("team", principal.getTeam() != null ? principal.getTeam() : "");
        body.put("canSwitchRole", principal.canSwitchRole());
        body.put("isAdmin", adminAccess.isAdmin(principal));
        return ResponseEntity.ok(body);
    }

    /**
     * Switches the signed-in user between Analyst and Approver. The new role is stored on the account (so it
     * is kept for the next login) and replaces the role in the current session immediately.
     */
    @PostMapping("/role")
    public ResponseEntity<Map<String, String>> switchRole(Authentication authentication,
                                                          @RequestBody RoleRequest req,
                                                          HttpServletRequest request,
                                                          HttpServletResponse response) {
        if (authentication == null || !(authentication.getPrincipal() instanceof UserPrincipal principal)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Unauthorized"));
        }
        String role = req == null || req.role() == null ? "" : req.role().trim().toUpperCase(Locale.ROOT);
        if (!UserPrincipal.SWITCHABLE_ROLES.contains(role)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Role must be ANALYST or APPROVER."));
        }
        if (!principal.canSwitchRole()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "This account cannot switch roles."));
        }
        if (!role.equals(principal.getRole())) {
            jdbc.update("UPDATE users SET role = ? WHERE id = ?", role, principal.getId());
            UserPrincipal updated = principal.withRole(role);
            UsernamePasswordAuthenticationToken auth =
                    new UsernamePasswordAuthenticationToken(updated, null, updated.getAuthorities());
            auth.setDetails(authentication.getDetails());
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(auth);
            SecurityContextHolder.setContext(context);
            contextRepository.saveContext(context, request, response);
            audit.log(principal.getUsername(), "ROLE_SWITCHED", "USER", principal.getId(),
                    Map.of("from", principal.getRole(), "to", role));
        }
        return ResponseEntity.ok(Map.of("role", role));
    }
}
