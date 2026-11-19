package com.fortivance.demo;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CredentialsTest {

    @Test
    void passwordsAreStrongAndDistinct() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            String p = Credentials.password();
            assertThat(p).hasSize(Credentials.PASSWORD_LENGTH)
                    .matches(".*[A-Z].*").matches(".*[a-z].*").matches(".*[2-9].*")
                    .doesNotContain("0", "O", "1", "l", "I");
            seen.add(p);
        }
        assertThat(seen).hasSize(200);
    }
}
