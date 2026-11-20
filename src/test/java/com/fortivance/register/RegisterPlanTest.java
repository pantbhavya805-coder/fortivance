package com.fortivance.register;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RegisterPlanTest {

    private static final Path TEMPLATES = Path.of("src/main/resources/static/templates");

    /** A register like the demo: NimbusCloud (id 1) hosts CBS (id 1); CBS is essential to SVC-PAY. */
    private static RegisterPlan.Snapshot demo() {
        var nimbus = new RegisterPlan.ExVendor(1, "NimbusCloud Infra Pvt Ltd", null, "CLOUD_HOSTING", "IaaS hosting",
                "CRITICAL", true, "FINANCIAL", "IN_SCOPE", null, "nimbuscloud.example", null, null, null, null);
        var secure = new RegisterPlan.ExVendor(5, "SecureVault Datacentres Pvt Ltd", 1L, "DATA_CENTRE", null,
                "CRITICAL", false, "NONE", "IN_SCOPE", null, null, null, null, null, null);
        var cbs = new RegisterPlan.ExSystem(1, "CBS", "Core Banking System", null, "CRITICAL", null, 2, 15, Map.of(1L, false));
        var cne = new RegisterPlan.ExSystem(8, "CNE", "Customer Notification Engine", null, "CRITICAL", null, 1, 5, Map.of());
        var pay = new RegisterPlan.ExService(2, "SVC-PAY", "Digital Payments", null, true, "CRITICAL", 2, "BCP-02", null, Map.of(1L, true));
        return new RegisterPlan.Snapshot(List.of(nimbus, secure), List.of(cbs, cne), List.of(pay));
    }

    private static RegisterCsv.Table csv(String text) throws Exception {
        return RegisterCsv.parse(text, 2000);
    }

    private static RegisterCsv.Table template(String name) throws Exception {
        return csv(Files.readString(TEMPLATES.resolve(name)));
    }

    @Test
    void shippedTemplatesImportCleanlyIntoTheDemoRegister() throws Exception {
        RegisterPlan p = RegisterPlan.build(demo(), template("vendors-template.csv"), template("systems-template.csv"),
                template("services-template.csv"), false);
        assertThat(p.errors).isEmpty();
        assertThat(p.count(p.vendors, RegisterPlan.Action.CREATE)).isEqualTo(2);
        assertThat(p.count(p.systems, RegisterPlan.Action.CREATE)).isEqualTo(1);
        assertThat(p.count(p.services, RegisterPlan.Action.CREATE)).isEqualTo(1);
        assertThat(p.systems.get(0).row().vendorLinks())
                .containsEntry("LedgerLine Analytics Pvt Ltd", true).containsEntry("NimbusCloud Infra Pvt Ltd", false);
        assertThat(p.services.get(0).row().systemLinks()).containsEntry("FRM", true).containsEntry("CNE", false);
    }

    @Test
    void excelStyleFilesAreRead() throws Exception {
        // BOM, semicolon delimiter, CRLF, quoted cell with a delimiter and an escaped quote, DD/MM/YYYY date.
        String text = "﻿Name;Service Category;Risk Tier;Data Access;Contract Start\r\n"
                + "\"Acme; \"\"Payments\"\"\";payments;high;Customer PII;01/04/2026\r\n;;;;\r\n";
        RegisterPlan p = RegisterPlan.build(demo(), csv(text), null, null, false);
        assertThat(p.errors).isEmpty();
        RegisterPlan.VendorRow r = p.vendors.get(0).row();
        assertThat(r.name()).isEqualTo("Acme; \"Payments\"");
        assertThat(r.tier()).isEqualTo("HIGH");
        assertThat(r.dataAccess()).isEqualTo("CUSTOMER_PII");
        assertThat(r.contractStart()).hasToString("2026-04-01");
    }

    @Test
    void everyProblemIsReportedWithItsLine() throws Exception {
        String vendors = "name,service_category,risk_tier,data_access,parent_vendor,contract_start\n"
                + "Bad Tier Ltd,PAYMENTS,SEVERE,FINANCIAL,,\n"
                + "Orphan Ltd,PAYMENTS,HIGH,NONE,Nobody Ltd,\n"
                + "Deep Ltd,PAYMENTS,HIGH,NONE,SecureVault Datacentres Pvt Ltd,\n"
                + "Bad Date Ltd,PAYMENTS,HIGH,NONE,,31/02/2026\n";
        String systems = "code,name,criticality,vendors,single_point_vendors\n"
                + "X1,Thing,HIGH,Unknown Vendor,\n"
                + "X2,Thing,HIGH,NimbusCloud Infra Pvt Ltd,Other Ltd\n";
        String services = "code,name,criticality,essential_systems,supporting_systems\n"
                + "SVC-X,Thing,HIGH,ZZZ,\n"
                + "SVC-Y,Thing,HIGH,CBS,CBS\n";
        RegisterPlan p = RegisterPlan.build(demo(), csv(vendors), csv(systems), csv(services), false);
        assertThat(p.errors).extracting(i -> i.file() + ":" + i.line()).containsExactly(
                "vendors:2", "vendors:5", "systems:3", "services:3", "vendors:3", "vendors:4", "systems:2", "services:2");
        assertThat(p.errors.get(0).message()).contains("risk_tier").contains("CRITICAL, HIGH, LOW, MEDIUM");
        assertThat(p.errors.get(5).message()).contains("itself a subcontractor");
    }

    @Test
    void existingRecordsAreUnchangedOrUpdatedOnlyWhenAllowed() throws Exception {
        String same = "code,name,criticality,rto_hours,rpo_minutes,vendors\nCBS,Core Banking System,CRITICAL,2,15,nimbuscloud infra pvt ltd\n";
        assertThat(RegisterPlan.build(demo(), null, csv(same), null, false).systems.get(0).action())
                .isEqualTo(RegisterPlan.Action.UNCHANGED);

        String changed = same.replace("2,15", "4,15");
        RegisterPlan blocked = RegisterPlan.build(demo(), null, csv(changed), null, false);
        assertThat(blocked.errors.get(0).message()).contains("IMPORT_UPDATE_EXISTING=false");
        assertThat(RegisterPlan.build(demo(), null, csv(changed), null, true).systems.get(0).action())
                .isEqualTo(RegisterPlan.Action.UPDATE);
    }

    @Test
    void workbooksAndBrokenFilesAreRejected() {
        assertThatThrownBy(() -> csv("name\n\"unterminated\n")).hasMessageContaining("never closed");
        assertThatThrownBy(() -> csv("   ")).hasMessageContaining("empty");
        StringBuilder big = new StringBuilder("name\n");
        for (int i = 0; i < 2001; i++) big.append("v").append(i).append('\n');
        assertThatThrownBy(() -> csv(big.toString())).hasMessageContaining("more than 2000");
    }

    @Test
    void aFileInTheWrongBoxIsRejected() throws Exception {
        // The services template has code, name and criticality, the systems file's required columns.
        RegisterPlan p = RegisterPlan.build(demo(), null, template("services-template.csv"), null, false);
        assertThat(p.errors).singleElement().satisfies(i -> {
            assertThat(i.file()).isEqualTo("systems");
            assertThat(i.message()).contains("looks like the services file").contains("box 3");
        });
        assertThat(p.systems).isEmpty();

        RegisterPlan q = RegisterPlan.build(demo(), template("systems-template.csv"), null, null, false);
        assertThat(q.errors.get(0).message()).contains("looks like the systems file").contains("box 2");
    }

    @Test
    void missingColumnsStopTheFile() throws Exception {
        RegisterPlan p = RegisterPlan.build(demo(), csv("name,risk_tier\nA,HIGH\n"), null, null, false);
        assertThat(p.errors).extracting(RegisterPlan.Issue::message)
                .anyMatch(m -> m.contains("service_category")).anyMatch(m -> m.contains("data_access"));
        assertThat(p.vendors).isEmpty();
    }
}
