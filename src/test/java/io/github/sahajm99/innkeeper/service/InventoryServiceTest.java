package io.github.sahajm99.innkeeper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.sahajm99.innkeeper.domain.NotFoundException;
import io.github.sahajm99.innkeeper.model.Branch;
import io.github.sahajm99.innkeeper.model.InventoryItem;
import io.github.sahajm99.innkeeper.support.AbstractServiceTest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Stock. The whole point is the adjustment: it is one atomic statement with the guard in its WHERE
 * clause, so a move that would take the shelf below zero changes nothing at all rather than
 * reading, deciding and writing a number that was already stale.
 */
class InventoryServiceTest extends AbstractServiceTest {

    @Autowired InventoryService inventory;

    private Long branchId;
    private Long otherBranchId;
    private Long towelsId;
    private Long paperId;

    @BeforeEach
    void stockTheStoreRoom() {
        inTransaction(data -> {
            Branch denton = data.branch("DEN");
            Branch fortWorth = data.branch("FTW");
            branchId = denton.getId();
            otherBranchId = fortWorth.getId();
            towelsId = data.inventoryItem(denton, "Bath towels", 120, 60).getId();
            paperId = data.inventoryItem(denton, "Toilet paper", 18, 24).getId();
            data.inventoryItem(fortWorth, "Shampoo bottles", 25, 40);
            return null;
        });
    }

    @Test
    void takingStockOffTheShelfMovesTheQuantity() {
        InventoryItem adjusted = inventory.adjust(paperId, -5);

        assertThat(adjusted.getQuantity()).isEqualTo(13);
        assertThat(adjusted.getUpdatedAt()).isEqualTo(clock.instant());
        assertThat(quantityOf(paperId)).isEqualTo(13);
    }

    @Test
    void puttingStockBackMovesItTheOtherWay() {
        assertThat(inventory.adjust(paperId, 6).getQuantity()).isEqualTo(24);
        assertThat(quantityOf(paperId)).isEqualTo(24);
    }

    @Test
    void anAdjustmentThatWouldGoBelowZeroIsRefusedAndChangesNothing() {
        assertThatThrownBy(() -> inventory.adjust(paperId, -20))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Quantity cannot go below zero");

        assertThat(quantityOf(paperId)).isEqualTo(18);
    }

    @Test
    void adjustingAnItemThatIsNotThereSaysSoRatherThanBlamingTheQuantity() {
        assertThatThrownBy(() -> inventory.adjust(-1L, -1))
            .isInstanceOf(NotFoundException.class);
    }

    @Test
    void addingAnItemCreatesTheRow() {
        InventoryItem added = inventory.add(branchId, "Key cards", "Front desk", 80, 50, "each");

        assertThat(added.getId()).isNotNull();
        assertThat(added.getQuantity()).isEqualTo(80);
        assertThat(added.getUpdatedAt()).isEqualTo(clock.instant());
        assertThat(inventory.list(branchId)).extracting(InventoryItem::getName)
            .containsExactly("Bath towels", "Key cards", "Toilet paper");
    }

    @Test
    void anItemCannotStartWithLessThanNothing() {
        assertThatThrownBy(() -> inventory.add(branchId, "Key cards", "Front desk", -1, 50, "each"))
            .isInstanceOf(IllegalArgumentException.class);

        assertThat(count("inventory_item")).isEqualTo(3);
    }

    @Test
    void lowStockIsWhateverSitsAtOrBelowItsReorderLevel() {
        assertThat(inventory.lowStock(branchId)).extracting(InventoryItem::getName)
            .containsExactly("Toilet paper");
        assertThat(inventory.lowStock(null)).extracting(InventoryItem::getName)
            .containsExactly("Toilet paper", "Shampoo bottles");

        inventory.adjust(towelsId, -61);

        assertThat(inventory.lowStock(branchId)).extracting(InventoryItem::getName)
            .containsExactly("Bath towels", "Toilet paper");
    }

    @Test
    void theListForEveryBranchSpansTheBranches() {
        assertThat(inventory.list(otherBranchId)).extracting(InventoryItem::getName)
            .containsExactly("Shampoo bottles");
        assertThat(inventory.list(null)).hasSize(3);
        assertThat(inventory.list(null).get(0).getBranch().getCode())
            .as("the branch comes back with the item").isEqualTo("DEN");
    }

    private int quantityOf(Long itemId) {
        Integer quantity = jdbc.queryForObject("select quantity from inventory_item where id = ?",
            Integer.class, itemId);
        return quantity == null ? 0 : quantity;
    }
}
