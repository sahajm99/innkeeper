package io.github.sahajm99.innkeeper.repository;

import java.util.List;

import io.github.sahajm99.innkeeper.model.Fine;

import org.springframework.data.jpa.repository.JpaRepository;

public interface FineRepository extends JpaRepository<Fine, Long> {

    List<Fine> findByBookingIdOrderByIssuedAt(Long bookingId);
}
