package com.fortivance.contact;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Public endpoint behind the contact form on the About page. */
@RestController
@RequestMapping("/api/contact")
public class ContactController {

    private final ContactService contactService;

    public ContactController(ContactService contactService) {
        this.contactService = contactService;
    }

    @PostMapping
    public ResponseEntity<Map<String, String>> submit(@Valid @RequestBody ContactRequest req,
                                                      HttpServletRequest http) {
        // Behind Caddy with forward-headers-strategy=framework, getRemoteAddr() is the visitor's IP.
        return switch (contactService.submit(req, http.getRemoteAddr())) {
            case SENT, IGNORED -> ResponseEntity.ok(Map.of("status", "sent"));
            case RATE_LIMITED -> ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("error", "Too many messages. Please try again later."));
            case NOT_CONFIGURED -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "The contact form is not configured yet."));
            case FAILED -> ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("error", "Your message could not be sent. Please try again later."));
        };
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> invalid(MethodArgumentNotValidException e) {
        String field = e.getBindingResult().getFieldError() != null
                ? e.getBindingResult().getFieldError().getField() : "form";
        return ResponseEntity.badRequest().body(Map.of("error", "Please check the " + field + " field."));
    }
}
