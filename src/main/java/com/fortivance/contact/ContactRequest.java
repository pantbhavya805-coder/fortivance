package com.fortivance.contact;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Contact form on the About page. Fields are trimmed before validation. {@code topic} must be one of the
 * fixed keys in {@link ContactService#TOPICS}. {@code website} is a honeypot: hidden from people, so any value
 * means a bot.
 */
public record ContactRequest(
        @NotBlank @Size(min = 2, max = 100)
        String name,

        @NotBlank @Size(max = 200)
        @Pattern(regexp = "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
        String email,

        @NotBlank
        String topic,

        @NotBlank @Size(min = 10, max = 3000)
        String message,

        @Size(max = 200)
        String website
) {
    public ContactRequest {
        name = strip(name);
        email = strip(email);
        topic = strip(topic);
        message = strip(message);
    }

    private static String strip(String s) {
        return s == null ? null : s.strip();
    }
}
