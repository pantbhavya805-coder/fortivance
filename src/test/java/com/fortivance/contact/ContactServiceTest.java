package com.fortivance.contact;

import com.fortivance.audit.AuditService;
import com.fortivance.demo.DemoMailer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailSendException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static com.fortivance.contact.ContactService.Outcome.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ContactServiceTest {

    private DemoMailer mailer;
    private ContactService service;

    @BeforeEach
    void setUp() {
        mailer = mock(DemoMailer.class);
        when(mailer.isConfigured()).thenReturn(true);
        service = new ContactService(mailer, mock(AuditService.class),
                Clock.fixed(Instant.parse("2026-11-20T06:00:00Z"), ZoneOffset.UTC));
    }

    private static ContactRequest req(String email, String topic, String website) {
        return new ContactRequest("Asha", email, topic, "We would like to discuss a pilot.", website);
    }

    @Test
    void forwardsToMailboxAndAcknowledgesWithTopicLabelOnly() throws Exception {
        assertThat(service.submit(req("Asha@Acme.com", "pilot", null), "1.1.1.1")).isEqualTo(SENT);
        verify(mailer).forwardContactMessage("Asha", "Asha@Acme.com", "Pilot or partnership",
                "We would like to discuss a pilot.");
        // the acknowledgement carries only the fixed topic label, never visitor text
        verify(mailer).sendContactAcknowledgement("asha@acme.com", "Pilot or partnership");
    }

    @Test
    void atMostOneAcknowledgementPerAddressPerDay() throws Exception {
        service.submit(req("asha@acme.com", "GENERAL", null), "1.1.1.1");
        service.submit(req("ASHA@acme.com", "GENERAL", null), "2.2.2.2");
        verify(mailer, times(2)).forwardContactMessage(any(), any(), any(), any());
        verify(mailer, times(1)).sendContactAcknowledgement(any(), any());
    }

    @Test
    void unknownTopicIsRejected() throws Exception {
        assertThat(service.submit(req("a@b.co", "<script>", null), "1.1.1.1")).isEqualTo(INVALID_TOPIC);
        verify(mailer, never()).forwardContactMessage(any(), any(), any(), any());
    }

    @Test
    void honeypotIsDroppedSilently() {
        assertThat(service.submit(req("a@b.co", "GENERAL", "http://spam"), "1.1.1.1")).isEqualTo(IGNORED);
        verify(mailer, never()).forwardContactMessage(any(), any(), any(), any());
    }

    @Test
    void rateLimitedPerIp() {
        for (int i = 0; i < 5; i++) {
            assertThat(service.submit(req("u" + i + "@b.co", "GENERAL", null), "1.1.1.1")).isEqualTo(SENT);
        }
        assertThat(service.submit(req("u9@b.co", "GENERAL", null), "1.1.1.1")).isEqualTo(RATE_LIMITED);
    }

    @Test
    void forwardFailureIsReportedAndNoAcknowledgementIsSent() throws Exception {
        doThrow(new MailSendException("auth failed")).when(mailer).forwardContactMessage(any(), any(), any(), any());
        assertThat(service.submit(req("a@b.co", "GENERAL", null), "1.1.1.1")).isEqualTo(FAILED);
        verify(mailer, never()).sendContactAcknowledgement(any(), any());
    }

    @Test
    void acknowledgementFailureDoesNotFailTheRequest() throws Exception {
        doThrow(new jakarta.mail.MessagingException("down")).when(mailer).sendContactAcknowledgement(any(), any());
        assertThat(service.submit(req("a@b.co", "GENERAL", null), "1.1.1.1")).isEqualTo(SENT);
    }

    @Test
    void notConfiguredWithoutMail() {
        when(mailer.isConfigured()).thenReturn(false);
        assertThat(service.submit(req("a@b.co", "GENERAL", null), "1.1.1.1")).isEqualTo(NOT_CONFIGURED);
    }

    @Test
    void topicsMatchTheFormOptions() {
        // keys must match the <option value> attributes in about.html
        assertThat(ContactService.TOPICS).containsOnlyKeys("GENERAL", "PILOT", "TECHNICAL", "FEEDBACK", "OTHER");
    }
}
