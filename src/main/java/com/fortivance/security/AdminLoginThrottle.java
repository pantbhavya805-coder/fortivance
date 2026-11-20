package com.fortivance.security;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Locks the admin sign-in for a client after 5 failed attempts within 15 minutes, until the oldest of those
 * failures is 15 minutes old. A successful sign-in clears the client's failures.
 */
@Component
public class AdminLoginThrottle {

    static final int MAX_FAILURES = 5;
    static final Duration WINDOW = Duration.ofMinutes(15);

    private final Clock clock;
    private final Map<String, Deque<Instant>> failures = new ConcurrentHashMap<>();

    public AdminLoginThrottle() {
        this(Clock.systemUTC());
    }

    AdminLoginThrottle(Clock clock) {
        this.clock = clock;
    }

    public boolean isLocked(String client) {
        Deque<Instant> q = failures.get(client);
        if (q == null) {
            return false;
        }
        synchronized (q) {
            prune(q);
            return q.size() >= MAX_FAILURES;
        }
    }

    public void recordFailure(String client) {
        Deque<Instant> q = failures.computeIfAbsent(client, k -> new ArrayDeque<>());
        synchronized (q) {
            prune(q);
            q.addLast(clock.instant());
        }
        failures.values().removeIf(d -> {
            synchronized (d) {
                prune(d);
                return d.isEmpty();
            }
        });
    }

    public void recordSuccess(String client) {
        failures.remove(client);
    }

    private void prune(Deque<Instant> q) {
        Instant cutoff = clock.instant().minus(WINDOW);
        while (!q.isEmpty() && q.peekFirst().isBefore(cutoff)) {
            q.pollFirst();
        }
    }
}
