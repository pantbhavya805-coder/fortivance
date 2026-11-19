package com.fortivance.incident;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/incidents")
public class IncidentController {

    private final IncidentService service;

    public IncidentController(IncidentService service) {
        this.service = service;
    }

    /** Upload a vendor notice (.pdf, .md or .txt). Returns the full incident detail. */
    @PostMapping(consumes = "multipart/form-data")
    public Map<String, Object> upload(@RequestParam("file") MultipartFile file,
                                      @RequestParam(defaultValue = "true") boolean draftPlan,
                                      Principal principal) throws Exception {
        if (file.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Empty file");
        long id = service.ingest(file.getOriginalFilename(), file.getBytes(), name(principal), draftPlan);
        return service.detail(id);
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable long id) {
        return service.detail(id);
    }

    /** Draft (or re-draft) the response plan, e.g. after a withheld plan has been checked. */
    @PostMapping("/{id}/plan")
    public Map<String, Object> draftPlan(@PathVariable long id, Principal principal) {
        service.draftPlan(id, name(principal));
        return service.detail(id);
    }

    @PostMapping("/{id}/plan/approve")
    public Map<String, Object> approve(@PathVariable long id, @RequestBody(required = false) Map<String, String> body,
                                       Principal principal) {
        service.review(id, true, body == null ? null : body.get("comment"), name(principal));
        return service.detail(id);
    }

    @PostMapping("/{id}/plan/reject")
    public Map<String, Object> reject(@PathVariable long id, @RequestBody(required = false) Map<String, String> body,
                                      Principal principal) {
        service.review(id, false, body == null ? null : body.get("comment"), name(principal));
        return service.detail(id);
    }

    private static String name(Principal p) {
        return p == null ? null : p.getName();
    }
}
