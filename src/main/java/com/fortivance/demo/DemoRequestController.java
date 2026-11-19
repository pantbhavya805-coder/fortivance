package com.fortivance.demo;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/** Public endpoint behind /request-demo.html. */
@RestController
@RequestMapping("/api/demo-requests")
public class DemoRequestController {

    static final Map<String, String> FIELD_MESSAGES = Map.of(
            "email", "Please enter a valid email address.",
            "name", "Please enter your name or organisation (2 to 100 characters).",
            "phone", "Phone may contain only digits, spaces and + - ( ), 7 to 20 characters.",
            "purpose", "Please tell us who you are and why you need access (50 to 1000 characters).",
            "website", "Invalid submission.");

    private final DemoRequestService service;

    public DemoRequestController(DemoRequestService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<Map<String, String>> create(@Valid @RequestBody DemoRequestForm form,
                                                      HttpServletRequest request) {
        // Behind Caddy with forward-headers-strategy=framework, getRemoteAddr() is the visitor's IP.
        DemoRequestService.SubmitResult result = service.submit(form, request.getRemoteAddr());
        return switch (result.outcome()) {
            case CREATED -> created(result.credentials());
            case IGNORED -> ResponseEntity.status(HttpStatus.CREATED).body(Map.of("status", "sent"));
            case DUPLICATE -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error",
                    "Demo access has already been issued to this email address. Please use the credentials "
                            + "from your welcome email, or reply to it if you need help."));
            case RATE_LIMITED -> ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("error", "Too many requests from your network. Please try again later."));
            case NOT_CONFIGURED -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Demo requests are not available right now. Please try again later."));
            case MAIL_FAILED -> ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error",
                    "We could not email your credentials just now. Please try again in a few minutes."));
        };
    }

    /** Credentials are shown once on screen as well as emailed; the response must not be cached. */
    private static ResponseEntity<Map<String, String>> created(DemoRequestService.Provisioned p) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("status", "sent");
        body.put("username", p.username());
        body.put("password", p.password());
        body.put("role", "ANALYST");
        body.put("accessExpiresAt", DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(p.expiresAt().atZone(DemoMailer.IST)));
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> invalid(MethodArgumentNotValidException e) {
        FieldError fe = e.getBindingResult().getFieldError();
        String field = fe != null ? fe.getField() : "form";
        return ResponseEntity.badRequest().body(Map.of(
                "error", FIELD_MESSAGES.getOrDefault(field, "Please check the form."),
                "field", field));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> unreadable() {
        return ResponseEntity.badRequest().body(Map.of("error", "Please check the form.", "field", "form"));
    }
}
