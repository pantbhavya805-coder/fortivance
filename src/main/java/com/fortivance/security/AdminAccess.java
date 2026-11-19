package com.fortivance.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Who may open the admin panel: the permanent accounts named in {@code fortivance.admin-usernames}
 * (default: bhavya, judge). Roles cannot be used for this because every account can switch between Analyst
 * and Approver. Demo accounts are never admins, even if a name matched.
 * <p>Only the owner accounts ({@code fortivance.owner-usernames}, default: bhavya) see requesters' personal
 * details (phone number and purpose text) in the admin panel.
 */
@Component
public class AdminAccess {

    private final Set<String> usernames;
    private final Set<String> owners;

    public AdminAccess(@Value("${fortivance.admin-usernames:bhavya,judge}") String usernames,
                       @Value("${fortivance.owner-usernames:bhavya}") String owners) {
        this.usernames = parse(usernames);
        this.owners = parse(owners);
    }

    private static Set<String> parse(String csv) {
        return Arrays.stream(csv.split(","))
                .map(s -> s.trim().toLowerCase(Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    /** An admin who may also see requesters' phone numbers and purpose text. */
    public boolean isOwner(Authentication auth) {
        return isAdmin(auth) && owners.contains(((UserPrincipal) auth.getPrincipal()).getUsername().toLowerCase(Locale.ROOT));
    }

    public boolean isAdmin(UserPrincipal user) {
        return user != null
                && user.getDemoRequestId() == null
                && user.getExpiresAt() == null
                && usernames.contains(user.getUsername().toLowerCase(Locale.ROOT));
    }

    public boolean isAdmin(Authentication auth) {
        return auth != null
                && auth.isAuthenticated()
                && !(auth instanceof AnonymousAuthenticationToken)
                && auth.getPrincipal() instanceof UserPrincipal p
                && isAdmin(p);
    }
}
