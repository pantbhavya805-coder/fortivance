package com.fortivance.dependency;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api")
public class DependencyController {

    private final ImpactService impactService;
    private final JdbcTemplate jdbc;

    public DependencyController(ImpactService impactService, JdbcTemplate jdbc) {
        this.impactService = impactService;
        this.jdbc = jdbc;
    }

    /** What breaks if this vendor has an incident? */
    @GetMapping("/impact/vendors/{vendorId}")
    public ImpactReport impact(@PathVariable long vendorId) {
        return impactService.analyse(vendorId);
    }

    /**
     * Whole dependency graph in Cytoscape.js format.
     * Edges point from the dependent to what it depends on:
     * service -> application -> vendor -> subcontractor.
     * With ?incidentVendorId=5, affected nodes carry affected=true.
     */
    @GetMapping("/dependencies/graph")
    public Map<String, Object> graph(@RequestParam(required = false) Long incidentVendorId) {
        Set<String> affected = new HashSet<>();
        if (incidentVendorId != null) {
            ImpactReport r = impactService.analyse(incidentVendorId);
            r.vendorChain().forEach(v -> affected.add("v" + v.id()));
            r.applications().forEach(a -> affected.add("a" + a.id()));
            r.services().forEach(s -> affected.add("s" + s.id()));
        }

        List<Map<String, Object>> nodes = new ArrayList<>();
        List<Map<String, Object>> edges = new ArrayList<>();

        jdbc.query("SELECT id, name, risk_tier, parent_vendor_id, outsourcing_scope FROM vendors", rs -> {
            String id = "v" + rs.getLong("id");
            boolean fourth = rs.getObject("parent_vendor_id") != null;
            nodes.add(node(id, rs.getString("name"), fourth ? "FOURTH_PARTY" : "VENDOR",
                    rs.getString("risk_tier"), affected.contains(id),
                    Map.of("outsourcingScope", rs.getString("outsourcing_scope"))));
            if (fourth) {
                String parent = "v" + rs.getLong("parent_vendor_id");
                edges.add(edge(parent, id, "SUBCONTRACTS_TO", affected.contains(parent) && affected.contains(id)));
            }
        });

        jdbc.query("SELECT id, code, name, criticality FROM applications", rs -> {
            String id = "a" + rs.getLong("id");
            nodes.add(node(id, rs.getString("code") + " · " + rs.getString("name"), "APPLICATION",
                    rs.getString("criticality"), affected.contains(id), Map.of()));
        });

        jdbc.query("SELECT id, code, name, criticality FROM business_services", rs -> {
            String id = "s" + rs.getLong("id");
            nodes.add(node(id, rs.getString("name"), "SERVICE",
                    rs.getString("criticality"), affected.contains(id), Map.of()));
        });

        jdbc.query("SELECT application_id, vendor_id, dependency_type, is_single_point FROM application_vendor_dependencies", rs -> {
            String a = "a" + rs.getLong("application_id"), v = "v" + rs.getLong("vendor_id");
            Map<String, Object> e = edge(a, v, rs.getString("dependency_type"), affected.contains(a) && affected.contains(v));
            ((Map<String, Object>) e.get("data")).put("singlePoint", rs.getBoolean("is_single_point"));
            edges.add(e);
        });

        jdbc.query("SELECT business_service_id, application_id, is_essential FROM service_application_dependencies", rs -> {
            String s = "s" + rs.getLong("business_service_id"), a = "a" + rs.getLong("application_id");
            Map<String, Object> e = edge(s, a, "USES", affected.contains(s) && affected.contains(a));
            ((Map<String, Object>) e.get("data")).put("essential", rs.getBoolean("is_essential"));
            edges.add(e);
        });

        return Map.of("nodes", nodes, "edges", edges);
    }

    private static Map<String, Object> node(String id, String label, String type, String tier,
                                            boolean affected, Map<String, Object> extra) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", id);
        data.put("label", label);
        data.put("type", type);
        data.put("tier", tier);
        data.put("affected", affected);
        data.putAll(extra);
        return Map.of("data", data);
    }

    private static Map<String, Object> edge(String source, String target, String kind, boolean affected) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", source + "-" + target + "-" + kind);
        data.put("source", source);
        data.put("target", target);
        data.put("kind", kind);
        data.put("affected", affected);
        return Map.of("data", data);
    }
}
