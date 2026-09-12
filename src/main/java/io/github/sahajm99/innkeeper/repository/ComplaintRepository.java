package io.github.sahajm99.innkeeper.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import io.github.sahajm99.innkeeper.model.Complaint;
import io.github.sahajm99.innkeeper.model.ComplaintStatus;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ComplaintRepository extends JpaRepository<Complaint, Long> {

    List<Complaint> findByStatusInOrderByCreatedAtAsc(Collection<ComplaintStatus> statuses);

    long countByStatusNot(ComplaintStatus status);

    Optional<Complaint> findByTicketNumber(String ticketNumber);
}
