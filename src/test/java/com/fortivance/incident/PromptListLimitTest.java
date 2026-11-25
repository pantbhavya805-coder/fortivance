package com.fortivance.incident;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fortivance.dependency.ImpactReport;
import com.fortivance.dependency.ImpactReport.*;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Large impacts are cut down for AI prompts; small ones (the demo) must reach the prompt unchanged. */
class PromptListLimitTest {

    private final ObjectMapper om = new ObjectMapper();

    private static ImpactReport report(int services, int apps) {
        List<AffectedService> s = new ArrayList<>();
        for (int i = 0; i < services; i++) {
            // Every third service is disrupted; tolerance grows with i, so urgency order is predictable.
            s.add(new AffectedService(i, String.format("SVC-%03d", i), "Service " + i, i % 2 == 0 ? "CRITICAL" : "LOW",
                    true, 1 + i, null, null, i % 3 == 0 ? "DISRUPTED" : "DEGRADED", List.of("APP")));
        }
        List<AffectedApplication> a = new ArrayList<>();
        for (int i = 0; i < apps; i++) {
            a.add(new AffectedApplication(i, String.format("APP-%03d", i), "App " + i, "HIGH", 4, 1, "V", "HOSTING", i == apps - 1, null));
        }
        VendorLink v = new VendorLink(1, "V", "HIGH", false, 0, "V");
        int disrupted = (int) s.stream().filter(x -> x.impactLevel().equals("DISRUPTED")).count();
        return new ImpactReport(v, List.of(v), a, s, new Summary(1, apps, services, disrupted, 0, services, 1, 1));
    }

    @Test
    void atOrUnderTheLimitTheListsAndFactsAreUnchanged() {
        ImpactReport r = report(IncidentService.PROMPT_LIST_LIMIT, IncidentService.PROMPT_LIST_LIMIT);
        ObjectNode facts = om.createObjectNode();
        assertThat(IncidentService.promptServices(r, facts)).isSameAs(r.services());
        assertThat(IncidentService.promptApplications(r, facts)).isSameAs(r.applications());
        assertThat(facts.isEmpty()).isTrue();
    }

    @Test
    void overTheLimitTheMostUrgentAreListedWithExactTotals() {
        ImpactReport r = report(395, 480);
        ObjectNode facts = om.createObjectNode();

        List<AffectedService> services = IncidentService.promptServices(r, facts);
        assertThat(services).hasSize(IncidentService.PROMPT_LIST_LIMIT);
        assertThat(services).allMatch(x -> x.impactLevel().equals("DISRUPTED"));
        assertThat(services.get(0).code()).isEqualTo("SVC-000");
        assertThat(facts.path("affected_services_listed").path("total").asInt()).isEqualTo(395);
        assertThat(facts.path("affected_services_listed").path("disrupted_total").asInt()).isEqualTo(132);

        List<AffectedApplication> apps = IncidentService.promptApplications(r, facts);
        assertThat(apps).hasSize(IncidentService.PROMPT_LIST_LIMIT);
        assertThat(apps.get(0).singlePointOfFailure()).isTrue();
        assertThat(facts.path("affected_applications_listed").path("total").asInt()).isEqualTo(480);
    }
}
