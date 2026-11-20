package com.fortivance.security;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class AdminLoginThrottleTest {

    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-11-20T06:00:00Z");
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @Test
    void locksAfterFiveFailuresAndUnlocksAfterFifteenMinutes() {
        MutableClock clock = new MutableClock();
        AdminLoginThrottle t = new AdminLoginThrottle(clock);
        for (int i = 0; i < 4; i++) {
            t.recordFailure("1.1.1.1");
        }
        assertThat(t.isLocked("1.1.1.1")).isFalse();
        t.recordFailure("1.1.1.1");
        assertThat(t.isLocked("1.1.1.1")).isTrue();
        assertThat(t.isLocked("2.2.2.2")).isFalse();

        clock.now = clock.now.plus(Duration.ofMinutes(16));
        assertThat(t.isLocked("1.1.1.1")).isFalse();
    }

    @Test
    void successClearsFailures() {
        AdminLoginThrottle t = new AdminLoginThrottle(new MutableClock());
        for (int i = 0; i < 4; i++) {
            t.recordFailure("1.1.1.1");
        }
        t.recordSuccess("1.1.1.1");
        t.recordFailure("1.1.1.1");
        assertThat(t.isLocked("1.1.1.1")).isFalse();
    }
}
