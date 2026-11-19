package com.fortivance.evidence;

import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/evidence")
public class EvidenceController {

    private final EvidenceService service;

    public EvidenceController(EvidenceService service) {
        this.service = service;
    }

    /** Run evidence mapping on one contract or assurance report. Re-runs are free unless force=true and inputs changed. */
    @PostMapping("/documents/{id}/analyse")
    public List<Map<String, Object>> analyse(@PathVariable long id, @RequestParam(defaultValue = "false") boolean force,
                                             Principal p) {
        return service.analyse(id, force, name(p));
    }

    /** Analyse every in-scope contract and assurance report not yet analysed. */
    @PostMapping("/analyse-all")
    public List<Map<String, Object>> analyseAll(Principal p) {
        return service.analyseAll(name(p));
    }

    @GetMapping("/documents/{id}")
    public List<Map<String, Object>> document(@PathVariable long id) {
        return service.findingsForDocument(id);
    }

    /** Gap report for one vendor: Found / Partial / Missing per requirement, with citations and risk score. */
    @GetMapping("/vendors/{id}")
    public Map<String, Object> vendor(@PathVariable long id) {
        return service.vendorReport(id);
    }

    @GetMapping("/summary")
    public List<Map<String, Object>> summary() {
        return service.summary();
    }

    @PostMapping("/mappings/{id}/verify")
    public Map<String, Object> verify(@PathVariable long id, @RequestBody(required = false) Map<String, String> body, Principal p) {
        return service.verify(id, body == null ? null : body.get("comment"), name(p));
    }

    /** Body: { "status": "PARTIAL" (optional, to correct the finding), "comment": "..." }. Without status the finding is rejected. */
    @PostMapping("/mappings/{id}/dispute")
    public Map<String, Object> dispute(@PathVariable long id, @RequestBody(required = false) Map<String, String> body, Principal p) {
        return service.dispute(id, body == null ? null : body.get("status"), body == null ? null : body.get("comment"), name(p));
    }

    private static String name(Principal p) {
        return p == null ? null : p.getName();
    }
}
