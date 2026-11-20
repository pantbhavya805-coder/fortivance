package com.fortivance.demo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static com.fortivance.demo.DemoQuotaService.Kind.INCIDENT_UPLOAD;
import static com.fortivance.demo.DemoQuotaService.Kind.REGISTER_IMPORT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DemoQuotaServiceTest {

    private JdbcTemplate jdbc;
    private DemoQuotaService quota;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        quota = new DemoQuotaService(jdbc, 10, 2);
    }

    private void demoAccount(long userId) {
        when(jdbc.queryForList(contains("SELECT demo_request_id"), eq(Long.class), eq(userId))).thenReturn(List.of(3L));
    }

    private void permanentAccount(long userId) {
        when(jdbc.queryForList(contains("SELECT demo_request_id"), eq(Long.class), eq(userId)))
                .thenReturn(Arrays.asList((Long) null));
    }

    @Test
    void demoAccountIsLimitedByAConditionalUpdate() {
        demoAccount(7L);
        when(jdbc.update(contains("incident_uploads_used = incident_uploads_used + 1"), eq(7L), eq(10))).thenReturn(1, 0);
        assertThat(quota.tryUse(7L, INCIDENT_UPLOAD)).isTrue();
        assertThat(quota.tryUse(7L, INCIDENT_UPLOAD)).isFalse(); // the 11th upload: no row updated
        verify(jdbc, times(2)).update(contains("AND incident_uploads_used < ?"), eq(7L), eq(10));
    }

    @Test
    void importLimitIsSeparateAndCanBeReleased() {
        demoAccount(7L);
        when(jdbc.update(contains("register_imports_used = register_imports_used + 1"), eq(7L), eq(2))).thenReturn(1);
        assertThat(quota.tryUse(7L, REGISTER_IMPORT)).isTrue();
        quota.release(7L, REGISTER_IMPORT);
        verify(jdbc).update(contains("register_imports_used = register_imports_used - 1"), eq(7L));
    }

    @Test
    void judgeAndAdminAccountsAreNeverLimited() {
        permanentAccount(2L);
        assertThat(quota.tryUse(2L, INCIDENT_UPLOAD)).isTrue();
        assertThat(quota.tryUse(2L, REGISTER_IMPORT)).isTrue();
        verify(jdbc, never()).update(contains("_used + 1"), any(), any());
        assertThat(quota.status(2L)).containsEntry("limited", false);

        when(jdbc.queryForList(contains("SELECT demo_request_id"), eq(Long.class), eq(99L))).thenReturn(Collections.emptyList());
        assertThat(quota.tryUse(99L, INCIDENT_UPLOAD)).isTrue();
        assertThat(quota.tryUse(null, INCIDENT_UPLOAD)).isTrue();
    }

    @Test
    void statusShowsWhatIsLeft() {
        demoAccount(7L);
        when(jdbc.queryForObject(contains("SELECT incident_uploads_used"), eq(Integer.class), eq(7L))).thenReturn(4);
        when(jdbc.queryForObject(contains("SELECT register_imports_used"), eq(Integer.class), eq(7L))).thenReturn(2);
        var status = quota.status(7L);
        assertThat(status).containsEntry("limited", true);
        assertThat(status.get("incidentUploads")).asString().contains("remaining=6").contains("limit=10");
        assertThat(status.get("registerImports")).asString().contains("remaining=0");
        assertThat(quota.limitMessage(REGISTER_IMPORT)).contains("all 2 register imports");
    }
}
