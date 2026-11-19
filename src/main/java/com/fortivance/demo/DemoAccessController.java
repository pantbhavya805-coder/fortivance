package com.fortivance.demo;

import com.fortivance.security.UserPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** The signed-in user's own demo access (used by /profile.html). */
@RestController
@RequestMapping("/api/me/demo")
public class DemoAccessController {

    private static final Map<String, String> NOT_DEMO =
            Map.of("error", "This account is not a demo account and does not expire.");

    private final DemoAccessService service;

    public DemoAccessController(DemoAccessService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<?> get(@AuthenticationPrincipal UserPrincipal user) {
        if (user == null || user.getDemoRequestId() == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(NOT_DEMO);
        }
        return service.view(user.getDemoRequestId())
                .<ResponseEntity<?>>map(v -> ResponseEntity.ok(withRole(v, user)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(NOT_DEMO));
    }

    /** The role is per session (it can be switched), so it comes from the signed-in user. */
    private static Map<String, Object> withRole(Map<String, Object> view, UserPrincipal user) {
        view.put("role", user.getRole());
        return view;
    }

    @PostMapping("/extend")
    public ResponseEntity<?> extend(@AuthenticationPrincipal UserPrincipal user) {
        if (user == null || user.getDemoRequestId() == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(NOT_DEMO);
        }
        DemoAccessService.ExtendResult r = service.extend(user.getDemoRequestId(), user.getUsername());
        return switch (r.status()) {
            case EXTENDED -> ResponseEntity.ok(withRole(r.view(), user));
            case MAX_REACHED -> ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "Maximum extensions reached"));
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(NOT_DEMO);
        };
    }
}
