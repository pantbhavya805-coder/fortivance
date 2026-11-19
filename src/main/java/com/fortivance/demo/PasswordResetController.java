package com.fortivance.demo;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Public forgot-password endpoints used by forgot-password.html and reset-password.html. */
@RestController
@RequestMapping("/api/password")
public class PasswordResetController {

    static final String SENT_MESSAGE =
            "If an account exists for this email, we have sent a reset link. It is valid for 30 minutes.";

    public record ForgotRequest(
            @NotBlank @Size(max = 210) @Pattern(regexp = "^\\s*[^@\\s]+@[^@\\s]+\\.[^@\\s]+\\s*$") String email) { }

    public record ResetRequest(String token, String newPassword) { }

    private final PasswordResetService service;

    public PasswordResetController(PasswordResetService service) {
        this.service = service;
    }

    @PostMapping("/forgot")
    public ResponseEntity<Map<String, String>> forgot(@Valid @RequestBody ForgotRequest req, HttpServletRequest http) {
        if (!service.requestReset(req.email(), http.getRemoteAddr())) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("error", "Too many requests. Please try again later."));
        }
        return ResponseEntity.ok(Map.of("status", "sent", "message", SENT_MESSAGE));
    }

    @PostMapping("/reset")
    public ResponseEntity<Map<String, String>> reset(@RequestBody ResetRequest req, HttpServletRequest http) {
        PasswordResetService.ResetResult r = service.reset(req.token(), req.newPassword(), http.getRemoteAddr());
        return switch (r.status()) {
            case CHANGED -> ResponseEntity.ok(Map.of("status", "changed", "message", r.message()));
            case INVALID_TOKEN -> ResponseEntity.badRequest().body(Map.of("error", r.message(), "field", "token"));
            case WEAK_PASSWORD -> ResponseEntity.badRequest().body(Map.of("error", r.message(), "field", "newPassword"));
            case RATE_LIMITED -> ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of("error", r.message()));
        };
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> invalid() {
        return ResponseEntity.badRequest().body(Map.of("error", "Please enter a valid email address.", "field", "email"));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> unreadable() {
        return ResponseEntity.badRequest().body(Map.of("error", "Please check the form.", "field", "form"));
    }
}
