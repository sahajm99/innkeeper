package io.github.sahajm99.innkeeper.service;

import java.time.Clock;
import java.util.List;

import io.github.sahajm99.innkeeper.domain.NotFoundException;
import io.github.sahajm99.innkeeper.model.Branch;
import io.github.sahajm99.innkeeper.model.InventoryItem;
import io.github.sahajm99.innkeeper.repository.BranchRepository;
import io.github.sahajm99.innkeeper.repository.InventoryItemRepository;

import org.hibernate.Hibernate;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The store room.
 *
 * <p>An adjustment is deliberately not read-decide-write: it is the single statement
 * {@code InventoryItemRepository.adjust}, whose WHERE clause carries the rule. Two clerks handing
 * out the last towels at once therefore cannot both pass a check against the same stale number -
 * the second statement simply matches no row, and that is what "cannot go below zero" means
 * here.</p>
 */
@Service
public class InventoryService {

    private final InventoryItemRepository items;
    private final BranchRepository branches;
    private final Clock clock;

    public InventoryService(InventoryItemRepository items, BranchRepository branches, Clock clock) {
        this.items = items;
        this.branches = branches;
        this.clock = clock;
    }

    /** Everything a branch holds, by name; a null branch is every branch, by branch then name. */
    @Transactional(readOnly = true)
    public List<InventoryItem> list(Long branchId) {
        List<InventoryItem> held = branchId == null
            ? items.findAll(Sort.by("branch.name", "name"))
            : items.findByBranchIdOrderByName(branchId);
        held.forEach(item -> Hibernate.initialize(item.getBranch()));
        return held;
    }

    /**
     * Moves the quantity by delta and returns the item as it now stands.
     *
     * @throws IllegalArgumentException the move would take the shelf below zero
     * @throws NotFoundException there is no such item
     */
    @Transactional
    public InventoryItem adjust(Long id, int delta) {
        if (items.adjust(id, delta, clock.instant()) == 0) {
            if (!items.existsById(id)) {
                throw new NotFoundException("No inventory item with id " + id);
            }
            throw new IllegalArgumentException("Quantity cannot go below zero");
        }
        // The adjustment was a bulk update, so the row has to be read back rather than remembered.
        InventoryItem item = items.findById(id)
            .orElseThrow(() -> new NotFoundException("No inventory item with id " + id));
        Hibernate.initialize(item.getBranch());
        return item;
    }

    /**
     * Puts a new line on the shelf.
     *
     * @throws IllegalArgumentException a negative quantity or reorder level
     * @throws NotFoundException there is no such branch
     */
    @Transactional
    public InventoryItem add(Long branchId, String name, String category, int quantity,
            int reorderLevel, String unit) {
        Branch branch = branches.findById(branchId)
            .orElseThrow(() -> new NotFoundException("No branch with id " + branchId));
        if (quantity < 0) {
            throw new IllegalArgumentException("Quantity cannot be less than zero");
        }
        if (reorderLevel < 0) {
            throw new IllegalArgumentException("A reorder level cannot be less than zero");
        }

        InventoryItem item = new InventoryItem();
        item.setBranch(branch);
        item.setName(name);
        item.setCategory(category);
        item.setQuantity(quantity);
        item.setReorderLevel(reorderLevel);
        item.setUnit(unit);
        item.setUpdatedAt(clock.instant());
        return items.save(item);
    }

    /** What to order: everything at or below its reorder level. A null branch is every branch. */
    @Transactional(readOnly = true)
    public List<InventoryItem> lowStock(Long branchId) {
        return items.lowStock(branchId);
    }
}
