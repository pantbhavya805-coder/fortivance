package com.fortivance.register;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates the three register files against each other and against what is already stored, and works out
 * what an import would create, update or leave unchanged. Pure logic: no database access, so it is fully
 * unit-tested. Nothing is ever deleted; a row that is not in the files is left as it is.
 */
final class RegisterPlan {

    // ------------------------------------------------------------------ what is stored today
    record ExVendor(long id, String name, Long parentId, String category, String description, String tier,
                    boolean material, String dataAccess, String scope, String scopeNote, String emailDomain,
                    LocalDate contractStart, LocalDate contractEnd, LocalDate lastAssessed, LocalDate nextReview) { }

    record ExSystem(long id, String code, String name, String description, String criticality, String ownerTeam,
                    Integer rtoHours, Integer rpoMinutes, Map<Long, Boolean> vendorLinks) { }

    record ExService(long id, String code, String name, String description, boolean customerFacing,
                     String criticality, Integer toleranceHours, String bcpRef, String owner,
                     Map<Long, Boolean> systemLinks) { }

    record Snapshot(List<ExVendor> vendors, List<ExSystem> systems, List<ExService> services) { }

    // ------------------------------------------------------------------ rows from the files
    record VendorRow(int line, String name, String parent, String category, String description, String tier,
                     boolean material, String dataAccess, String scope, String scopeNote, String emailDomain,
                     LocalDate contractStart, LocalDate contractEnd, LocalDate lastAssessed, LocalDate nextReview) { }

    /** links: vendor name (as written) -> single point of failure. */
    record SystemRow(int line, String code, String name, String description, String criticality, String ownerTeam,
                     Integer rtoHours, Integer rpoMinutes, Map<String, Boolean> vendorLinks) { }

    /** links: system code -> essential. */
    record ServiceRow(int line, String code, String name, String description, boolean customerFacing,
                      String criticality, Integer toleranceHours, String bcpRef, String owner,
                      Map<String, Boolean> systemLinks) { }

    enum Action { CREATE, UPDATE, UNCHANGED }

    record Change<R>(Action action, R row, Long existingId) { }

    record Issue(String file, int line, String message) { }

    final List<Change<VendorRow>> vendors = new ArrayList<>();
    final List<Change<SystemRow>> systems = new ArrayList<>();
    final List<Change<ServiceRow>> services = new ArrayList<>();
    final List<Issue> errors = new ArrayList<>();
    final List<Issue> warnings = new ArrayList<>();

    static final String VENDORS = "vendors", SYSTEMS = "systems", SERVICES = "services";
    static final Set<String> TIERS = Set.of("CRITICAL", "HIGH", "MEDIUM", "LOW");
    static final Set<String> DATA_ACCESS = Set.of("FINANCIAL", "CUSTOMER_PII", "INTERNAL", "NONE");
    static final Set<String> SCOPES = Set.of("IN_SCOPE", "EXCLUDED", "UNDER_REVIEW");
    static final List<String> VENDOR_REQUIRED = List.of("name", "service_category", "risk_tier", "data_access");
    static final List<String> VENDOR_OPTIONAL = List.of("parent_vendor", "service_description", "material",
            "outsourcing_scope", "scope_note", "email_domain", "contract_start", "contract_end", "last_assessed_on",
            "next_review_due");
    static final List<String> SYSTEM_REQUIRED = List.of("code", "name", "criticality");
    static final List<String> SYSTEM_OPTIONAL = List.of("description", "owner_team", "rto_hours", "rpo_minutes",
            "vendors", "single_point_vendors");
    static final List<String> SERVICE_REQUIRED = List.of("code", "name", "criticality");
    static final List<String> SERVICE_OPTIONAL = List.of("description", "customer_facing", "impact_tolerance_hours",
            "bcp_plan_ref", "business_owner", "essential_systems", "supporting_systems");

    private static final Pattern CODE = Pattern.compile("[A-Z0-9][A-Z0-9_-]{0,29}");
    private static final Pattern CATEGORY = Pattern.compile("[A-Z0-9_]{2,80}");
    private static final Pattern DOMAIN = Pattern.compile("[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+");
    private static final List<DateTimeFormatter> DATES = java.util.stream.Stream.of("uuuu-M-d", "d-M-uuuu", "d/M/uuuu", "d.M.uuuu")
            .map(f -> DateTimeFormatter.ofPattern(f).withResolverStyle(java.time.format.ResolverStyle.STRICT)).toList();

    private RegisterPlan() {
    }

    boolean hasErrors() {
        return !errors.isEmpty();
    }

    long count(List<? extends Change<?>> changes, Action a) {
        return changes.stream().filter(c -> c.action() == a).count();
    }

    // ================================================================== entry point
    static RegisterPlan build(Snapshot existing, RegisterCsv.Table vendorFile, RegisterCsv.Table systemFile,
                              RegisterCsv.Table serviceFile, boolean allowUpdates) {
        RegisterPlan p = new RegisterPlan();
        List<VendorRow> vRows = vendorFile == null ? List.of() : p.readVendors(vendorFile);
        List<SystemRow> sRows = systemFile == null ? List.of() : p.readSystems(systemFile);
        List<ServiceRow> bRows = serviceFile == null ? List.of() : p.readServices(serviceFile);
        p.diffVendors(existing, vRows, allowUpdates);
        p.diffSystems(existing, vRows, sRows, allowUpdates);
        p.diffServices(existing, sRows, bRows, allowUpdates);
        return p;
    }

    // ================================================================== reading and field validation
    private static final Map<String, String> BOX = Map.of(VENDORS, "box 1 (Vendors)", SYSTEMS, "box 2 (Systems)",
            SERVICES, "box 3 (Business services)");

    private static int matches(List<String> headers, List<String> required, List<String> optional) {
        int n = 0;
        for (String h : headers) if (required.contains(h) || optional.contains(h)) n++;
        return n;
    }

    /** The template whose columns best fit these headers; catches a file chosen in the wrong box. */
    static String likelyTemplate(List<String> headers) {
        int v = matches(headers, VENDOR_REQUIRED, VENDOR_OPTIONAL);
        int s = matches(headers, SYSTEM_REQUIRED, SYSTEM_OPTIONAL);
        int b = matches(headers, SERVICE_REQUIRED, SERVICE_OPTIONAL);
        if (v > s && v > b) return VENDORS;
        if (s > v && s > b) return SYSTEMS;
        if (b > v && b > s) return SERVICES;
        return null;
    }

    private boolean checkColumns(String file, RegisterCsv.Table t, List<String> required, List<String> optional) {
        String likely = likelyTemplate(t.headers());
        if (likely != null && !likely.equals(file)) {
            errors.add(new Issue(file, 1, "This looks like the " + likely + " file, not the " + file
                    + " file. Choose it in " + BOX.get(likely) + "."));
            return false;
        }
        boolean ok = true;
        for (String col : required) {
            if (!t.headers().contains(col)) {
                errors.add(new Issue(file, 1, "Missing column \"" + col + "\". Use the template's header row."));
                ok = false;
            }
        }
        for (String h : t.headers()) {
            if (!h.isBlank() && !required.contains(h) && !optional.contains(h)) {
                warnings.add(new Issue(file, 1, "Unknown column \"" + h + "\" is ignored."));
            }
        }
        if (t.rows().isEmpty()) {
            warnings.add(new Issue(file, 1, "The file has a header but no rows."));
        }
        return ok;
    }

    private List<VendorRow> readVendors(RegisterCsv.Table t) {
        List<VendorRow> out = new ArrayList<>();
        if (!checkColumns(VENDORS, t, VENDOR_REQUIRED, VENDOR_OPTIONAL)) return out;
        Set<String> seen = new LinkedHashSet<>();
        for (RegisterCsv.Row r : t.rows()) {
            Field f = new Field(VENDORS, r.line());
            String name = f.text(r, "name", 200, true);
            String parent = f.text(r, "parent_vendor", 200, false);
            String category = f.pattern(r, "service_category", CATEGORY, "letters, digits and _ (for example CLOUD_HOSTING)", true);
            String description = f.text(r, "service_description", 500, false);
            String tier = f.choice(r, "risk_tier", TIERS, true);
            boolean material = f.yesNo(r, "material", false);
            String data = f.choice(r, "data_access", DATA_ACCESS, true);
            String scope = f.choice(r, "outsourcing_scope", SCOPES, false);
            String note = f.text(r, "scope_note", 300, false);
            String domain = f.text(r, "email_domain", 120, false);
            if (domain != null) {
                domain = domain.toLowerCase(Locale.ROOT);
                if (!DOMAIN.matcher(domain).matches()) f.error("email_domain must be a domain such as vendor.com, not an email address.");
            }
            LocalDate cs = f.date(r, "contract_start"), ce = f.date(r, "contract_end");
            LocalDate la = f.date(r, "last_assessed_on"), nr = f.date(r, "next_review_due");
            if (cs != null && ce != null && ce.isBefore(cs)) f.error("contract_end is before contract_start.");
            if (name != null && !seen.add(key(name))) f.error("Vendor \"" + name + "\" appears more than once in the file.");
            if (name != null && parent != null && key(name).equals(key(parent))) f.error("A vendor cannot be its own parent_vendor.");
            if (f.ok()) {
                out.add(new VendorRow(r.line(), name, parent, category, description, tier, material, data,
                        scope == null ? "IN_SCOPE" : scope, note, domain, cs, ce, la, nr));
            }
        }
        return out;
    }

    private List<SystemRow> readSystems(RegisterCsv.Table t) {
        List<SystemRow> out = new ArrayList<>();
        if (!checkColumns(SYSTEMS, t, SYSTEM_REQUIRED, SYSTEM_OPTIONAL)) return out;
        Set<String> seen = new LinkedHashSet<>();
        for (RegisterCsv.Row r : t.rows()) {
            Field f = new Field(SYSTEMS, r.line());
            String code = f.pattern(r, "code", CODE, "up to 30 capital letters, digits, - or _ (for example CBS)", true);
            String name = f.text(r, "name", 200, true);
            String description = f.text(r, "description", 500, false);
            String crit = f.choice(r, "criticality", TIERS, true);
            String owner = f.text(r, "owner_team", 100, false);
            Integer rto = f.integer(r, "rto_hours", 0, 8760);
            Integer rpo = f.integer(r, "rpo_minutes", 0, 525600);
            List<String> vendors = f.list(r, "vendors");
            List<String> single = f.list(r, "single_point_vendors");
            Map<String, Boolean> links = new LinkedHashMap<>();
            vendors.forEach(v -> links.put(v, false));
            for (String s : single) {
                String match = links.keySet().stream().filter(v -> key(v).equals(key(s))).findFirst().orElse(null);
                if (match == null) f.error("single_point_vendors lists \"" + s + "\", which is not in this row's vendors.");
                else links.put(match, true);
            }
            if (code != null && !seen.add(code)) f.error("System code " + code + " appears more than once in the file.");
            if (f.ok()) out.add(new SystemRow(r.line(), code, name, description, crit, owner, rto, rpo, links));
        }
        return out;
    }

    private List<ServiceRow> readServices(RegisterCsv.Table t) {
        List<ServiceRow> out = new ArrayList<>();
        if (!checkColumns(SERVICES, t, SERVICE_REQUIRED, SERVICE_OPTIONAL)) return out;
        Set<String> seen = new LinkedHashSet<>();
        for (RegisterCsv.Row r : t.rows()) {
            Field f = new Field(SERVICES, r.line());
            String code = f.pattern(r, "code", CODE, "up to 30 capital letters, digits, - or _ (for example SVC-PAY)", true);
            String name = f.text(r, "name", 200, true);
            String description = f.text(r, "description", 500, false);
            boolean facing = f.yesNo(r, "customer_facing", true);
            String crit = f.choice(r, "criticality", TIERS, true);
            Integer tolerance = f.integer(r, "impact_tolerance_hours", 1, 8760);
            String bcp = f.text(r, "bcp_plan_ref", 50, false);
            String owner = f.text(r, "business_owner", 100, false);
            Map<String, Boolean> links = new LinkedHashMap<>();
            for (String s : f.list(r, "essential_systems")) links.put(s.toUpperCase(Locale.ROOT), true);
            for (String s : f.list(r, "supporting_systems")) {
                String c = s.toUpperCase(Locale.ROOT);
                if (links.containsKey(c)) f.error("System " + c + " is listed as both essential and supporting.");
                else links.put(c, false);
            }
            if (code != null && !seen.add(code)) f.error("Service code " + code + " appears more than once in the file.");
            if (f.ok()) out.add(new ServiceRow(r.line(), code, name, description, facing, crit, tolerance, bcp, owner, links));
        }
        return out;
    }

    // ================================================================== cross-checks and diff
    private void diffVendors(Snapshot ex, List<VendorRow> rows, boolean allowUpdates) {
        Map<String, ExVendor> byName = new LinkedHashMap<>();
        ex.vendors().forEach(v -> byName.putIfAbsent(key(v.name()), v));
        Map<Long, ExVendor> byId = new LinkedHashMap<>();
        ex.vendors().forEach(v -> byId.put(v.id(), v));
        Map<String, VendorRow> inFile = new LinkedHashMap<>();
        rows.forEach(r -> inFile.put(key(r.name()), r));
        Set<Long> parentsInRegister = new LinkedHashSet<>();
        ex.vendors().forEach(v -> { if (v.parentId() != null) parentsInRegister.add(v.parentId()); });

        for (VendorRow r : rows) {
            if (r.parent() != null) {
                VendorRow fileParent = inFile.get(key(r.parent()));
                ExVendor dbParent = byName.get(key(r.parent()));
                if (fileParent == null && dbParent == null) {
                    errors.add(new Issue(VENDORS, r.line(), "parent_vendor \"" + r.parent() + "\" is not in the file or the register."));
                    continue;
                }
                boolean parentHasParent = fileParent != null ? fileParent.parent() != null : dbParent.parentId() != null;
                if (parentHasParent) {
                    errors.add(new Issue(VENDORS, r.line(), "parent_vendor \"" + r.parent()
                            + "\" is itself a subcontractor. Name the direct vendor the bank contracts with."));
                    continue;
                }
            }
            ExVendor e = byName.get(key(r.name()));
            if (e != null && r.parent() != null && parentsInRegister.contains(e.id())) {
                errors.add(new Issue(VENDORS, r.line(), "Vendor \"" + r.name() + "\" has its own subcontractors in the "
                        + "register, so it cannot itself be listed as a subcontractor."));
                continue;
            }
            if (e == null) {
                vendors.add(new Change<>(Action.CREATE, r, null));
                continue;
            }
            String existingParent = e.parentId() == null ? null : byId.get(e.parentId()) == null ? null : byId.get(e.parentId()).name();
            boolean same = sameKey(existingParent, r.parent()) && Objects.equals(e.category(), r.category())
                    && eq(e.description(), r.description()) && Objects.equals(e.tier(), r.tier())
                    && e.material() == r.material() && Objects.equals(e.dataAccess(), r.dataAccess())
                    && Objects.equals(e.scope(), r.scope()) && eq(e.scopeNote(), r.scopeNote())
                    && eq(e.emailDomain(), r.emailDomain()) && Objects.equals(e.contractStart(), r.contractStart())
                    && Objects.equals(e.contractEnd(), r.contractEnd()) && Objects.equals(e.lastAssessed(), r.lastAssessed())
                    && Objects.equals(e.nextReview(), r.nextReview());
            addChange(vendors, VENDORS, r.line(), "Vendor \"" + r.name() + "\"", same, allowUpdates, r, e.id());
        }
    }

    private void diffSystems(Snapshot ex, List<VendorRow> vRows, List<SystemRow> rows, boolean allowUpdates) {
        Map<String, Long> vendorIds = new LinkedHashMap<>();
        ex.vendors().forEach(v -> vendorIds.putIfAbsent(key(v.name()), v.id()));
        Set<String> fileVendors = new LinkedHashSet<>();
        vRows.forEach(v -> fileVendors.add(key(v.name())));
        Map<String, ExSystem> byCode = new LinkedHashMap<>();
        ex.systems().forEach(s -> byCode.put(s.code(), s));

        for (SystemRow r : rows) {
            boolean ok = true;
            for (String v : r.vendorLinks().keySet()) {
                if (!vendorIds.containsKey(key(v)) && !fileVendors.contains(key(v))) {
                    errors.add(new Issue(SYSTEMS, r.line(), "Vendor \"" + v + "\" is not in the vendors file or the register."));
                    ok = false;
                }
            }
            if (!ok) continue;
            ExSystem e = byCode.get(r.code());
            if (e == null) {
                systems.add(new Change<>(Action.CREATE, r, null));
                continue;
            }
            Map<String, Boolean> existingLinks = new LinkedHashMap<>();
            e.vendorLinks().forEach((id, sp) -> ex.vendors().stream().filter(v -> v.id() == id).findFirst()
                    .ifPresent(v -> existingLinks.put(key(v.name()), sp)));
            Map<String, Boolean> newLinks = new LinkedHashMap<>();
            r.vendorLinks().forEach((n, sp) -> newLinks.put(key(n), sp));
            boolean same = Objects.equals(e.name(), r.name()) && eq(e.description(), r.description())
                    && Objects.equals(e.criticality(), r.criticality()) && eq(e.ownerTeam(), r.ownerTeam())
                    && Objects.equals(e.rtoHours(), r.rtoHours()) && Objects.equals(e.rpoMinutes(), r.rpoMinutes())
                    && existingLinks.equals(newLinks);
            addChange(systems, SYSTEMS, r.line(), "System " + r.code(), same, allowUpdates, r, e.id());
        }
    }

    private void diffServices(Snapshot ex, List<SystemRow> sRows, List<ServiceRow> rows, boolean allowUpdates) {
        Map<Long, String> systemCodes = new LinkedHashMap<>();
        ex.systems().forEach(s -> systemCodes.put(s.id(), s.code()));
        Set<String> knownSystems = new LinkedHashSet<>(systemCodes.values());
        sRows.forEach(s -> knownSystems.add(s.code()));
        Map<String, ExService> byCode = new LinkedHashMap<>();
        ex.services().forEach(s -> byCode.put(s.code(), s));

        for (ServiceRow r : rows) {
            boolean ok = true;
            for (String c : r.systemLinks().keySet()) {
                if (!knownSystems.contains(c)) {
                    errors.add(new Issue(SERVICES, r.line(), "System " + c + " is not in the systems file or the register."));
                    ok = false;
                }
            }
            if (r.systemLinks().isEmpty()) {
                warnings.add(new Issue(SERVICES, r.line(), "Service " + r.code()
                        + " lists no systems, so no incident can reach it. Add essential_systems or supporting_systems."));
            }
            if (!ok) continue;
            ExService e = byCode.get(r.code());
            if (e == null) {
                services.add(new Change<>(Action.CREATE, r, null));
                continue;
            }
            Map<String, Boolean> existingLinks = new LinkedHashMap<>();
            e.systemLinks().forEach((id, ess) -> existingLinks.put(systemCodes.get(id), ess));
            boolean same = Objects.equals(e.name(), r.name()) && eq(e.description(), r.description())
                    && e.customerFacing() == r.customerFacing() && Objects.equals(e.criticality(), r.criticality())
                    && Objects.equals(e.toleranceHours(), r.toleranceHours()) && eq(e.bcpRef(), r.bcpRef())
                    && eq(e.owner(), r.owner()) && existingLinks.equals(r.systemLinks());
            addChange(services, SERVICES, r.line(), "Service " + r.code(), same, allowUpdates, r, e.id());
        }
    }

    private <R> void addChange(List<Change<R>> list, String file, int line, String label, boolean same,
                               boolean allowUpdates, R row, long id) {
        if (same) {
            list.add(new Change<>(Action.UNCHANGED, row, id));
        } else if (allowUpdates) {
            list.add(new Change<>(Action.UPDATE, row, id));
        } else {
            errors.add(new Issue(file, line, label + " already exists with different values. Updating existing "
                    + "records is switched off on this instance (IMPORT_UPDATE_EXISTING=false); new records can be added."));
        }
    }

    static String key(String s) {
        return s == null ? "" : s.strip().toLowerCase(Locale.ROOT);
    }

    private static boolean eq(String a, String b) {
        return Objects.equals(a == null || a.isBlank() ? null : a.strip(), b == null || b.isBlank() ? null : b.strip());
    }

    private static boolean sameKey(String a, String b) {
        return key(a).equals(key(b));
    }

    // ================================================================== one row's field parsing
    private final class Field {
        private final String file;
        private final int line;
        private boolean ok = true;

        Field(String file, int line) {
            this.file = file;
            this.line = line;
        }

        boolean ok() {
            return ok;
        }

        void error(String message) {
            errors.add(new Issue(file, line, message));
            ok = false;
        }

        String text(RegisterCsv.Row r, String col, int max, boolean required) {
            String v = r.get(col);
            if (v.isEmpty()) {
                if (required) error(col + " is required.");
                return null;
            }
            if (v.length() > max) {
                error(col + " is longer than " + max + " characters.");
                return null;
            }
            return v;
        }

        String pattern(RegisterCsv.Row r, String col, Pattern p, String rule, boolean required) {
            String v = r.get(col).toUpperCase(Locale.ROOT).replaceAll("\\s+", "_");
            if (v.isEmpty()) {
                if (required) error(col + " is required.");
                return null;
            }
            if (!p.matcher(v).matches()) {
                error(col + " \"" + r.get(col) + "\" is not valid: use " + rule + ".");
                return null;
            }
            return v;
        }

        String choice(RegisterCsv.Row r, String col, Set<String> allowed, boolean required) {
            String v = r.get(col).toUpperCase(Locale.ROOT).replaceAll("[\\s-]+", "_");
            if (v.isEmpty()) {
                if (required) error(col + " is required.");
                return null;
            }
            if (!allowed.contains(v)) {
                error(col + " \"" + r.get(col) + "\" must be one of " + String.join(", ", allowed.stream().sorted().toList()) + ".");
                return null;
            }
            return v;
        }

        boolean yesNo(RegisterCsv.Row r, String col, boolean dflt) {
            String v = r.get(col).toLowerCase(Locale.ROOT);
            if (v.isEmpty()) return dflt;
            if (Set.of("yes", "y", "true", "1").contains(v)) return true;
            if (Set.of("no", "n", "false", "0").contains(v)) return false;
            error(col + " \"" + r.get(col) + "\" must be yes or no.");
            return dflt;
        }

        Integer integer(RegisterCsv.Row r, String col, int min, int max) {
            String v = r.get(col);
            if (v.isEmpty()) return null;
            try {
                int n = Integer.parseInt(v.replaceAll("\\.0+$", ""));
                if (n < min || n > max) {
                    error(col + " must be between " + min + " and " + max + ".");
                    return null;
                }
                return n;
            } catch (NumberFormatException e) {
                error(col + " \"" + v + "\" must be a whole number.");
                return null;
            }
        }

        LocalDate date(RegisterCsv.Row r, String col) {
            String v = r.get(col);
            if (v.isEmpty()) return null;
            for (DateTimeFormatter f : DATES) {
                try {
                    return LocalDate.parse(v, f);
                } catch (DateTimeParseException ignored) {
                    // try the next format
                }
            }
            error(col + " \"" + v + "\" is not a date. Use YYYY-MM-DD or DD/MM/YYYY.");
            return null;
        }

        List<String> list(RegisterCsv.Row r, String col) {
            List<String> out = new ArrayList<>();
            for (String part : r.get(col).split("[;|\\n]")) {
                String p = part.strip();
                if (!p.isEmpty() && out.stream().noneMatch(x -> key(x).equals(key(p)))) out.add(p);
            }
            return out;
        }
    }
}
