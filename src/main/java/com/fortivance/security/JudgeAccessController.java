package com.fortivance.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Sign-in details of the shared judges' test account, shown on /judges.html.
 *
 * The password is never in the repository: it comes from JUDGE_PASSWORD in the server's .env and is
 * returned only when JUDGE_PASSWORD_PUBLIC=true (off by default). The account itself is a demo account on
 * synthetic data; AI spend is limited by the daily cap.
 */
@RestController
public class JudgeAccessController {

    private final String password;
    private final boolean showPassword;

    public JudgeAccessController(@Value("${fortivance.accounts.judge-password:}") String password,
                                 @Value("${fortivance.accounts.judge-password-public:false}") boolean showPassword) {
        this.password = password;
        this.showPassword = showPassword;
    }

    @GetMapping("/api/judge-access")
    public ResponseEntity<Map<String, Object>> access() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", AccountBootstrap.JUDGE.username());
        body.put("password", showPassword && password != null && !password.isBlank() ? password : null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
