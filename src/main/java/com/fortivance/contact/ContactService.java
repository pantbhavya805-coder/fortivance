package com.fortivance.contact;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sends contact form messages to the domain mailbox over SMTP.
 * The mailbox is both sender (SMTP login) and recipient; the visitor's address goes in Reply-To,
 * so replying from the mailbox reaches the visitor.
 */
@Service
public class ContactService {

    public enum Result { SENT, IGNORED, RATE_LIMITED, NOT_CONFIGURED, FAILED }

    private static final Logger log = LoggerFactory.getLogger(ContactService.class);
    private static final Duration WINDOW = Duration.ofHours(1);

    private final JavaMailSender mailSender;
    private final String from;
    private final String to;
    private final int maxPerHour;
    private final Clock clock;
    private final Map<String, Deque<Instant>> recentByClient = new ConcurrentHashMap<>();

    @Autowired
    public ContactService(JavaMailSender mailSender,
                          @Value("${spring.mail.username:}") String from,
                          @Value("${fortivance.contact.to:}") String to,
                          @Value("${fortivance.contact.max-per-hour:5}") int maxPerHour) {
        this(mailSender, from, to, maxPerHour, Clock.systemUTC());
    }

    ContactService(JavaMailSender mailSender, String from, String to, int maxPerHour, Clock clock) {
        this.mailSender = mailSender;
        this.from = from;
        this.to = to;
        this.maxPerHour = maxPerHour;
        this.clock = clock;
    }

    public Result submit(ContactRequest req, String clientKey) {
        if (req.website() != null && !req.website().isBlank()) {
            log.info("Contact form honeypot triggered; message dropped");
            return Result.IGNORED; // report success to the bot so it does not retry
        }
        if (from.isBlank() || to.isBlank()) {
            log.warn("Contact form used but MAIL_USERNAME / CONTACT_TO are not set");
            return Result.NOT_CONFIGURED;
        }
        if (!allow(clientKey)) {
            return Result.RATE_LIMITED;
        }

        SimpleMailMessage mail = new SimpleMailMessage();
        mail.setFrom(from);
        mail.setTo(to);
        mail.setReplyTo(singleLine(req.email()));
        mail.setSubject("[Fortivance contact] " + singleLine(req.name()));
        mail.setText("Name: " + singleLine(req.name()) + "\n"
                + "Email: " + singleLine(req.email()) + "\n\n"
                + req.message() + "\n\n"
                + "-- Sent from the contact form on the Fortivance site");
        try {
            mailSender.send(mail);
            return Result.SENT;
        } catch (MailException e) {
            log.error("Contact form email could not be sent: {}", e.getMessage());
            return Result.FAILED;
        }
    }

    /** Sliding one-hour window per client; also prunes entries so the map does not grow without bound. */
    private boolean allow(String clientKey) {
        Instant now = clock.instant();
        Instant cutoff = now.minus(WINDOW);
        recentByClient.values().removeIf(q -> {
            synchronized (q) {
                return q.isEmpty() || q.peekLast().isBefore(cutoff);
            }
        });
        Deque<Instant> q = recentByClient.computeIfAbsent(clientKey, k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && q.peekFirst().isBefore(cutoff)) {
                q.pollFirst();
            }
            if (q.size() >= maxPerHour) {
                return false;
            }
            q.addLast(now);
            return true;
        }
    }

    /** Strips CR/LF so user input cannot add mail headers. */
    private static String singleLine(String s) {
        return s == null ? "" : s.replaceAll("[\\r\\n]+", " ").trim();
    }
}
