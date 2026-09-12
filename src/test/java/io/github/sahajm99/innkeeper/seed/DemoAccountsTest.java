package io.github.sahajm99.innkeeper.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.sahajm99.innkeeper.seed.DemoAccounts.Account;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * The demo hashes are literals in the source, so the only thing that can go wrong with them is a
 * paste error. These tests are the guard against one.
 */
class DemoAccountsTest {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @Test
    void everyStoredHashMatchesItsDemoPassword() {
        for (Account account : DemoAccounts.ALL) {
            assertThat(encoder.matches(account.password(), DemoAccounts.hashFor(account)))
                .as("hash of %s", account.username())
                .isTrue();
        }
    }

    @Test
    void theHashesAreBcryptAtStrengthTen() {
        assertThat(DemoAccounts.ALL)
            .extracting(DemoAccounts::hashFor)
            .allSatisfy(hash -> assertThat(hash).startsWith("$2a$10$").hasSize(60));
    }

    @Test
    void hashForReturnsTheHashOfEachAccount() {
        assertThat(DemoAccounts.hashFor(DemoAccounts.GUEST)).isEqualTo(DemoAccounts.GUEST_HASH);
        assertThat(DemoAccounts.hashFor(DemoAccounts.STAFF)).isEqualTo(DemoAccounts.STAFF_HASH);
        assertThat(DemoAccounts.hashFor(DemoAccounts.MANAGER)).isEqualTo(DemoAccounts.MANAGER_HASH);
    }

    @Test
    void aWrongPasswordDoesNotMatchAnyHash() {
        assertThat(encoder.matches("guest124", DemoAccounts.GUEST_HASH)).isFalse();
        assertThat(encoder.matches("guest123", DemoAccounts.STAFF_HASH)).isFalse();
        assertThat(encoder.matches("staff123", DemoAccounts.MANAGER_HASH)).isFalse();
    }

    @Test
    void theAccountsAreTheThreeFictionalDemoLogins() {
        assertThat(DemoAccounts.ALL)
            .extracting(Account::username, Account::password, Account::role)
            .containsExactly(
                tuple("guest", "guest123", "GUEST"),
                tuple("staff", "staff123", "STAFF"),
                tuple("manager", "manager123", "MANAGER"));
        assertThat(DemoAccounts.GUEST.employeeEmail()).isNull();
        assertThat(DemoAccounts.STAFF.employeeEmail()).isEqualTo("ben.sample@example.com");
        assertThat(DemoAccounts.MANAGER.employeeEmail()).isEqualTo("ada.example@example.com");
    }
}
