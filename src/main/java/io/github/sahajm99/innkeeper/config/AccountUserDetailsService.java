package io.github.sahajm99.innkeeper.config;

import io.github.sahajm99.innkeeper.model.UserAccount;
import io.github.sahajm99.innkeeper.repository.UserAccountRepository;

import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The three demo logins, read from {@code user_account}.
 *
 * <p>The role column holds GUEST, STAFF or MANAGER and becomes the single authority
 * {@code ROLE_<role>}, which is what {@code hasRole} compares against. A disabled account is
 * returned rather than hidden, so Spring Security refuses it for the reason it actually has.</p>
 */
@Service
public class AccountUserDetailsService implements UserDetailsService {

    private final UserAccountRepository accounts;

    public AccountUserDetailsService(UserAccountRepository accounts) {
        this.accounts = accounts;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) {
        UserAccount account = accounts.findByUsername(username == null ? "" : username.trim())
            .orElseThrow(() -> new UsernameNotFoundException("No account named " + username));
        return User.withUsername(account.getUsername())
            .password(account.getPasswordHash())
            .authorities("ROLE_" + account.getRole().name())
            .disabled(!account.isEnabled())
            .build();
    }
}
