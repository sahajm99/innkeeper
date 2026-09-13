package io.github.sahajm99.innkeeper.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import io.github.sahajm99.innkeeper.model.Complaint;
import io.github.sahajm99.innkeeper.model.ComplaintStatus;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ComplaintRepository extends JpaRepository<Complaint, Long> {

    List<Complaint> findByStatusInOrderByCreatedAtAsc(Collection<ComplaintStatus> statuses);

    long countByStatusNot(ComplaintStatus status);

    /** How many complaints are in one status; pass a null branch for every branch. */
    @Query("select count(c) from Complaint c where c.status = :status "
        + "and (:branchId is null or c.branch.id = :branchId)")
    long countByStatus(@Param("status") ComplaintStatus status, @Param("branchId") Long branchId);

    Optional<Complaint> findByTicketNumber(String ticketNumber);

    /**
     * One complaint with its branch loaded, for the thanks page, which reads the branch name after
     * the transaction has closed and would otherwise meet a lazy proxy.
     */
    @Query("select c from Complaint c join fetch c.branch where c.ticketNumber = :ticket")
    Optional<Complaint> findDetailedByTicketNumber(@Param("ticket") String ticket);
}
