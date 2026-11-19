package com.fortivance.action;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/actions")
public class ActionController {

    private final ActionService actionService;

    public ActionController(ActionService actionService) {
        this.actionService = actionService;
    }

    @GetMapping
    public List<ActionRecord> list(
            @RequestParam(required = false) Long incidentId,
            @RequestParam(required = false) Long vendorId,
            @RequestParam(required = false) String status
    ) {
        return actionService.findActions(incidentId, vendorId, status);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ActionRecord> get(@PathVariable Long id) {
        return ResponseEntity.of(actionService.findById(id));
    }

    @PostMapping
    public ResponseEntity<ActionRecord> create(@RequestBody CreateActionRequest req) {
        ActionRecord created = actionService.createManualAction(req);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PatchMapping("/{id}")
    public ResponseEntity<ActionRecord> update(@PathVariable Long id, @RequestBody UpdateActionRequest req) {
        return ResponseEntity.of(actionService.updateAction(id, req));
    }
}
