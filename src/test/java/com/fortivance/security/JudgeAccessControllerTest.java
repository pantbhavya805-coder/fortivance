package com.fortivance.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JudgeAccessControllerTest {

    @Test
    void passwordIsHiddenUnlessExplicitlyPublic() {
        var hidden = new JudgeAccessController("Secret!2026", false).access();
        assertThat(hidden.getBody()).containsEntry("username", "judgedemo").containsEntry("password", null);
        assertThat(hidden.getHeaders().getCacheControl()).contains("no-store");

        assertThat(new JudgeAccessController("Secret!2026", true).access().getBody())
                .containsEntry("password", "Secret!2026");
        assertThat(new JudgeAccessController("", true).access().getBody()).containsEntry("password", null);
    }
}
