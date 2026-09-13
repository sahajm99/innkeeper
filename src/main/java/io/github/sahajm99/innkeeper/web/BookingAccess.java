package io.github.sahajm99.innkeeper.web;

import java.io.Serializable;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

import io.github.sahajm99.innkeeper.model.Role;
import io.github.sahajm99.innkeeper.repository.UserAccountRepository;
import io.github.sahajm99.innkeeper.service.BookingView;

import org.springframework.context.annotation.ScopedProxyMode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.SessionScope;

/**
 * Who may open a booking page.
 *
 * <p>There is no account behind a public booking, so the confirmation page cannot be protected by
 * a role. It is protected by the session instead: booking a room, or proving the code and the email
 * together on the lookup form, unlocks that code for as long as the browser keeps its session. The
 * alternative - a signed token in the URL - would put a secret in browser history, in the request
 * log and in anything the visitor pastes.</p>
 *
 * <p>Two other people can also open it: staff, who work these bookings at the desk, and a signed-in
 * GUEST account whose display name is the email the booking was made with, which is what makes
 * {@code /my-bookings} a list rather than a form for them.</p>
 *
 * <p>Session-scoped, so the set of unlocked codes dies with the session. Sessions are in memory and
 * the instance is restarted often, so a code that was unlocked an hour ago may need the email
 * again; the lookup page says so rather than showing an error.</p>
 */
@Component
@SessionScope(proxyMode = ScopedProxyMode.TARGET_CLASS)
public class BookingAccess implements Serializable {

    private static final long serialVersionUID = 1L;

    private final Set<String> unlocked = new LinkedHashSet<>();

    private final transient UserAccountRepository accounts;

    public BookingAccess(UserAccountRepository accounts) {
        this.accounts = accounts;
    }

    /** Remembers that this browser has proved it owns the booking. */
    public void unlock(String code) {
        if (code != null && !code.isBlank()) {
            unlocked.add(normalise(code));
        }
    }

    public boolean isUnlocked(String code) {
        return code != null && unlocked.contains(normalise(code));
    }

    /**
     * Whether the caller may see this booking: they unlocked it, they are staff, or they are the
     * guest account it belongs to.
     */
    public boolean canView(String code, Authentication authentication, BookingView booking) {
        if (isUnlocked(code)) {
            return true;
        }
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        if (hasAnyRole(authentication, Role.STAFF, Role.MANAGER)) {
            return true;
        }
        return booking != null && hasAnyRole(authentication, Role.GUEST)
            && normalise(accountEmail(authentication)).equals(normalise(booking.guestEmail()));
    }

    /** The email a signed-in GUEST account books under, which is its display name. */
    public String accountEmail(Authentication authentication) {
        if (authentication == null || !hasAnyRole(authentication, Role.GUEST)) {
            return null;
        }
        return accounts.findByUsername(authentication.getName())
            .map(account -> account.getDisplayName())
            .orElse(null);
    }

    private static boolean hasAnyRole(Authentication authentication, Role... roles) {
        for (Role role : roles) {
            String authority = "ROLE_" + role.name();
            for (GrantedAuthority granted : authentication.getAuthorities()) {
                if (authority.equals(granted.getAuthority())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String normalise(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
