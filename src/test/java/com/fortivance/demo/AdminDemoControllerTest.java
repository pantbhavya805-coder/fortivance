package com.fortivance.demo;

import com.fortivance.security.AdminAccess;
import com.fortivance.security.UserPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class AdminDemoControllerTest {

    private static final Instant NOW = Instant.parse("2026-11-20T06:00:00Z");
    private final AdminAccess admins = new AdminAccess("bhavya, judge", "bhavya");

    private static Authentication auth(String username, Long demoRequestId, Instant expires) {
        UserPrincipal p = new UserPrincipal(1L, username, username, "x", "ANALYST", "t", true,
                List.of(new SimpleGrantedAuthority("ROLE_ANALYST")), expires, demoRequestId);
        return new UsernamePasswordAuthenticationToken(p, null, p.getAuthorities());
    }

    @Test
    void onlyNamedPermanentAccountsAreAdmins() {
        assertThat(admins.isAdmin(auth("bhavya", null, null))).isTrue();
        assertThat(admins.isAdmin(auth("JUDGE", null, null))).isTrue();
        assertThat(admins.isAdmin(auth("jane@acme.com", 3L, NOW))).isFalse();
        // a demo account can never be an admin, even with a matching name
        assertThat(admins.isAdmin(auth("judge", 3L, NOW))).isFalse();
        assertThat(admins.isAdmin(auth("analyst", null, null))).isFalse();
        assertThat(admins.isAdmin(new AnonymousAuthenticationToken("k", "anon",
                List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))))).isFalse();
        assertThat(admins.isAdmin((Authentication) null)).isFalse();
    }

    @Test
    void accessStates() {
        String ok = DemoRequestService.STATUS_PROVISIONED;
        assertThat(AdminDemoController.accessState(ok, true, NOW.plusSeconds(7 * 3600), NOW)).isEqualTo("ACTIVE");
        assertThat(AdminDemoController.accessState(ok, true, NOW.plusSeconds(5 * 3600), NOW)).isEqualTo("EXPIRING_SOON");
        assertThat(AdminDemoController.accessState(ok, true, NOW, NOW)).isEqualTo("EXPIRED");
        assertThat(AdminDemoController.accessState(ok, false, NOW.plusSeconds(7 * 3600), NOW)).isEqualTo("NOT_ACTIVATED");
        assertThat(AdminDemoController.accessState("MAIL_FAILED", false, NOW.plusSeconds(3600), NOW)).isEqualTo("NOT_ACTIVATED");
    }

    @Test
    void nonAdminGets403AndNoDataIsRead() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AdminDemoController c = new AdminDemoController(jdbc, admins, 2, Clock.fixed(NOW, ZoneOffset.UTC));
        assertThat(c.demoAccounts(auth("jane@acme.com", 3L, NOW)).getStatusCode().value()).isEqualTo(403);
        verifyNoInteractions(jdbc);
    }

    @Test
    @SuppressWarnings("unchecked")
    void adminSeesAccountsWithCountsAndNoPasswords() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        Map<String, Object> row = new HashMap<>();
        row.put("id", 1L);
        row.put("email", "jane@acme.com");
        row.put("username", "jane@acme.com");
        row.put("name", "Acme");
        row.put("phone", null);
        row.put("purpose", "Evaluating.");
        row.put("status", "PROVISIONED");
        row.put("created_at", Timestamp.from(NOW.minusSeconds(3600)));
        row.put("access_expires_at", Timestamp.from(NOW.plusSeconds(2 * 3600)));
        row.put("extension_count", 1);
        row.put("mail_sent_at", Timestamp.from(NOW.minusSeconds(3600)));
        row.put("role", "APPROVER");
        row.put("active", true);
        when(jdbc.queryForList(anyString())).thenReturn(List.of(row));

        AdminDemoController c = new AdminDemoController(jdbc, admins, 2, Clock.fixed(NOW, ZoneOffset.UTC));
        ResponseEntity<?> res = c.demoAccounts(auth("bhavya", null, null));

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> body = (Map<String, Object>) res.getBody();
        assertThat(body.get("total")).isEqualTo(1);
        assertThat((Map<String, Integer>) body.get("counts")).containsEntry("EXPIRING_SOON", 1).containsEntry("ACTIVE", 0);
        Map<String, Object> a = ((List<Map<String, Object>>) body.get("accounts")).get(0);
        assertThat(a).containsEntry("accessState", "EXPIRING_SOON").containsEntry("minutesLeft", 120L)
                .containsEntry("role", "APPROVER").doesNotContainKeys("password", "password_hash");
        assertThat(res.getHeaders().getCacheControl()).contains("no-store");
        // bhavya is the owner, so personal details are included
        assertThat(body.get("personalDetailsVisible")).isEqualTo(true);
        assertThat(a).containsEntry("purpose", "Evaluating.");
    }

    @Test
    @SuppressWarnings("unchecked")
    void judgeDoesNotReceivePhoneOrPurpose() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        Map<String, Object> row = new HashMap<>();
        row.put("id", 1L);
        row.put("email", "jane@acme.com");
        row.put("username", "jane@acme.com");
        row.put("name", "Acme");
        row.put("phone", "+91 98765 43210");
        row.put("purpose", "Private reason.");
        row.put("status", "PROVISIONED");
        row.put("created_at", Timestamp.from(NOW.minusSeconds(3600)));
        row.put("access_expires_at", Timestamp.from(NOW.plusSeconds(10 * 3600)));
        row.put("extension_count", 0);
        row.put("mail_sent_at", Timestamp.from(NOW.minusSeconds(3600)));
        row.put("role", "ANALYST");
        row.put("active", true);
        when(jdbc.queryForList(anyString())).thenReturn(List.of(row));

        AdminDemoController c = new AdminDemoController(jdbc, admins, 2, Clock.fixed(NOW, ZoneOffset.UTC));
        Map<String, Object> body = (Map<String, Object>) c.demoAccounts(auth("judge", null, null)).getBody();

        assertThat(body.get("personalDetailsVisible")).isEqualTo(false);
        Map<String, Object> a = ((List<Map<String, Object>>) body.get("accounts")).get(0);
        assertThat(a.get("phone")).isNull();
        assertThat(a.get("purpose")).isNull();
        assertThat(a).containsEntry("email", "jane@acme.com").containsEntry("accessState", "ACTIVE");
    }
}
