package com.fortivance.audit;

import com.fortivance.security.UserPrincipal;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.authentication.event.InteractiveAuthenticationSuccessEvent;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
public class SecurityAuditListener {

    private final AuditService auditService;

    public SecurityAuditListener(AuditService auditService) {
        this.auditService = auditService;
    }

    @EventListener
    public void onAuthenticationSuccess(InteractiveAuthenticationSuccessEvent event) {
        String actor = event.getAuthentication().getName();
        Long userId = null;
        if (event.getAuthentication().getPrincipal() instanceof UserPrincipal principal) {
            userId = principal.getId();
        }
        Map<String, Object> detail = new HashMap<>();
        detail.put("authType", event.getAuthentication().getClass().getSimpleName());
        auditService.log(actor, "LOGIN_SUCCESS", "USER", userId, detail);
    }

    @EventListener
    public void onAuthenticationFailure(AbstractAuthenticationFailureEvent event) {
        String actor = event.getAuthentication() != null ? event.getAuthentication().getName() : "UNKNOWN";
        Map<String, Object> detail = new HashMap<>();
        detail.put("error", event.getException() != null ? event.getException().getMessage() : "Authentication failed");
        auditService.log(actor, "LOGIN_FAILURE", "USER", null, detail);
    }
}
