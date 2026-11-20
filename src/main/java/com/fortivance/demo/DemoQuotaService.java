package com.fortivance.demo;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hard limits on what a demo account (one created through Request a demo) can do, so a single visitor cannot
 * exhaust the shared AI budget or fill the shared register: by default 10 incident uploads and 2 register
 * imports per account, for the life of the account. Permanent accounts (judge, the admin) are not limited.
 *
 * A use is reserved with a conditional UPDATE, so concurrent requests cannot exceed the limit, and released
 * again if the action fails.
 */
@Service
public class DemoQuotaService {

    public enum Kind {
        INCIDENT_UPLOAD("incident_uploads_used", "incident notice uploads"),
        REGISTER_IMPORT("register_imports_used", "register imports");

        final String column;   // fixed column name, never user input
        final String label;

        Kind(String column, String label) {
            this.column = column;
            this.label = label;
        }
    }

    private final JdbcTemplate jdbc;
    private final Map<Kind, Integer> limits;

    @Autowired
    public DemoQuotaService(JdbcTemplate jdbc,
                            @Value("${fortivance.demo.max-incident-uploads:10}") int maxIncidentUploads,
                            @Value("${fortivance.demo.max-register-imports:2}") int maxRegisterImports) {
        this.jdbc = jdbc;
        this.limits = Map.of(Kind.INCIDENT_UPLOAD, maxIncidentUploads, Kind.REGISTER_IMPORT, maxRegisterImports);
    }

    public int limit(Kind kind) {
        return limits.get(kind);
    }

    /** True when this user is a demo account (and therefore limited). */
    public boolean isLimited(Long userId) {
        if (userId == null) return false;
        List<Long> r = jdbc.queryForList("SELECT demo_request_id FROM users WHERE id = ?", Long.class, userId);
        return !r.isEmpty() && r.get(0) != null;
    }

    /** Reserves one use. Returns false when a demo account has used up its limit; always true for others. */
    public boolean tryUse(Long userId, Kind kind) {
        if (!isLimited(userId)) return true;
        int updated = jdbc.update("UPDATE users SET " + kind.column + " = " + kind.column + " + 1 "
                + "WHERE id = ? AND demo_request_id IS NOT NULL AND " + kind.column + " < ?", userId, limit(kind));
        return updated == 1;
    }

    /** Gives back a use reserved for an action that then failed. */
    public void release(Long userId, Kind kind) {
        if (userId == null) return;
        jdbc.update("UPDATE users SET " + kind.column + " = " + kind.column + " - 1 "
                + "WHERE id = ? AND demo_request_id IS NOT NULL AND " + kind.column + " > 0", userId);
    }

    public String limitMessage(Kind kind) {
        return "This demo account has used all " + limit(kind) + " " + kind.label + " it allows. Demo accounts are "
                + "limited so the shared demo stays available to everyone. Ask us for more through Contact us on the About page.";
    }

    /** For the UI: null values mean unlimited. */
    public Map<String, Object> status(Long userId) {
        Map<String, Object> out = new LinkedHashMap<>();
        boolean limited = isLimited(userId);
        out.put("limited", limited);
        for (Kind k : Kind.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            if (limited) {
                Integer used = jdbc.queryForObject("SELECT " + k.column + " FROM users WHERE id = ?", Integer.class, userId);
                int u = used == null ? 0 : used;
                m.put("used", u);
                m.put("limit", limit(k));
                m.put("remaining", Math.max(0, limit(k) - u));
            } else {
                m.put("used", null);
                m.put("limit", null);
                m.put("remaining", null);
            }
            out.put(k == Kind.INCIDENT_UPLOAD ? "incidentUploads" : "registerImports", m);
        }
        return out;
    }
}
