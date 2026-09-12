package io.github.sahajm99.innkeeper.repository;

import io.github.sahajm99.innkeeper.model.Guest;

import org.springframework.data.jpa.repository.JpaRepository;

/** Guests are created per booking, so there is nothing to look one up by but its id. */
public interface GuestRepository extends JpaRepository<Guest, Long> {
}
