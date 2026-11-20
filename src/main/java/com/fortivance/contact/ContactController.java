package com.fortivance.contact;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Public endpoint behind the "Contact us" form on the About page. */
@RestController
@RequestMapping("/api/contact")
public class ContactController {

    static final Map<String, String> FIELD_MESSAGES = Map.of(
            "name", "Please enter your name (2 to 100 characters).",
            "email", "Please enter a valid email address.",
            "topic", "Please choose what your message is about.",
            "message", "Please write a message of 10 to 3000 characters.",
            "website", "Invalid submission.");

    private final ContactService service;

    public ContactController(ContactService service) {
        this.service = service;
    }

    /** Topic options for the form, so the page and the server always agree. */
    @GetMapping("/topics")
    public List<Map<String, String>> topics() {
        return ContactService.TOPICS.entrySet().stream()
                .map(e -> Map.of("key", e.getKey(), "label", e.getValue()))
                .toList();
    }

    @PostMapping
    public ResponseEntity<Map<String, String>> submit(@Valid @RequestBody ContactRequest req, HttpServletRequest http) {
        // Behind Caddy with forward-headers-strategy=framework, getRemoteAddr() is the visitor's IP.
        return switch (service.submit(req, http.getRemoteAddr())) {
            case SENT, IGNORED -> ResponseEntity.ok(Map.of("status", "sent",
                    "message", "Thank you. Your message has been sent and we will reply shortly. "
                            + "A confirmation is on its way to your inbox."));
            case INVALID_TOPIC -> ResponseEntity.badRequest()
                    .body(Map.of("error", FIELD_MESSAGES.get("topic"), "field", "topic"));
            case RATE_LIMITED -> ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("error", "Too many messages from your network. Please try again later."));
            case NOT_CONFIGURED -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "The contact form is not available right now. Please try again later."));
            case FAILED -> ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("error", "Your message could not be sent. Please try again in a few minutes."));
        };
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> invalid(MethodArgumentNotValidException e) {
        FieldError fe = e.getBindingResult().getFieldError();
        String field = fe != null ? fe.getField() : "form";
        return ResponseEntity.badRequest().body(Map.of(
                "error", FIELD_MESSAGES.getOrDefault(field, "Please check the form."), "field", field));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> unreadable() {
        return ResponseEntity.badRequest().body(Map.of("error", "Please check the form.", "field", "form"));
    }
}
