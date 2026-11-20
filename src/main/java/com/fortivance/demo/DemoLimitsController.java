package com.fortivance.demo;

import com.fortivance.security.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** What the signed-in demo account has left (incident uploads, register imports); unlimited accounts get nulls. */
@RestController
public class DemoLimitsController {

    private final DemoQuotaService quota;

    public DemoLimitsController(DemoQuotaService quota) {
        this.quota = quota;
    }

    @GetMapping("/api/me/limits")
    public Map<String, Object> limits() {
        return quota.status(CurrentUser.userId());
    }
}
