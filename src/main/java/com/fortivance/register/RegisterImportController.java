package com.fortivance.register;

import com.fortivance.demo.DemoQuotaService;
import com.fortivance.security.UserPrincipal;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Vendor register import behind /import.html. Any signed-in user can check files (nothing is written);
 * importing needs the Approver role, the person accountable for the register.
 */
@RestController
@RequestMapping("/api/register-import")
public class RegisterImportController {

    private static final Set<String> CAN_IMPORT = Set.of("APPROVER", "ADMIN");

    private final RegisterImportService service;
    private final DemoQuotaService quota;

    public RegisterImportController(RegisterImportService service, DemoQuotaService quota) {
        this.service = service;
        this.quota = quota;
    }

    @GetMapping("/settings")
    public Map<String, Object> settings() {
        return Map.of("updatesAllowed", service.updatesAllowed(), "maxRows", RegisterImportService.MAX_ROWS);
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> run(@RequestParam(value = "vendors", required = false) MultipartFile vendors,
                                                   @RequestParam(value = "systems", required = false) MultipartFile systems,
                                                   @RequestParam(value = "services", required = false) MultipartFile services,
                                                   @RequestParam(value = "apply", defaultValue = "false") boolean apply,
                                                   Authentication authentication) throws IOException {
        if (!(authentication != null && authentication.getPrincipal() instanceof UserPrincipal user)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Unauthorized"));
        }
        if (apply && !CAN_IMPORT.contains(user.getRole())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error",
                    "Only an Approver can import the register. Switch to Approver in the header, then import."));
        }
        Map<String, byte[]> files = new HashMap<>();
        put(files, RegisterPlan.VENDORS, vendors);
        put(files, RegisterPlan.SYSTEMS, systems);
        put(files, RegisterPlan.SERVICES, services);
        boolean reserved = false;
        if (apply) {
            if (!quota.tryUse(user.getId(), DemoQuotaService.Kind.REGISTER_IMPORT)) {
                return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                        .body(Map.of("error", quota.limitMessage(DemoQuotaService.Kind.REGISTER_IMPORT)));
            }
            reserved = true;
        }
        Map<String, Object> result;
        try {
            result = service.run(files, apply, user.getUsername());
        } catch (RuntimeException e) {
            if (reserved) quota.release(user.getId(), DemoQuotaService.Kind.REGISTER_IMPORT);
            throw e;
        }
        // Only an import that actually saved something counts against the limit.
        if (reserved && !Boolean.TRUE.equals(result.get("applied"))) {
            quota.release(user.getId(), DemoQuotaService.Kind.REGISTER_IMPORT);
        }
        HttpStatus status = Boolean.TRUE.equals(result.get("ok")) ? HttpStatus.OK : HttpStatus.UNPROCESSABLE_ENTITY;
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(result);
    }

    private static void put(Map<String, byte[]> files, String name, MultipartFile f) throws IOException {
        if (f != null && !f.isEmpty()) {
            files.put(name, f.getSize() > RegisterImportService.MAX_BYTES + 1 ? new byte[RegisterImportService.MAX_BYTES + 1] : f.getBytes());
        }
    }
}
