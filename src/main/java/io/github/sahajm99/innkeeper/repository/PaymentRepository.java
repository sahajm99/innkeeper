package io.github.sahajm99.innkeeper.repository;

import java.util.List;

import io.github.sahajm99.innkeeper.model.Payment;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    List<Payment> findByInvoiceIdOrderByPaidAt(Long invoiceId);
}
