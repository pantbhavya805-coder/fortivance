package com.fortivance.security;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * The public About page is the landing page; the dashboard stays at /index.html after login.
 * The admin console lives at the unlinked addresses /admin/login and /admin/ (see SecurityConfig).
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/").setViewName("forward:/about.html");
        // POST /login is the sign-in form target; a GET (someone typing the address) goes to the page.
        registry.addRedirectViewController("/login", "/login.html");
        registry.addViewController("/admin/login").setViewName("forward:/admin/login.html");
        registry.addViewController("/admin/").setViewName("forward:/admin/index.html");
        registry.addRedirectViewController("/admin", "/admin/");
    }
}
