package com.fortivance.demo;

import java.security.SecureRandom;

/** Password generation for demo accounts (the username is the requester's email address). */
final class Credentials {

    private static final SecureRandom RANDOM = new SecureRandom();
    // No look-alike characters (0/O, 1/l/I) so the password can be typed from the email.
    private static final String UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final String LOWER = "abcdefghijkmnpqrstuvwxyz";
    private static final String DIGITS = "23456789";
    private static final String ALL = UPPER + LOWER + DIGITS;
    static final int PASSWORD_LENGTH = 14;

    private Credentials() {
    }

    /** Random password with at least one upper-case letter, one lower-case letter and one digit. */
    static String password() {
        while (true) {
            StringBuilder sb = new StringBuilder(PASSWORD_LENGTH);
            for (int i = 0; i < PASSWORD_LENGTH; i++) {
                sb.append(ALL.charAt(RANDOM.nextInt(ALL.length())));
            }
            String p = sb.toString();
            if (p.chars().anyMatch(c -> UPPER.indexOf(c) >= 0)
                    && p.chars().anyMatch(c -> LOWER.indexOf(c) >= 0)
                    && p.chars().anyMatch(c -> DIGITS.indexOf(c) >= 0)) {
                return p;
            }
        }
    }
}
