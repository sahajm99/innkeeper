package io.github.sahajm99.innkeeper.repository;

import io.github.sahajm99.innkeeper.model.InvoiceLine;

import org.springframework.data.jpa.repository.JpaRepository;

/** Lines are written and read through their invoice; this exists for direct counts in tests. */
public interface InvoiceLineRepository extends JpaRepository<InvoiceLine, Long> {
}
