package com.fortivance.admin;

import com.fortivance.security.AccountRevocations;
import com.fortivance.security.AdminLoginThrottle;
import com.fortivance.security.CustomUserDetailsService;
import com.fortivance.security.SecurityConfig;
import com.fortivance.security.UserPrincipal;
import com.fortivance.security.WebConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Runs the real security chains (admin + application) with the database mocked out. */
@WebMvcTest(controllers = AdminController.class)
@Import({SecurityConfig.class, WebConfig.class, AdminLoginThrottle.class, AccountRevocations.class})
class AdminSecurityTest {

    @Autowired MockMvc mvc;
    @Autowired PasswordEncoder encoder;
    @MockBean CustomUserDetailsService users;
    @MockBean JdbcTemplate jdbc;
    @MockBean AdminAccountService service;

    private UserPrincipal admin;
    private UserPrincipal demoUser;

    @BeforeEach
    void setUp() {
        String hash = encoder.encode("Correct-Pass-2026");
        admin = new UserPrincipal(1L, "bhavya", "Bhavya Pant", hash, "ADMIN", "TPRM", true,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")), null, null);
        demoUser = new UserPrincipal(2L, "jane@acme.com", "Acme", hash, "ANALYST", "Acme", true,
                List.of(new SimpleGrantedAuthority("ROLE_ANALYST")), null, 5L);
        when(users.loadUserByUsername("bhavya")).thenReturn(admin);
        when(users.loadUserByUsername("jane@acme.com")).thenReturn(demoUser);
        when(users.loadUserByUsername(argThat(u -> !"bhavya".equals(u) && !"jane@acme.com".equals(u))))
                .thenThrow(new UsernameNotFoundException("none"));
        when(service.list(anyBoolean())).thenReturn(Map.of("accounts", List.of()));
    }

    // ------------------------------------------------------------------ hidden from everyone but admins

    @Test
    void adminAreaIs404WhenSignedOut() throws Exception {
        mvc.perform(get("/admin/")).andExpect(status().isNotFound());
        mvc.perform(get("/admin/index.html")).andExpect(status().isNotFound());
        mvc.perform(get("/api/admin/accounts")).andExpect(status().isNotFound())
                .andExpect(content().json("{\"error\":\"Not found\"}"));
    }

    @Test
    void adminAreaIs404ForNormalUsers() throws Exception {
        mvc.perform(get("/api/admin/accounts").with(user(demoUser))).andExpect(status().isNotFound());
        mvc.perform(get("/admin/").with(user(demoUser))).andExpect(status().isNotFound());
    }

    @Test
    void loginPageIsReachableByItsLinkAndNotIndexed() throws Exception {
        mvc.perform(get("/admin/login"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/admin/login.html"))
                .andExpect(header().string("X-Robots-Tag", "noindex, nofollow"));
    }

    // ------------------------------------------------------------------ who may sign in where

    @Test
    void adminSignsInOnlyAtTheAdminLogin() throws Exception {
        mvc.perform(post("/admin/login").param("username", "bhavya").param("password", "Correct-Pass-2026")
                        .with(r -> { r.setRemoteAddr("10.0.0.1"); return r; }))
                .andExpect(redirectedUrl("/admin/"));
        // the application login does not know admin accounts
        mvc.perform(post("/login").param("username", "bhavya").param("password", "Correct-Pass-2026"))
                .andExpect(redirectedUrl("/login.html?error"));
    }

    @Test
    void normalUsersCannotSignInAtTheAdminLogin() throws Exception {
        mvc.perform(post("/admin/login").param("username", "jane@acme.com").param("password", "Correct-Pass-2026")
                        .with(r -> { r.setRemoteAddr("10.0.0.2"); return r; }))
                .andExpect(redirectedUrl("/admin/login?error"));
        mvc.perform(post("/login").param("username", "jane@acme.com").param("password", "Correct-Pass-2026"))
                .andExpect(redirectedUrl("/index.html"));
    }

    @Test
    void fiveFailuresLockTheAdminLoginEvenForTheRightPassword() throws Exception {
        for (int i = 0; i < 4; i++) {
            mvc.perform(post("/admin/login").param("username", "bhavya").param("password", "wrong")
                            .with(r -> { r.setRemoteAddr("10.0.0.3"); return r; }))
                    .andExpect(redirectedUrl("/admin/login?error"));
        }
        mvc.perform(post("/admin/login").param("username", "bhavya").param("password", "wrong")
                        .with(r -> { r.setRemoteAddr("10.0.0.3"); return r; }))
                .andExpect(redirectedUrl("/admin/login?error=locked"));
        mvc.perform(post("/admin/login").param("username", "bhavya").param("password", "Correct-Pass-2026")
                        .with(r -> { r.setRemoteAddr("10.0.0.3"); return r; }))
                .andExpect(redirectedUrl("/admin/login?error=locked"));
        // another network is not affected
        mvc.perform(post("/admin/login").param("username", "bhavya").param("password", "Correct-Pass-2026")
                        .with(r -> { r.setRemoteAddr("10.0.0.4"); return r; }))
                .andExpect(redirectedUrl("/admin/"));
    }

    // ------------------------------------------------------------------ admin API

    @Test
    void adminCanListAccounts() throws Exception {
        mvc.perform(get("/api/admin/accounts").with(user(admin))).andExpect(status().isOk());
        mvc.perform(get("/api/admin/me").with(user(admin)))
                .andExpect(status().isOk()).andExpect(content().json("{\"username\":\"bhavya\"}"));
    }

    @Test
    void adminActionsNeedTheCsrfToken() throws Exception {
        when(service.disable(eq(7L), anyString()))
                .thenReturn(new AdminAccountService.Result(AdminAccountService.Status.OK, null, Map.of("userId", 7)));
        mvc.perform(post("/api/admin/accounts/7/disable").with(user(admin))).andExpect(status().isNotFound());
        mvc.perform(post("/api/admin/accounts/7/disable").with(user(admin)).with(csrf()))
                .andExpect(status().isOk());
    }

    @Test
    void adminDeleteWithCsrf() throws Exception {
        when(service.delete(eq(7L), anyString()))
                .thenReturn(new AdminAccountService.Result(AdminAccountService.Status.OK, null, null));
        mvc.perform(delete("/api/admin/accounts/7").with(user(admin)).with(csrf()))
                .andExpect(status().isOk()).andExpect(content().json("{\"status\":\"deleted\"}"));
    }
}
