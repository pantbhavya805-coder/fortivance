package com.fortivance.demo;

import com.fortivance.security.UserPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Change the signed-in user's own password (demo accounts only). */
@RestController
@RequestMapping("/api/me/password")
public class PasswordController {

    public record ChangePasswordRequest(String currentPassword, String newPassword) { }

    private final PasswordChangeService service;

    public PasswordController(PasswordChangeService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<Map<String, String>> change(@AuthenticationPrincipal UserPrincipal user,
                                                      @RequestBody ChangePasswordRequest req) {
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Unauthorized"));
        }
        PasswordChangeService.Result r = service.change(user, req.currentPassword(), req.newPassword());
        // Wrong current password is a 400, not a 401: a 401 would make the page log the user out.
        return switch (r.status()) {
            case CHANGED -> ResponseEntity.ok(Map.of("status", "changed", "message", r.message()));
            case INVALID -> ResponseEntity.badRequest().body(Map.of("error", r.message(), "field", "newPassword"));
            case WRONG_CURRENT -> ResponseEntity.badRequest()
                    .body(Map.of("error", r.message(), "field", "currentPassword"));
            case NOT_ALLOWED -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", r.message()));
            case RATE_LIMITED -> ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of("error", r.message()));
        };
    }
}
