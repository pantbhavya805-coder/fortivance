package com.fortivance.admin;

import com.fortivance.security.UserPrincipal;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Admin console API. Access (ADMIN only, 404 for everyone else) and CSRF are enforced by the admin security
 * chain in SecurityConfig; this controller assumes a signed-in admin.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    public record ExtendRequest(Integer hours) { }

    private final AdminAccountService service;

    public AdminController(AdminAccountService service) {
        this.service = service;
    }

    @GetMapping("/me")
    public Map<String, Object> me(@AuthenticationPrincipal UserPrincipal admin) {
        return Map.of("username", admin.getUsername(), "displayName", admin.getDisplayName());
    }

    @GetMapping("/accounts")
    public ResponseEntity<Map<String, Object>> accounts(
            @RequestParam(name = "includeDeleted", defaultValue = "false") boolean includeDeleted) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.list(includeDeleted));
    }

    @PostMapping("/accounts/{userId}/disable")
    public ResponseEntity<?> disable(@PathVariable long userId, @AuthenticationPrincipal UserPrincipal admin) {
        return respond(service.disable(userId, admin.getUsername()));
    }

    @PostMapping("/accounts/{userId}/enable")
    public ResponseEntity<?> enable(@PathVariable long userId, @AuthenticationPrincipal UserPrincipal admin) {
        return respond(service.enable(userId, admin.getUsername()));
    }

    @PostMapping("/accounts/{userId}/extend")
    public ResponseEntity<?> extend(@PathVariable long userId, @RequestBody(required = false) ExtendRequest req,
                                    @AuthenticationPrincipal UserPrincipal admin) {
        return respond(service.extend(userId, req == null ? null : req.hours(), admin.getUsername()));
    }

    @DeleteMapping("/accounts/{userId}")
    public ResponseEntity<?> delete(@PathVariable long userId, @AuthenticationPrincipal UserPrincipal admin) {
        AdminAccountService.Result r = service.delete(userId, admin.getUsername());
        return r.status() == AdminAccountService.Status.OK
                ? ResponseEntity.ok(Map.of("status", "deleted"))
                : respond(r);
    }

    private static ResponseEntity<?> respond(AdminAccountService.Result r) {
        return switch (r.status()) {
            case OK -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(r.account());
            // Not 404: the console treats 404 as "signed out" (that is what the security chain answers).
            case NOT_FOUND, CONFLICT -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", r.error()));
            case BAD_REQUEST -> ResponseEntity.badRequest().body(Map.of("error", r.error()));
        };
    }
}
