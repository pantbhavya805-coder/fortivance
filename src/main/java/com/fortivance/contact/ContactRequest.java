package com.fortivance.contact;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Public contact form submission. {@code website} is a honeypot: the field is hidden from people,
 * so any value in it means a bot filled the form.
 */
public record ContactRequest(
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Email @Size(max = 200) String email,
        @NotBlank @Size(min = 10, max = 5000) String message,
        @Size(max = 200) String website
) {
}
