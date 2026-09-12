package io.github.sahajm99.innkeeper.seed;

import java.util.List;

/**
 * The three demo logins the sign-in page prints. The passwords are public on purpose: this is a
 * portfolio demo with fictional data and nothing worth protecting behind them.
 *
 * <p>The hashes are BCrypt strength-10 literals rather than something the seeder computes, so a
 * reset does not spend three BCrypt rounds on every run and so the seeded rows are identical
 * between runs. {@code DemoAccountsTest} checks that every literal still matches its password.</p>
 */
public final class DemoAccounts {

    /** A demo login. {@code employeeEmail} is null for the guest account, which has no employee. */
    public record Account(String username, String password, String role, String displayName,
        String employeeEmail) {
    }

    public static final Account GUEST =
        new Account("guest", "guest123", "GUEST", "guest@example.com", null);

    public static final Account STAFF =
        new Account("staff", "staff123", "STAFF", "Ben Sample", "ben.sample@example.com");

    public static final Account MANAGER =
        new Account("manager", "manager123", "MANAGER", "Ada Example", "ada.example@example.com");

    public static final String GUEST_HASH =
        "$2a$10$EHd/tJdG.TiyKraqdcJW.uQlYYkoD5ykitrrQbwOCUzGGMys/L9xy";

    public static final String STAFF_HASH =
        "$2a$10$zxvQd2tcE82Z4iQRqR9cl.zfQ4ye8ecMBpQMLvBbsIR/1zTbjOyL.";

    public static final String MANAGER_HASH =
        "$2a$10$4vF0LXq8OZ143CCPVtTROu42/wO5j7mBQQnpXcGUc8l.t2wOcJQ5m";

    /** The accounts from least to most privileged, which is the order the seeder inserts them. */
    public static final List<Account> ALL = List.of(GUEST, STAFF, MANAGER);

    private DemoAccounts() {
    }

    public static String hashFor(Account account) {
        if (GUEST.equals(account)) {
            return GUEST_HASH;
        }
        if (STAFF.equals(account)) {
            return STAFF_HASH;
        }
        if (MANAGER.equals(account)) {
            return MANAGER_HASH;
        }
        throw new IllegalArgumentException("Not a demo account: " + account);
    }
}
