package io.github.sahajm99.innkeeper.web.form;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** A new line on a branch's shelf: what it is, how much of it there is, and when to reorder. */
public class InventoryForm {

    @NotNull(message = "Choose the hotel this is held at")
    private Long branchId;

    @NotBlank(message = "Name the item")
    @Size(max = 80, message = "Keep the name to 80 characters")
    private String name;

    @NotBlank(message = "Say what kind of thing it is")
    @Size(max = 40, message = "Keep the category to 40 characters")
    private String category;

    @NotNull(message = "Enter a quantity")
    @Min(value = 0, message = "A quantity cannot be less than zero")
    private Integer quantity = 0;

    @NotNull(message = "Enter a reorder level")
    @Min(value = 0, message = "A reorder level cannot be less than zero")
    private Integer reorderLevel = 0;

    @NotBlank(message = "Say how it is counted, such as roll or each")
    @Size(max = 20, message = "Keep the unit to 20 characters")
    private String unit;

    public Long getBranchId() {
        return branchId;
    }

    public void setBranchId(Long branchId) {
        this.branchId = branchId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name == null ? null : name.trim();
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category == null ? null : category.trim();
    }

    public Integer getQuantity() {
        return quantity;
    }

    public void setQuantity(Integer quantity) {
        this.quantity = quantity;
    }

    public Integer getReorderLevel() {
        return reorderLevel;
    }

    public void setReorderLevel(Integer reorderLevel) {
        this.reorderLevel = reorderLevel;
    }

    public String getUnit() {
        return unit;
    }

    public void setUnit(String unit) {
        this.unit = unit == null ? null : unit.trim();
    }
}
