package com.fortivance.demo;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Public "request a demo" submission. Fields are trimmed before validation, so the length rules apply
 * to real content. {@code website} is a honeypot: hidden from people, so any value means a bot.
 */
public record DemoRequestForm(
        @NotBlank @Email @Size(max = 200)
        @Pattern(regexp = "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
        String email,

        @NotBlank @Size(min = 2, max = 100)
        String name,

        @Pattern(regexp = "^$|^[0-9+()\\- ]{7,20}$")
        String phone,

        @NotBlank @Size(min = 50, max = 1000)
        String purpose,

        /** Chosen by the requester; strength is checked by DemoRequestService.passwordProblem. Not trimmed. */
        @NotBlank @Size(max = 64)
        String password,

        @Size(max = 200)
        String website
) {
    public DemoRequestForm {
        email = strip(email);
        name = strip(name);
        phone = strip(phone);
        purpose = strip(purpose);
    }

    /** Never print the password (records include every field in toString by default). */
    @Override
    public String toString() {
        return "DemoRequestForm[email=" + email + ", name=" + name + "]";
    }

    private static String strip(String s) {
        return s == null ? null : s.strip();
    }
}
