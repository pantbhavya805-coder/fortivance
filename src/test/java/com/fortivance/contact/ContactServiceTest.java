package com.fortivance.contact;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ContactServiceTest {

    private static final String BOX = "admin@fortivance.in";

    private JavaMailSender sender;
    private MutableClock clock;
    private ContactService service;

    @BeforeEach
    void setUp() {
        sender = mock(JavaMailSender.class);
        clock = new MutableClock(Instant.parse("2026-11-19T10:00:00Z"));
        service = new ContactService(sender, BOX, BOX, 2, clock);
    }

    private static ContactRequest req(String name, String website) {
        return new ContactRequest(name, "visitor@example.com", "Hello, I would like a demo.", website);
    }

    @Test
    void sendsToMailboxWithVisitorAsReplyTo() {
        assertThat(service.submit(req("Asha", null), "1.1.1.1")).isEqualTo(ContactService.Result.SENT);

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender).send(captor.capture());
        SimpleMailMessage mail = captor.getValue();
        assertThat(mail.getFrom()).isEqualTo(BOX);
        assertThat(mail.getTo()).containsExactly(BOX);
        assertThat(mail.getReplyTo()).isEqualTo("visitor@example.com");
        assertThat(mail.getText()).contains("Hello, I would like a demo.");
    }

    @Test
    void stripsNewlinesFromSubject() {
        service.submit(req("Asha\r\nBcc: victim@example.com", null), "1.1.1.1");

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender).send(captor.capture());
        assertThat(captor.getValue().getSubject()).doesNotContain("\r").doesNotContain("\n");
    }

    @Test
    void honeypotIsDroppedSilently() {
        assertThat(service.submit(req("Bot", "http://spam.example"), "1.1.1.1"))
                .isEqualTo(ContactService.Result.IGNORED);
        verifyNoInteractions(sender);
    }

    @Test
    void rateLimitsPerClientAndResetsAfterAnHour() {
        assertThat(service.submit(req("A", null), "1.1.1.1")).isEqualTo(ContactService.Result.SENT);
        assertThat(service.submit(req("A", null), "1.1.1.1")).isEqualTo(ContactService.Result.SENT);
        assertThat(service.submit(req("A", null), "1.1.1.1")).isEqualTo(ContactService.Result.RATE_LIMITED);
        assertThat(service.submit(req("B", null), "2.2.2.2")).isEqualTo(ContactService.Result.SENT);

        clock.advance(Duration.ofMinutes(61));
        assertThat(service.submit(req("A", null), "1.1.1.1")).isEqualTo(ContactService.Result.SENT);
    }

    @Test
    void notConfiguredWithoutMailbox() {
        ContactService unconfigured = new ContactService(sender, "", "", 5, clock);
        assertThat(unconfigured.submit(req("A", null), "1.1.1.1"))
                .isEqualTo(ContactService.Result.NOT_CONFIGURED);
        verifyNoInteractions(sender);
    }

    @Test
    void smtpFailureIsReported() {
        doThrow(new MailSendException("auth failed")).when(sender).send(any(SimpleMailMessage.class));
        assertThat(service.submit(req("A", null), "1.1.1.1")).isEqualTo(ContactService.Result.FAILED);
    }

    private static final class MutableClock extends Clock {
        private Instant now;
        MutableClock(Instant start) { this.now = start; }
        void advance(Duration d) { now = now.plus(d); }
        @Override public Instant instant() { return now; }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
    }
}
