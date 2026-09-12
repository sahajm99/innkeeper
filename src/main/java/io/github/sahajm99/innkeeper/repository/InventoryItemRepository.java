package io.github.sahajm99.innkeeper.repository;

import java.time.Instant;
import java.util.List;

import io.github.sahajm99.innkeeper.model.InventoryItem;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InventoryItemRepository extends JpaRepository<InventoryItem, Long> {

    List<InventoryItem> findByBranchIdOrderByName(Long branchId);

    @Query("select i from InventoryItem i join fetch i.branch "
        + "where i.quantity <= i.reorderLevel and (:branchId is null or i.branch.id = :branchId) "
        + "order by i.branch.name, i.name")
    List<InventoryItem> lowStock(@Param("branchId") Long branchId);

    /**
     * Moves stock by delta in one statement, so two clerks adjusting at once cannot lose an
     * update. Returns 0 when the item is gone or the move would take the quantity below zero.
     */
    @Modifying
    @Query("update InventoryItem i set i.quantity = i.quantity + :delta, i.updatedAt = :now "
        + "where i.id = :id and i.quantity + :delta >= 0")
    int adjust(@Param("id") Long id, @Param("delta") int delta, @Param("now") Instant now);
}
