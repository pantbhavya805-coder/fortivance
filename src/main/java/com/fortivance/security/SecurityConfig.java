package com.fortivance.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AccountExpiredException;
import org.springframework.security.authentication.AuthenticationEventPublisher;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.DefaultAuthenticationEventPublisher;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import org.springframework.security.web.servletapi.SecurityContextHolderAwareRequestFilter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Two separate security chains:
 * <ol>
 *   <li><b>Admin</b> ({@code /admin/**}, {@code /api/admin/**}): only ADMIN accounts, signed in through the
 *       unlinked page {@code /admin/login}. Everyone else gets a plain 404, so the area does not reveal itself.
 *       Sign-in is locked for 15 minutes after 5 failures; admin actions require a CSRF token
 *       (cookie {@code XSRF-TOKEN}, header {@code X-XSRF-TOKEN}); responses carry {@code X-Robots-Tag: noindex}.</li>
 *   <li><b>Application</b> (everything else): demo, test and other user accounts. ADMIN accounts cannot sign in
 *       here.</li>
 * </ol>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    static final String ADMIN_ROLE = "ADMIN";

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationEventPublisher authenticationEventPublisher(ApplicationEventPublisher applicationEventPublisher) {
        return new DefaultAuthenticationEventPublisher(applicationEventPublisher);
    }

    // ------------------------------------------------------------------ admin chain

    @Bean
    @Order(1)
    public SecurityFilterChain adminSecurityFilterChain(HttpSecurity http, CustomUserDetailsService users,
                                                        PasswordEncoder encoder, AuthenticationEventPublisher events,
                                                        AdminLoginThrottle throttle) throws Exception {
        http
            .securityMatcher("/admin", "/admin/**", "/api/admin/**")
            .authenticationManager(manager(onlyAdmins(users), encoder, events))
            .csrf(csrf -> csrf
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                // plain (non-masked) token, so the page can send the cookie value back in the header
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                // the sign-in form itself is protected by the credentials and the lockout
                .ignoringRequestMatchers(new AntPathRequestMatcher("/admin/login", "POST")))
            .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class)
            .addFilterBefore(new AdminLoginLockFilter(throttle), UsernamePasswordAuthenticationFilter.class)
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/admin/login", "/admin/login.html", "/admin/admin.css").permitAll()
                .anyRequest().hasRole(ADMIN_ROLE))
            .formLogin(form -> form
                .loginPage("/admin/login")
                .loginProcessingUrl("/admin/login")
                .successHandler((request, response, authentication) -> {
                    throttle.recordSuccess(request.getRemoteAddr());
                    response.sendRedirect("/admin/");
                })
                .failureHandler((request, response, ex) -> {
                    String client = request.getRemoteAddr();
                    throttle.recordFailure(client);
                    response.sendRedirect(throttle.isLocked(client) ? "/admin/login?error=locked" : "/admin/login?error");
                })
                .permitAll())
            .logout(logout -> logout
                .logoutRequestMatcher(new AntPathRequestMatcher("/admin/logout", "GET"))
                .logoutSuccessUrl("/admin/login?logout")
                .permitAll())
            // Not signed in as an admin: answer exactly like a page that does not exist.
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((request, response, e) -> notFound(request, response))
                .accessDeniedHandler((request, response, e) -> notFound(request, response)))
            .headers(headers -> headers.addHeaderWriter(new StaticHeadersWriter("X-Robots-Tag", "noindex, nofollow")));
        return http.build();
    }

    // ------------------------------------------------------------------ application chain

    @Bean
    @Order(2)
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JdbcTemplate jdbcTemplate,
                                                   CustomUserDetailsService users, PasswordEncoder encoder,
                                                   AuthenticationEventPublisher events,
                                                   AccountRevocations revocations) throws Exception {
        RequestMatcher approveOrRejectMatcher = request ->
                "POST".equalsIgnoreCase(request.getMethod()) &&
                (request.getRequestURI().endsWith("/approve") || request.getRequestURI().endsWith("/reject"));

        http
            .authenticationManager(manager(excludingAdmins(users), encoder, events))
            .csrf(csrf -> csrf.ignoringRequestMatchers(new AntPathRequestMatcher("/api/**"), new AntPathRequestMatcher("/login"), new AntPathRequestMatcher("/logout")))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
                    "/",
                    "/about.html",
                    "/guide.html",
                    "/judges.html",
                    "/login.html",
                    "/request-demo.html",
                    "/forgot-password.html",
                    "/reset-password.html",
                    "/login",
                    "/logout",
                    "/css/**",
                    "/js/**",
                    "/vendor/**",
                    "/images/**",
                    "/favicon.svg",
                    "/actuator/health",
                    "/error",
                    "/favicon.ico"
                ).permitAll()
                // Public landing page (about.html) shows these aggregate counts only.
                .requestMatchers(HttpMethod.GET, "/api/metrics").permitAll()
                // Judges' test account sign-in details for /judges.html (password only if JUDGE_PASSWORD_PUBLIC=true).
                .requestMatchers(HttpMethod.GET, "/api/judge-access").permitAll()
                // Public demo access request; rate-limited, one per email (DemoRequestService).
                .requestMatchers(HttpMethod.POST, "/api/demo-requests").permitAll()
                // Public contact form; honeypot, rate limits and a content-free auto-reply (ContactService).
                .requestMatchers(HttpMethod.POST, "/api/contact").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/contact/topics").permitAll()
                // Forgot-password flow; rate-limited, one-time hashed tokens (PasswordResetService).
                .requestMatchers(HttpMethod.POST, "/api/password/forgot", "/api/password/reset").permitAll()
                .requestMatchers(approveOrRejectMatcher).hasAnyRole("APPROVER", ADMIN_ROLE)
                .anyRequest().authenticated()
            )
            .formLogin(form -> form
                .loginPage("/login.html")
                .loginProcessingUrl("/login")
                .defaultSuccessUrl("/index.html", true)
                .failureHandler((request, response, ex) -> response.sendRedirect(
                        ex instanceof AccountExpiredException ? "/login.html?error=expired" : "/login.html?error"))
                .permitAll()
            )
            .logout(logout -> logout
                .logoutUrl("/logout")
                .logoutRequestMatcher(new AntPathRequestMatcher("/logout", "GET"))
                .logoutSuccessUrl("/login.html?logout")
                .permitAll()
            )
            .httpBasic(Customizer.withDefaults())
            .addFilterAfter(new DemoExpiryFilter(jdbcTemplate, revocations), SecurityContextHolderAwareRequestFilter.class)
            .exceptionHandling(ex -> ex
                .defaultAuthenticationEntryPointFor(
                    (request, response, authException) -> {
                        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                        response.setContentType("application/json");
                        response.setCharacterEncoding("UTF-8");
                        response.getWriter().write("{\"error\":\"Unauthorized\",\"message\":\"Authentication required\"}");
                    },
                    new AntPathRequestMatcher("/api/**")
                )
            );

        return http.build();
    }

    // ------------------------------------------------------------------ helpers

    private static AuthenticationManager manager(UserDetailsService uds, PasswordEncoder encoder,
                                                 AuthenticationEventPublisher events) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(uds);
        provider.setPasswordEncoder(encoder);
        ProviderManager manager = new ProviderManager(provider);
        manager.setAuthenticationEventPublisher(events);
        return manager;
    }

    /** Admin sign-in: only ADMIN accounts exist. (Unknown users are reported as bad credentials.) */
    static UserDetailsService onlyAdmins(CustomUserDetailsService users) {
        return username -> {
            UserDetails u = users.loadUserByUsername(username);
            if (!(u instanceof UserPrincipal p) || !ADMIN_ROLE.equals(p.getRole())) {
                throw new UsernameNotFoundException("Not an admin account");
            }
            return u;
        };
    }

    /** Application sign-in: ADMIN accounts do not exist here. */
    static UserDetailsService excludingAdmins(CustomUserDetailsService users) {
        return username -> {
            UserDetails u = users.loadUserByUsername(username);
            if (u instanceof UserPrincipal p && ADMIN_ROLE.equals(p.getRole())) {
                throw new UsernameNotFoundException("Admin accounts sign in at the admin console");
            }
            return u;
        };
    }

    private static void notFound(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        if (request.getRequestURI().startsWith("/api/")) {
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write("{\"error\":\"Not found\"}");
        } else {
            response.setContentType("text/plain");
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write("Not Found");
        }
    }

    /** Makes sure the XSRF-TOKEN cookie is written on every admin response (the token is loaded lazily). */
    static final class CsrfCookieFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
            if (token != null) {
                token.getToken();
            }
            chain.doFilter(request, response);
        }
    }

    /** Rejects admin sign-in attempts from a locked-out client before the password is even checked. */
    static final class AdminLoginLockFilter extends OncePerRequestFilter {
        // A matcher, not getServletPath(): the servlet path depends on how the app is deployed.
        private static final RequestMatcher LOGIN_POST = new AntPathRequestMatcher("/admin/login", "POST");
        private final AdminLoginThrottle throttle;

        AdminLoginLockFilter(AdminLoginThrottle throttle) {
            this.throttle = throttle;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            if (LOGIN_POST.matches(request) && throttle.isLocked(request.getRemoteAddr())) {
                response.sendRedirect("/admin/login?error=locked");
                return;
            }
            chain.doFilter(request, response);
        }
    }
}
