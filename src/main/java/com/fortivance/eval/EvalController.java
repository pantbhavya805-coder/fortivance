package com.fortivance.eval;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/eval")
public class EvalController {

    private final EvalService service;

    public EvalController(EvalService service) {
        this.service = service;
    }

    /** Gold-label comparison of stored AI outputs plus usage, cost and latency. No Claude calls. */
    @GetMapping("/report")
    public Map<String, Object> report() {
        return service.report();
    }
}
