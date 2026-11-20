package com.fortivance.security;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Accounts whose open sessions must end now (disabled or deleted by the admin). Checked on every request by
 * {@link DemoExpiryFilter}. In memory, which is enough for a single app instance; sessions are in memory too,
 * so a restart ends them anyway, and the login check (active / deleted) covers new sign-ins.
 */
@Component
public class AccountRevocations {

    private final Set<Long> revoked = ConcurrentHashMap.newKeySet();

    public void revoke(long userId) {
        revoked.add(userId);
    }

    public void restore(long userId) {
        revoked.remove(userId);
    }

    public boolean isRevoked(Long userId) {
        return userId != null && revoked.contains(userId);
    }
}
