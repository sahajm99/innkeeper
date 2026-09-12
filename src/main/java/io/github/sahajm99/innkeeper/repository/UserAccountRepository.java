package io.github.sahajm99.innkeeper.repository;

import java.util.Optional;

import io.github.sahajm99.innkeeper.model.UserAccount;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UserAccountRepository extends JpaRepository<UserAccount, Long> {

    Optional<UserAccount> findByUsername(String username);
}
