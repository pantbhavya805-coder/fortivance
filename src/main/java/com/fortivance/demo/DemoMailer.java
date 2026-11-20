package com.fortivance.demo;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.io.UnsupportedEncodingException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Emails for demo access: welcome (credentials), password reset, and a notice to the Fortivance mailbox. */
@Component
public class DemoMailer {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a 'IST'", Locale.ENGLISH).withZone(IST);
    private static final String LOGO_PATH = "static/images/email-logo.png";

    private final JavaMailSender mailSender;
    private final String from;
    private final String ownerTo;
    private final String publicUrl;

    public DemoMailer(JavaMailSender mailSender,
                      @Value("${spring.mail.username:}") String from,
                      @Value("${fortivance.contact.to:}") String ownerTo,
                      @Value("${fortivance.public-url:https://fortivance.in}") String publicUrl) {
        this.mailSender = mailSender;
        this.from = from;
        this.ownerTo = ownerTo;
        this.publicUrl = publicUrl.endsWith("/") ? publicUrl.substring(0, publicUrl.length() - 1) : publicUrl;
    }

    public boolean isConfigured() {
        return !from.isBlank();
    }

    public String publicUrl() {
        return publicUrl;
    }

    static String formatIst(Instant t) {
        return WHEN.format(t);
    }

    // ------------------------------------------------------------------ welcome

    /** Sends the credentials. Throws if the message cannot be handed to the SMTP server. */
    public void sendWelcome(String to, String name, String username, Instant expiresAt)
            throws MessagingException {
        send(to, "Your Fortivance demo access",
                welcomeText(name, username, expiresAt),
                hasLogo -> welcomeHtml(name, username, expiresAt, hasLogo));
    }

    String welcomeText(String name, String username, Instant expiresAt) {
        return "Hello " + oneLine(name) + ",\n\n"
                + "Thank you for your interest in Fortivance. Your demo access is ready.\n\n"
                + "Sign in at: " + publicUrl + "/login.html\n"
                + "  Email:    " + username + "\n"
                + "  Password: the one you chose when you requested access\n"
                + "Forgot it? Use \"Forgot password?\" on the sign-in page.\n\n"
                + "You start as an Analyst (triage incidents, review evidence). Switch to Approver from the\n"
                + "header at any time to approve or reject response plans.\n\n"
                + "Access is valid until " + formatIst(expiresAt) + " (24 hours).\n"
                + "Need more time? Before it expires, open your profile (" + publicUrl + "/profile.html)\n"
                + "and request an extension: 24 hours each, up to 2 extensions. You can also change your\n"
                + "password there.\n\n"
                + "The demo runs on synthetic data shared by all demo users. Please do not enter real\n"
                + "customer or bank information. Keep your password private.\n\n"
                + "Regards,\nThe Fortivance team\n" + publicUrl + "\n";
    }

    String welcomeHtml(String name, String username, Instant expiresAt, boolean hasLogo) {
        String body = """
                <p style="margin:0 0 14px;font-size:16px;">Hello %s,</p>
                <p style="margin:0 0 20px;font-size:15px;line-height:1.6;">Thank you for your interest in Fortivance. Your demo access is ready.</p>
                <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="border:1px solid #e2e8f0;border-left:4px solid #0d9488;border-radius:6px;margin:0 0 12px;">
                  <tr><td style="padding:14px 16px;">
                    <div style="font-weight:700;color:#0f2744;font-size:15px;margin-bottom:10px;">Your sign-in details</div>
                    <div style="font-size:14px;">Email: <code style="background:#f1f5f9;padding:2px 6px;border-radius:4px;font-size:14px;">%s</code></div>
                    <div style="font-size:14px;margin-top:6px;">Password: the one you chose when you requested access</div>
                    <div style="font-size:13px;margin-top:6px;color:#64748b;">Forgot it? Use <a href="%s/forgot-password.html" style="color:#0d9488;">Forgot password?</a> on the sign-in page.</div>
                  </td></tr>
                </table>
                <p style="margin:0 0 4px;font-size:14px;line-height:1.6;">You start as an <strong>Analyst</strong> (triage incidents, review evidence). Switch to <strong>Approver</strong> from the header at any time to approve or reject AI-drafted response plans.</p>
                %s
                <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="background:#fffbeb;border:1px solid #fde68a;border-radius:6px;">
                  <tr><td style="padding:14px 16px;font-size:14px;line-height:1.6;">
                    <strong>Access is valid until %s</strong> (24 hours).<br>
                    Need more time? Before it expires, open your <a href="%s/profile.html" style="color:#0d9488;">profile</a> and request an extension: 24 hours each, up to 2 extensions. You can also change your password there.
                  </td></tr>
                </table>
                <p style="margin:20px 0 0;font-size:13px;line-height:1.6;color:#64748b;">The demo runs on synthetic data shared by all demo users. Please do not enter real customer or bank information, and keep your password private.</p>
                """.formatted(
                esc(oneLine(name)), esc(username), publicUrl,
                button(publicUrl + "/login.html", "Sign in to Fortivance"),
                esc(formatIst(expiresAt)), publicUrl);
        return layout(body, hasLogo);
    }

    // ------------------------------------------------------------------ password reset

    public void sendPasswordReset(String to, String name, String link, int validMinutes) throws MessagingException {
        String text = "Hello " + oneLine(name) + ",\n\n"
                + "We received a request to reset the password for your Fortivance demo account (" + to + ").\n\n"
                + "Choose a new password here (valid for " + validMinutes + " minutes, usable once):\n" + link + "\n\n"
                + "If you did not ask for this, ignore this email; your password will not change.\n\n"
                + "Regards,\nThe Fortivance team\n" + publicUrl + "\n";
        send(to, "Reset your Fortivance password", text, hasLogo -> layout("""
                <p style="margin:0 0 14px;font-size:16px;">Hello %s,</p>
                <p style="margin:0 0 6px;font-size:15px;line-height:1.6;">We received a request to reset the password for your Fortivance demo account (<strong>%s</strong>).</p>
                %s
                <p style="margin:0 0 14px;font-size:14px;line-height:1.6;">The link is valid for <strong>%d minutes</strong> and can be used once. If the button does not work, copy this address into your browser:<br><span style="word-break:break-all;color:#475569;font-size:13px;">%s</span></p>
                <p style="margin:0;font-size:13px;line-height:1.6;color:#64748b;">If you did not ask for this, ignore this email. Your password will not change.</p>
                """.formatted(esc(oneLine(name)), esc(to), button(link, "Choose a new password"), validMinutes, esc(link)),
                hasLogo));
    }

    // ------------------------------------------------------------------ contact form

    /**
     * Automatic acknowledgement to a contact-form sender. It deliberately contains no text supplied by the
     * visitor (only a topic label from a fixed list), so the form cannot be used to send arbitrary content to
     * third-party addresses.
     */
    public void sendContactAcknowledgement(String to, String topicLabel) throws MessagingException {
        String text = "Hello,\n\n"
                + "Thank you for contacting Fortivance. We have received your message about \"" + topicLabel + "\"\n"
                + "and will reply shortly, usually within one working day.\n\n"
                + "If you did not send this message, you can ignore this email.\n\n"
                + "Regards,\nThe Fortivance team\n" + publicUrl + "\n";
        send(to, "We received your message - Fortivance", text, hasLogo -> layout("""
                <p style="margin:0 0 14px;font-size:16px;">Hello,</p>
                <p style="margin:0 0 14px;font-size:15px;line-height:1.6;">Thank you for contacting Fortivance. We have received your message about <strong>%s</strong> and will reply shortly, usually within one working day.</p>
                <p style="margin:0 0 14px;font-size:14px;line-height:1.6;">While you wait, you can explore the product with a personal demo account:</p>
                %s
                <p style="margin:0;font-size:13px;line-height:1.6;color:#64748b;">If you did not send this message, you can ignore this email.</p>
                """.formatted(esc(topicLabel), button(publicUrl + "/request-demo.html", "Request a demo")), hasLogo));
    }

    /** Forwards a contact-form message to the Fortivance mailbox; Reply-To is the visitor. Throws on failure. */
    public void forwardContactMessage(String name, String email, String topicLabel, String message) {
        SimpleMailMessage m = new SimpleMailMessage();
        m.setFrom(from);
        m.setTo(ownerTo.isBlank() ? from : ownerTo);
        m.setReplyTo(oneLine(email));
        m.setSubject("[Fortivance contact] " + topicLabel + ": " + oneLine(name));
        m.setText("Name: " + oneLine(name) + "\n"
                + "Email: " + oneLine(email) + "\n"
                + "Topic: " + topicLabel + "\n\n"
                + message + "\n\n"
                + "-- Sent from the contact form on " + publicUrl + "/about.html. Reply to this email to answer.\n");
        mailSender.send(m);
    }

    // ------------------------------------------------------------------ owner notice

    /** Best-effort notice to the Fortivance mailbox. Never contains passwords. */
    public void notifyOwner(String email, String name, String phone, String purpose,
                            String username, Instant expiresAt) {
        if (ownerTo.isBlank()) {
            return;
        }
        SimpleMailMessage m = new SimpleMailMessage();
        m.setFrom(from);
        m.setTo(ownerTo);
        m.setReplyTo(email);
        m.setSubject("[Fortivance] New demo request: " + oneLine(name));
        m.setText("A demo was provisioned.\n\n"
                + "Name / organisation: " + oneLine(name) + "\n"
                + "Email (sign-in): " + oneLine(email) + "\n"
                + "Phone: " + (phone == null || phone.isBlank() ? "not provided" : oneLine(phone)) + "\n"
                + "Account: " + username + "\n"
                + "Access until: " + formatIst(expiresAt) + "\n\n"
                + "Who they are and why they need access:\n" + purpose + "\n");
        try {
            mailSender.send(m);
        } catch (MailException ignored) {
            // The visitor already has their credentials; the request is stored in demo_requests.
        }
    }

    // ------------------------------------------------------------------ shared

    private void send(String to, String subject, String text, java.util.function.Function<Boolean, String> html)
            throws MessagingException {
        MimeMessage msg = mailSender.createMimeMessage();
        MimeMessageHelper h = new MimeMessageHelper(msg, true, "UTF-8");
        try {
            h.setFrom(from, "Fortivance");
        } catch (UnsupportedEncodingException e) {
            h.setFrom(from);
        }
        h.setTo(to);
        h.setReplyTo(from);
        h.setSubject(subject);
        ClassPathResource logo = new ClassPathResource(LOGO_PATH);
        boolean hasLogo = logo.exists();
        h.setText(text, html.apply(hasLogo));
        if (hasLogo) {
            h.addInline("fortivance-logo", logo, "image/png");
        }
        mailSender.send(msg);
    }

    /** Branded frame shared by all HTML emails. {@code body} must already be escaped. */
    String layout(String body, boolean hasLogo) {
        // The PNG logo has dark text (and its own tagline) on a transparent background, so it sits on white;
        // without it, a navy header with a text wordmark.
        String header = hasLogo
                ? "<tr><td style=\"background:#ffffff;padding:20px 28px;border-bottom:3px solid #0d9488;\">"
                  + "<img src=\"cid:fortivance-logo\" alt=\"Fortivance - Operational resilience and TPRM\" width=\"240\" height=\"64\""
                  + " style=\"display:block;border:0;width:240px;height:64px;\"></td></tr>"
                : "<tr><td style=\"background:#0f2744;padding:22px 28px;\">"
                  + "<span style=\"font-size:22px;font-weight:700;color:#ffffff;letter-spacing:0.5px;\">Fortivance</span>"
                  + "<div style=\"color:#5eead4;font-size:12px;letter-spacing:1px;text-transform:uppercase;margin-top:6px;\">"
                  + "Operational resilience &amp; third-party risk</div></td></tr>";
        return """
                <!DOCTYPE html>
                <html><body style="margin:0;padding:0;background:#f1f5f9;font-family:Segoe UI,Arial,sans-serif;color:#1e293b;">
                <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="background:#f1f5f9;padding:24px 0;">
                <tr><td align="center">
                <table role="presentation" width="600" cellpadding="0" cellspacing="0" style="max-width:600px;width:100%%;background:#ffffff;border-radius:8px;overflow:hidden;border:1px solid #e2e8f0;">
                  %s
                  <tr><td style="padding:28px;">
                %s
                  </td></tr>
                  <tr><td style="background:#f8fafc;padding:16px 28px;font-size:12px;color:#94a3b8;border-top:1px solid #e2e8f0;">
                    Fortivance &middot; <a href="%s" style="color:#64748b;">%s</a><br>Questions? Reply to this email.
                  </td></tr>
                </table>
                </td></tr></table>
                </body></html>
                """.formatted(header, body, publicUrl, esc(publicUrl.replaceFirst("^https?://", "")));
    }

    private static String button(String href, String label) {
        return """
                <p style="margin:22px 0;text-align:center;">
                  <a href="%s" style="background:#0d9488;color:#ffffff;text-decoration:none;padding:12px 26px;border-radius:6px;font-weight:600;display:inline-block;">%s</a>
                </p>
                """.formatted(esc(href), esc(label));
    }

    private static String esc(String s) {
        return HtmlUtils.htmlEscape(s == null ? "" : s);
    }

    /** Strips CR/LF so user input cannot add mail headers or break the layout. */
    static String oneLine(String s) {
        return s == null ? "" : s.replaceAll("[\\r\\n]+", " ").trim();
    }
}
