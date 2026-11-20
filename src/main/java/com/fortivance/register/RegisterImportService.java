package com.fortivance.register;

import com.fortivance.audit.AuditService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Imports a bank's vendor register (vendors and subcontractors, systems, business services and the links
 * between them) from the CSV templates. A check run validates and previews without writing; an import run
 * writes everything in one transaction or nothing. Records are matched by vendor name and by system or
 * service code; nothing is deleted. Updating existing records is allowed only when
 * fortivance.import.update-existing is true (IMPORT_UPDATE_EXISTING), so a shared demo cannot be altered.
 */
@Service
public class RegisterImportService {

    static final int MAX_ROWS = 2000;
    static final int MAX_BYTES = 1_000_000;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final AuditService audit;
    private final boolean allowUpdates;

    public RegisterImportService(JdbcTemplate jdbc, PlatformTransactionManager txManager, AuditService audit,
                                 @Value("${fortivance.import.update-existing:false}") boolean allowUpdates) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        this.audit = audit;
        this.allowUpdates = allowUpdates;
    }

    public boolean updatesAllowed() {
        return allowUpdates;
    }

    /** files: "vendors" / "systems" / "services" -> raw bytes (any may be missing). */
    public Map<String, Object> run(Map<String, byte[]> files, boolean apply, String actor) {
        List<RegisterPlan.Issue> fileErrors = new ArrayList<>();
        RegisterCsv.Table v = read(RegisterPlan.VENDORS, files.get(RegisterPlan.VENDORS), fileErrors);
        RegisterCsv.Table s = read(RegisterPlan.SYSTEMS, files.get(RegisterPlan.SYSTEMS), fileErrors);
        RegisterCsv.Table b = read(RegisterPlan.SERVICES, files.get(RegisterPlan.SERVICES), fileErrors);

        RegisterPlan plan = RegisterPlan.build(snapshot(), v, s, b, allowUpdates);
        plan.errors.addAll(0, fileErrors);
        if (v == null && s == null && b == null && fileErrors.isEmpty()) {
            plan.errors.add(new RegisterPlan.Issue("files", 0, "Choose at least one file to import."));
        }

        boolean applied = false;
        if (apply && !plan.hasErrors()) {
            tx.executeWithoutResult(st -> write(plan));
            applied = true;
            Map<String, Object> detail = new LinkedHashMap<>(summary(plan));
            audit.log(actor, "REGISTER_IMPORTED", "BANK", bankId(), detail);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", !plan.hasErrors());
        out.put("applied", applied);
        out.put("updatesAllowed", allowUpdates);
        out.put("summary", summary(plan));
        out.put("errors", plan.errors.stream().map(RegisterImportService::issue).toList());
        out.put("warnings", plan.warnings.stream().map(RegisterImportService::issue).toList());
        out.put("changes", changes(plan));
        return out;
    }

    private static RegisterCsv.Table read(String name, byte[] bytes, List<RegisterPlan.Issue> errors) {
        if (bytes == null || bytes.length == 0) return null;
        if (bytes.length > MAX_BYTES) {
            errors.add(new RegisterPlan.Issue(name, 0, "The file is larger than 1 MB."));
            return null;
        }
        if (bytes.length > 1 && bytes[0] == 'P' && bytes[1] == 'K') {
            errors.add(new RegisterPlan.Issue(name, 0, "This is an Excel workbook (.xlsx). In Excel use File > Save As > "
                    + "\"CSV UTF-8 (Comma delimited)\" and upload the .csv file."));
            return null;
        }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            // Older Excel versions save "CSV (Comma delimited)" in Windows-1252.
            text = new String(bytes, java.nio.charset.Charset.forName("windows-1252"));
        }
        try {
            return RegisterCsv.parse(text, MAX_ROWS);
        } catch (RegisterCsv.CsvException e) {
            errors.add(new RegisterPlan.Issue(name, 0, e.getMessage()));
            return null;
        }
    }

    private static Map<String, Object> issue(RegisterPlan.Issue i) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("file", i.file());
        m.put("line", i.line());
        m.put("message", i.message());
        return m;
    }

    private static Map<String, Object> summary(RegisterPlan p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("vendors", counts(p, p.vendors));
        m.put("systems", counts(p, p.systems));
        m.put("services", counts(p, p.services));
        return m;
    }

    private static Map<String, Long> counts(RegisterPlan p, List<? extends RegisterPlan.Change<?>> c) {
        Map<String, Long> m = new LinkedHashMap<>();
        m.put("create", p.count(c, RegisterPlan.Action.CREATE));
        m.put("update", p.count(c, RegisterPlan.Action.UPDATE));
        m.put("unchanged", p.count(c, RegisterPlan.Action.UNCHANGED));
        return m;
    }

    private static List<Map<String, Object>> changes(RegisterPlan p) {
        List<Map<String, Object>> out = new ArrayList<>();
        p.vendors.forEach(c -> out.add(change("vendor", c.row().name(), c.action())));
        p.systems.forEach(c -> out.add(change("system", c.row().code() + " - " + c.row().name(), c.action())));
        p.services.forEach(c -> out.add(change("service", c.row().code() + " - " + c.row().name(), c.action())));
        return out;
    }

    private static Map<String, Object> change(String type, String label, RegisterPlan.Action a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("label", label);
        m.put("action", a.name());
        return m;
    }

    // ================================================================== read what is stored
    private long bankId() {
        Long id = jdbc.queryForObject("SELECT MIN(id) FROM banks", Long.class);
        if (id == null) throw new IllegalStateException("No bank record exists on this instance.");
        return id;
    }

    RegisterPlan.Snapshot snapshot() {
        List<RegisterPlan.ExVendor> vendors = jdbc.query("SELECT id, name, parent_vendor_id, service_category, "
                + "service_description, risk_tier, is_material, data_access, outsourcing_scope, scope_note, email_domain, "
                + "contract_start, contract_end, last_assessed_on, next_review_due FROM vendors ORDER BY id",
                (rs, i) -> new RegisterPlan.ExVendor(rs.getLong("id"), rs.getString("name"),
                        rs.getObject("parent_vendor_id", Long.class), rs.getString("service_category"),
                        rs.getString("service_description"), rs.getString("risk_tier"), rs.getBoolean("is_material"),
                        rs.getString("data_access"), rs.getString("outsourcing_scope"), rs.getString("scope_note"),
                        rs.getString("email_domain"), date(rs.getDate("contract_start")), date(rs.getDate("contract_end")),
                        date(rs.getDate("last_assessed_on")), date(rs.getDate("next_review_due"))));

        Map<Long, Map<Long, Boolean>> appLinks = new HashMap<>();
        jdbc.query("SELECT application_id, vendor_id, is_single_point FROM application_vendor_dependencies",
                rs -> { appLinks.computeIfAbsent(rs.getLong(1), k -> new LinkedHashMap<>())
                        .merge(rs.getLong(2), rs.getBoolean(3), Boolean::logicalOr); });
        List<RegisterPlan.ExSystem> systems = jdbc.query("SELECT id, code, name, description, criticality, owner_team, "
                + "rto_hours, rpo_minutes FROM applications ORDER BY id",
                (rs, i) -> new RegisterPlan.ExSystem(rs.getLong("id"), rs.getString("code"), rs.getString("name"),
                        rs.getString("description"), rs.getString("criticality"), rs.getString("owner_team"),
                        rs.getObject("rto_hours", Integer.class), rs.getObject("rpo_minutes", Integer.class),
                        appLinks.getOrDefault(rs.getLong("id"), Map.of())));

        Map<Long, Map<Long, Boolean>> svcLinks = new HashMap<>();
        jdbc.query("SELECT business_service_id, application_id, is_essential FROM service_application_dependencies",
                rs -> { svcLinks.computeIfAbsent(rs.getLong(1), k -> new LinkedHashMap<>()).put(rs.getLong(2), rs.getBoolean(3)); });
        List<RegisterPlan.ExService> services = jdbc.query("SELECT id, code, name, description, is_customer_facing, "
                + "criticality, impact_tolerance_hours, bcp_plan_ref, business_owner FROM business_services ORDER BY id",
                (rs, i) -> new RegisterPlan.ExService(rs.getLong("id"), rs.getString("code"), rs.getString("name"),
                        rs.getString("description"), rs.getBoolean("is_customer_facing"), rs.getString("criticality"),
                        rs.getObject("impact_tolerance_hours", Integer.class), rs.getString("bcp_plan_ref"),
                        rs.getString("business_owner"), svcLinks.getOrDefault(rs.getLong("id"), Map.of())));
        return new RegisterPlan.Snapshot(vendors, systems, services);
    }

    private static LocalDate date(Date d) {
        return d == null ? null : d.toLocalDate();
    }

    // ================================================================== write (inside one transaction)
    private void write(RegisterPlan p) {
        long bank = bankId();

        // Vendors: create or update every row first, then set parents (a parent may be later in the file).
        Map<String, Long> vendorIds = new HashMap<>();
        jdbc.query("SELECT id, name FROM vendors ORDER BY id DESC", rs -> { vendorIds.put(RegisterPlan.key(rs.getString(2)), rs.getLong(1)); });
        for (RegisterPlan.Change<RegisterPlan.VendorRow> c : p.vendors) {
            RegisterPlan.VendorRow r = c.row();
            if (c.action() == RegisterPlan.Action.CREATE) {
                KeyHolder kh = new GeneratedKeyHolder();
                jdbc.update(con -> {
                    PreparedStatement ps = con.prepareStatement("INSERT INTO vendors (bank_id, name, service_category, "
                            + "service_description, risk_tier, is_material, data_access, outsourcing_scope, scope_note, "
                            + "email_domain, contract_start, contract_end, last_assessed_on, next_review_due) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS);
                    ps.setLong(1, bank);
                    vendorParams(ps, 2, r);
                    return ps;
                }, kh);
                vendorIds.put(RegisterPlan.key(r.name()), kh.getKey().longValue());
            } else if (c.action() == RegisterPlan.Action.UPDATE) {
                jdbc.update(con -> {
                    PreparedStatement ps = con.prepareStatement("UPDATE vendors SET name = ?, service_category = ?, "
                            + "service_description = ?, risk_tier = ?, is_material = ?, data_access = ?, outsourcing_scope = ?, "
                            + "scope_note = ?, email_domain = ?, contract_start = ?, contract_end = ?, last_assessed_on = ?, "
                            + "next_review_due = ? WHERE id = ?");
                    vendorParams(ps, 1, r);
                    ps.setLong(14, c.existingId());
                    return ps;
                });
            }
        }
        for (RegisterPlan.Change<RegisterPlan.VendorRow> c : p.vendors) {
            if (c.action() == RegisterPlan.Action.UNCHANGED) continue;
            Long parent = c.row().parent() == null ? null : vendorIds.get(RegisterPlan.key(c.row().parent()));
            jdbc.update("UPDATE vendors SET parent_vendor_id = ? WHERE id = ?", parent, vendorIds.get(RegisterPlan.key(c.row().name())));
        }

        Map<Long, String> vendorCategory = new HashMap<>();
        jdbc.query("SELECT id, service_category FROM vendors", rs -> { vendorCategory.put(rs.getLong(1), rs.getString(2)); });

        // Systems and their vendor links.
        Map<String, Long> systemIds = new HashMap<>();
        jdbc.query("SELECT id, code FROM applications", rs -> { systemIds.put(rs.getString(2), rs.getLong(1)); });
        for (RegisterPlan.Change<RegisterPlan.SystemRow> c : p.systems) {
            RegisterPlan.SystemRow r = c.row();
            if (c.action() == RegisterPlan.Action.UNCHANGED) continue;
            long id;
            if (c.action() == RegisterPlan.Action.CREATE) {
                KeyHolder kh = new GeneratedKeyHolder();
                jdbc.update(con -> {
                    PreparedStatement ps = con.prepareStatement("INSERT INTO applications (bank_id, code, name, description, "
                            + "criticality, owner_team, rto_hours, rpo_minutes) VALUES (?,?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS);
                    ps.setLong(1, bank);
                    ps.setString(2, r.code());
                    ps.setString(3, r.name());
                    ps.setString(4, r.description());
                    ps.setString(5, r.criticality());
                    ps.setString(6, r.ownerTeam());
                    ps.setObject(7, r.rtoHours());
                    ps.setObject(8, r.rpoMinutes());
                    return ps;
                }, kh);
                id = kh.getKey().longValue();
                systemIds.put(r.code(), id);
            } else {
                id = c.existingId();
                jdbc.update("UPDATE applications SET name = ?, description = ?, criticality = ?, owner_team = ?, "
                        + "rto_hours = ?, rpo_minutes = ? WHERE id = ?", r.name(), r.description(), r.criticality(),
                        r.ownerTeam(), r.rtoHours(), r.rpoMinutes(), id);
                jdbc.update("DELETE FROM application_vendor_dependencies WHERE application_id = ?", id);
            }
            for (Map.Entry<String, Boolean> link : r.vendorLinks().entrySet()) {
                long vendorId = vendorIds.get(RegisterPlan.key(link.getKey()));
                String type = vendorCategory.getOrDefault(vendorId, "SERVICE");
                jdbc.update("INSERT INTO application_vendor_dependencies (application_id, vendor_id, dependency_type, "
                        + "is_single_point) VALUES (?,?,?,?)", id, vendorId, type.length() > 40 ? type.substring(0, 40) : type,
                        link.getValue());
            }
        }

        // Business services and their system links.
        for (RegisterPlan.Change<RegisterPlan.ServiceRow> c : p.services) {
            RegisterPlan.ServiceRow r = c.row();
            if (c.action() == RegisterPlan.Action.UNCHANGED) continue;
            long id;
            if (c.action() == RegisterPlan.Action.CREATE) {
                KeyHolder kh = new GeneratedKeyHolder();
                jdbc.update(con -> {
                    PreparedStatement ps = con.prepareStatement("INSERT INTO business_services (bank_id, code, name, description, "
                            + "is_customer_facing, criticality, impact_tolerance_hours, bcp_plan_ref, business_owner) "
                            + "VALUES (?,?,?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS);
                    ps.setLong(1, bank);
                    ps.setString(2, r.code());
                    ps.setString(3, r.name());
                    ps.setString(4, r.description());
                    ps.setBoolean(5, r.customerFacing());
                    ps.setString(6, r.criticality());
                    ps.setObject(7, r.toleranceHours());
                    ps.setString(8, r.bcpRef());
                    ps.setString(9, r.owner());
                    return ps;
                }, kh);
                id = kh.getKey().longValue();
            } else {
                id = c.existingId();
                jdbc.update("UPDATE business_services SET name = ?, description = ?, is_customer_facing = ?, criticality = ?, "
                        + "impact_tolerance_hours = ?, bcp_plan_ref = ?, business_owner = ? WHERE id = ?", r.name(),
                        r.description(), r.customerFacing(), r.criticality(), r.toleranceHours(), r.bcpRef(), r.owner(), id);
                jdbc.update("DELETE FROM service_application_dependencies WHERE business_service_id = ?", id);
            }
            for (Map.Entry<String, Boolean> link : r.systemLinks().entrySet()) {
                jdbc.update("INSERT INTO service_application_dependencies (business_service_id, application_id, is_essential) "
                        + "VALUES (?,?,?)", id, systemIds.get(link.getKey()), link.getValue());
            }
        }
    }

    private static void vendorParams(PreparedStatement ps, int start, RegisterPlan.VendorRow r) throws java.sql.SQLException {
        int i = start;
        ps.setString(i++, r.name());
        ps.setString(i++, r.category());
        ps.setString(i++, r.description());
        ps.setString(i++, r.tier());
        ps.setBoolean(i++, r.material());
        ps.setString(i++, r.dataAccess());
        ps.setString(i++, r.scope());
        ps.setString(i++, r.scopeNote());
        ps.setString(i++, r.emailDomain());
        ps.setObject(i++, r.contractStart() == null ? null : Date.valueOf(r.contractStart()));
        ps.setObject(i++, r.contractEnd() == null ? null : Date.valueOf(r.contractEnd()));
        ps.setObject(i++, r.lastAssessed() == null ? null : Date.valueOf(r.lastAssessed()));
        ps.setObject(i, r.nextReview() == null ? null : Date.valueOf(r.nextReview()));
    }
}
