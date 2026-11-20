package com.fortivance.contact;

import com.fortivance.audit.AuditService;
import com.fortivance.demo.DemoMailer;
import com.fortivance.demo.SlidingWindowRateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Contact form: forwards the message to the Fortivance mailbox (Reply-To = visitor) and sends the visitor an
 * automatic "we'll reply shortly" acknowledgement.
 *
 * Abuse controls: honeypot; 5 messages per IP per hour; at most one acknowledgement per email address per
 * day; the acknowledgement contains no visitor-supplied text, so the form cannot relay content to strangers.
 */
@Service
public class ContactService {

    public enum Outcome { SENT, IGNORED, INVALID_TOPIC, RATE_LIMITED, NOT_CONFIGURED, FAILED }

    /** Allowed topics: key -> label. The label is the only visitor-chosen value that goes into the auto-reply. */
    public static final Map<String, String> TOPICS;

    static {
        Map<String, String> t = new LinkedHashMap<>();
        t.put("GENERAL", "General question");
        t.put("PILOT", "Pilot or partnership");
        t.put("TECHNICAL", "Technical issue");
        t.put("FEEDBACK", "Feedback on the demo");
        t.put("OTHER", "Other");
        TOPICS = java.util.Collections.unmodifiableMap(t);
    }

    private static final Logger log = LoggerFactory.getLogger(ContactService.class);

    private final DemoMailer mailer;
    private final AuditService audit;
    private final SlidingWindowRateLimiter perIp;
    private final SlidingWindowRateLimiter acksPerEmail;

    @Autowired
    public ContactService(DemoMailer mailer, AuditService audit) {
        this(mailer, audit, Clock.systemUTC());
    }

    ContactService(DemoMailer mailer, AuditService audit, Clock clock) {
        this.mailer = mailer;
        this.audit = audit;
        this.perIp = new SlidingWindowRateLimiter(5, Duration.ofHours(1), clock);
        this.acksPerEmail = new SlidingWindowRateLimiter(1, Duration.ofHours(24), clock);
    }

    public Outcome submit(ContactRequest req, String clientIp) {
        if (req.website() != null && !req.website().isBlank()) {
            log.info("Contact form honeypot triggered; message dropped");
            return Outcome.IGNORED; // report success so the bot does not retry
        }
        String topicLabel = TOPICS.get(req.topic().toUpperCase(Locale.ROOT));
        if (topicLabel == null) {
            return Outcome.INVALID_TOPIC;
        }
        if (!mailer.isConfigured()) {
            log.warn("Contact form used but mail is not configured (MAIL_USERNAME)");
            return Outcome.NOT_CONFIGURED;
        }
        if (!perIp.allow(clientIp)) {
            return Outcome.RATE_LIMITED;
        }

        try {
            mailer.forwardContactMessage(req.name(), req.email(), topicLabel, req.message());
        } catch (Exception e) {
            log.error("Contact message could not be forwarded: {}", e.getMessage());
            return Outcome.FAILED;
        }
        audit.log("contact-form", "CONTACT_MESSAGE_RECEIVED", "CONTACT", null,
                Map.of("email", req.email(), "topic", topicLabel));

        // The message reached the mailbox; the acknowledgement is best effort and limited per address.
        String email = req.email().toLowerCase(Locale.ROOT);
        if (acksPerEmail.allow(email)) {
            try {
                mailer.sendContactAcknowledgement(email, topicLabel);
            } catch (Exception e) {
                log.warn("Contact acknowledgement could not be sent: {}", e.getMessage());
            }
        }
        return Outcome.SENT;
    }
}
