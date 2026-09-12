package io.github.sahajm99.innkeeper.repository;

import java.time.Instant;
import java.util.List;

import io.github.sahajm99.innkeeper.model.MaintenanceRequest;
import io.github.sahajm99.innkeeper.model.MaintenanceStatus;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MaintenanceRequestRepository extends JpaRepository<MaintenanceRequest, Long> {

    /** Priority is stored as its name, and URGENT, NORMAL, LOW sort that way descending. */
    List<MaintenanceRequest> findByBranchIdAndStatusOrderByPriorityDescCreatedAtAsc(
        Long branchId, MaintenanceStatus status);

    /**
     * The maintenance board: everything still open, plus the jobs finished since doneSince so the
     * board shows recent work. A DONE row with no completion time is kept rather than dropped, so a
     * badly written row is visible on the board instead of vanishing from it. Pass a null branch
     * for every branch.
     */
    @Query("select m from MaintenanceRequest m join fetch m.branch "
        + "left join fetch m.room left join fetch m.team "
        + "where (:branchId is null or m.branch.id = :branchId) "
        + "and (m.status <> io.github.sahajm99.innkeeper.model.MaintenanceStatus.DONE "
        + "or m.completedAt is null or m.completedAt >= :doneSince) "
        + "order by m.priority desc, m.createdAt")
    List<MaintenanceRequest> board(@Param("branchId") Long branchId, @Param("doneSince") Instant doneSince);

    long countByStatusAndBranchId(MaintenanceStatus status, Long branchId);
}
