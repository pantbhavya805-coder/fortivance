package com.fortivance.demo;

import com.fortivance.audit.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** The checks that run before any database work. */
class DemoRequestServiceTest {

    private static final String PURPOSE = "I lead third-party risk at a bank and want to evaluate incident triage.";

    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private DemoMailer mailer;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        tx = mock(TransactionTemplate.class);
        mailer = mock(DemoMailer.class);
        when(mailer.isConfigured()).thenReturn(true);
    }

    private DemoRequestService service(int perHour) {
        return new DemoRequestService(jdbc, tx, mock(PasswordEncoder.class), mailer, mock(AuditService.class),
                Duration.ofHours(24), perHour, Clock.fixed(Instant.parse("2026-11-19T10:00:00Z"), ZoneOffset.UTC));
    }

    private static DemoRequestForm form(String website) {
        return new DemoRequestForm("jane@acme.com", "Acme", null, PURPOSE, website);
    }

    @Test
    void honeypotIsDroppedWithoutTouchingTheDatabase() {
        assertThat(service(3).submit(form("http://spam"), "1.1.1.1").outcome()).isEqualTo(DemoRequestService.Outcome.IGNORED);
        verifyNoInteractions(jdbc, tx);
    }

    @Test
    void notConfiguredWithoutMail() {
        when(mailer.isConfigured()).thenReturn(false);
        assertThat(service(3).submit(form(null), "1.1.1.1").outcome()).isEqualTo(DemoRequestService.Outcome.NOT_CONFIGURED);
        verifyNoInteractions(jdbc, tx);
    }

    @Test
    void rateLimitedPerIp() {
        DemoRequestService s = service(1);
        s.submit(form(null), "1.1.1.1");          // tx mock returns null -> treated as duplicate
        assertThat(s.submit(form(null), "1.1.1.1").outcome()).isEqualTo(DemoRequestService.Outcome.RATE_LIMITED);
        assertThat(s.submit(form(null), "2.2.2.2").outcome()).isNotEqualTo(DemoRequestService.Outcome.RATE_LIMITED);
    }

    @Test
    void existingRequestIsReportedAsDuplicateAndNoMailIsSent() throws Exception {
        // provision() returns null for an existing, non-retryable request
        assertThat(service(3).submit(form(null), "1.1.1.1").outcome()).isEqualTo(DemoRequestService.Outcome.DUPLICATE);
        verify(mailer, never()).sendWelcome(any(), any(), any(), any(), any());
    }

    @Test
    void welcomeEmailEscapesUserInput() {
        DemoMailer real = new DemoMailer(mock(JavaMailSender.class), "admin@fortivance.in", "admin@fortivance.in",
                "https://fortivance.in");
        String html = real.welcomeHtml("<script>alert(1)</script>", "jane@acme.com", "Pass2345abcdEF",
                Instant.parse("2026-11-20T10:00:00Z"), false);
        assertThat(html).doesNotContain("<script>").contains("&lt;script&gt;")
                .contains("jane@acme.com").contains("Pass2345abcdEF").contains("Approver")
                .contains("20 Nov 2026, 3:30 PM IST")
                .contains("https://fortivance.in/profile.html");
    }
}
