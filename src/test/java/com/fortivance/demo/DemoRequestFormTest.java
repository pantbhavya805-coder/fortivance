package com.fortivance.demo;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class DemoRequestFormTest {

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();
    private static final String PURPOSE = "I lead third-party risk at a bank and want to evaluate incident triage.";

    private static Set<String> invalidFields(DemoRequestForm f) {
        return VALIDATOR.validate(f).stream()
                .map(ConstraintViolation::getPropertyPath).map(Object::toString)
                .collect(Collectors.toSet());
    }

    @Test
    void validFormPasses() {
        assertThat(invalidFields(new DemoRequestForm("jane@acme.com", "Acme Bank", "", PURPOSE, ""))).isEmpty();
        assertThat(invalidFields(new DemoRequestForm("jane@acme.com", "Acme Bank", "+91 (22) 4000-1234", PURPOSE, null)))
                .isEmpty();
    }

    @Test
    void fieldsAreTrimmed() {
        DemoRequestForm f = new DemoRequestForm("  jane@acme.com ", "  Acme ", " ", "  " + PURPOSE + "  ", null);
        assertThat(f.email()).isEqualTo("jane@acme.com");
        assertThat(f.name()).isEqualTo("Acme");
        assertThat(f.phone()).isEmpty();
        assertThat(f.purpose()).isEqualTo(PURPOSE);
    }

    @Test
    void rejectsBadEmail() {
        assertThat(invalidFields(new DemoRequestForm("jane@acme", "Acme", null, PURPOSE, null))).contains("email");
        assertThat(invalidFields(new DemoRequestForm("not-an-email", "Acme", null, PURPOSE, null))).contains("email");
    }

    @Test
    void enforcesPurposeLengthAfterTrimming() {
        String padded = "x".repeat(49) + " ".repeat(20);
        assertThat(invalidFields(new DemoRequestForm("j@a.co", "Acme", null, padded, null))).contains("purpose");
        assertThat(invalidFields(new DemoRequestForm("j@a.co", "Acme", null, "x".repeat(1001), null))).contains("purpose");
        assertThat(invalidFields(new DemoRequestForm("j@a.co", "Acme", null, "x".repeat(50), null))).isEmpty();
    }

    @Test
    void rejectsBadPhoneAndShortName() {
        assertThat(invalidFields(new DemoRequestForm("j@a.co", "Acme", "abc1234", PURPOSE, null))).contains("phone");
        assertThat(invalidFields(new DemoRequestForm("j@a.co", "Acme", "12345", PURPOSE, null))).contains("phone");
        assertThat(invalidFields(new DemoRequestForm("j@a.co", "A", null, PURPOSE, null))).contains("name");
    }
}
