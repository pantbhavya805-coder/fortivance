package com.fortivance.demo;

import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSender;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Writes target/email-preview.html (git-ignored) so the welcome email can be checked in a browser. */
class EmailPreviewTest {

    @Test
    void writesWelcomeEmailPreview() throws Exception {
        Path logo = Path.of("src/main/resources/static/images/email-logo.png").toAbsolutePath();
        boolean hasLogo = Files.exists(logo);
        DemoMailer mailer = new DemoMailer(mock(JavaMailSender.class), "admin@fortivance.in", "admin@fortivance.in",
                "https://fortivance.in");
        String html = mailer.welcomeHtml("Acme Bank", "jane@acmebank.com", "Kp7mQx2RtW9aZe",
                Instant.parse("2026-11-21T12:35:00Z"), hasLogo);

        assertThat(html).contains("jane@acmebank.com").contains("Sign in to Fortivance");
        if (hasLogo) {
            assertThat(html).contains("cid:fortivance-logo");
            html = html.replace("cid:fortivance-logo", logo.toUri().toString());
        }
        Path out = Path.of("target", "email-preview.html");
        Files.createDirectories(out.getParent());
        Files.writeString(out, html, StandardCharsets.UTF_8);
    }
}
