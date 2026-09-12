package io.github.sahajm99.innkeeper.repository;

import java.util.Optional;

import io.github.sahajm99.innkeeper.model.Invoice;

import org.springframework.data.jpa.repository.JpaRepository;

public interface InvoiceRepository extends JpaRepository<Invoice, Long> {

    Optional<Invoice> findByBookingId(Long bookingId);
}
