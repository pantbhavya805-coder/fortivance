package com.fortivance.security;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AccountExpiredException;
import org.springframework.security.authentication.AuthenticationEventPublisher;
import org.springframework.security.authentication.DefaultAuthenticationEventPublisher;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servletapi.SecurityContextHolderAwareRequestFilter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationEventPublisher authenticationEventPublisher(ApplicationEventPublisher applicationEventPublisher) {
        return new DefaultAuthenticationEventPublisher(applicationEventPublisher);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JdbcTemplate jdbcTemplate) throws Exception {
        RequestMatcher approveOrRejectMatcher = request ->
                "POST".equalsIgnoreCase(request.getMethod()) &&
                (request.getRequestURI().endsWith("/approve") || request.getRequestURI().endsWith("/reject"));

        http
            .csrf(csrf -> csrf.ignoringRequestMatchers(new AntPathRequestMatcher("/api/**"), new AntPathRequestMatcher("/login"), new AntPathRequestMatcher("/logout")))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
                    "/",
                    "/about.html",
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
                // Public demo access request; rate-limited, one per email (DemoRequestService).
                .requestMatchers(HttpMethod.POST, "/api/demo-requests").permitAll()
                // Forgot-password flow; rate-limited, one-time hashed tokens (PasswordResetService).
                .requestMatchers(HttpMethod.POST, "/api/password/forgot", "/api/password/reset").permitAll()
                .requestMatchers(approveOrRejectMatcher).hasAnyRole("APPROVER", "ADMIN")
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
            .addFilterAfter(new DemoExpiryFilter(jdbcTemplate), SecurityContextHolderAwareRequestFilter.class)
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
}
