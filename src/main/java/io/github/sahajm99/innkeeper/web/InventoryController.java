package io.github.sahajm99.innkeeper.web;

import java.util.List;

import io.github.sahajm99.innkeeper.model.Branch;
import io.github.sahajm99.innkeeper.model.InventoryItem;
import io.github.sahajm99.innkeeper.repository.BranchRepository;
import io.github.sahajm99.innkeeper.service.InventoryService;
import io.github.sahajm99.innkeeper.service.StaffDeskService;
import io.github.sahajm99.innkeeper.web.form.InventoryForm;
import jakarta.validation.Valid;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * What the linen cupboard holds.
 *
 * <p>Stock moves in small amounts all day, so the adjustment is a button on the row rather than a
 * page of its own, and the shelf that has run low is marked on the row rather than hidden behind a
 * filter. The database refuses a move that would take a count below zero, and the refusal is shown
 * as it comes back, because "minus three towels" is a mistake worth naming.</p>
 */
@Controller
public class InventoryController {

    private final InventoryService inventory;
    private final StaffDeskService desk;
    private final BranchRepository branches;

    public InventoryController(InventoryService inventory, StaffDeskService desk,
            BranchRepository branches) {
        this.inventory = inventory;
        this.desk = desk;
        this.branches = branches;
    }

    @GetMapping("/staff/inventory")
    public String list(@RequestParam(name = "branchId", required = false) String branchParam,
            @ModelAttribute("item") InventoryForm form, Authentication authentication,
            Model model) {
        return render(scope(branchParam, authentication), form, model);
    }

    @PostMapping("/staff/inventory/{id}/adjust")
    public String adjust(@PathVariable("id") Long id,
            @RequestParam(name = "delta", defaultValue = "0") int delta,
            @RequestParam(name = "sign", defaultValue = "1") int sign,
            @RequestParam(name = "scope", required = false) String branchParam,
            Authentication authentication, RedirectAttributes attributes) {
        int move = delta * (sign < 0 ? -1 : 1);
        try {
            InventoryItem item = inventory.adjust(id, move);
            Flash.message(attributes, item.getName() + ": " + item.getQuantity() + " "
                + item.getUnit() + " on the shelf.");
        } catch (IllegalArgumentException refused) {
            Flash.error(attributes, refused.getMessage());
        }
        return back(scope(branchParam, authentication));
    }

    @PostMapping("/staff/inventory")
    public String add(@RequestParam(name = "scope", required = false) String branchParam,
            @Valid @ModelAttribute("item") InventoryForm form, BindingResult binding,
            Authentication authentication, Model model, RedirectAttributes attributes) {
        Long branchId = scope(branchParam, authentication);
        if (!binding.hasErrors()) {
            try {
                inventory.add(form.getBranchId(), form.getName(), form.getCategory(),
                    form.getQuantity(), form.getReorderLevel(), form.getUnit());
                Flash.message(attributes, form.getName() + " added to the shelf.");
                return back(branchId);
            } catch (DataIntegrityViolationException duplicate) {
                binding.rejectValue("name", "duplicate",
                    "That hotel already holds an item with this name");
            } catch (IllegalArgumentException refused) {
                binding.rejectValue("quantity", "refused", refused.getMessage());
            }
        }
        return render(branchId, form, model);
    }

    private String render(Long branchId, InventoryForm form, Model model) {
        if (form.getBranchId() == null) {
            form.setBranchId(branchId);
        }
        List<InventoryItem> held = inventory.list(branchId);
        model.addAttribute("branchId", branchId);
        model.addAttribute("scope", scopeName(branchId));
        model.addAttribute("items", held);
        model.addAttribute("lowCount", held.stream()
            .filter(item -> item.getQuantity() <= item.getReorderLevel()).count());
        return "staff/inventory";
    }

    private String scopeName(Long branchId) {
        if (branchId == null) {
            return StaffAdvice.ALL_BRANCHES;
        }
        return branches.findById(branchId).map(Branch::getName).orElse(StaffAdvice.ALL_BRANCHES);
    }

    private Long scope(String branchParam, Authentication authentication) {
        return StaffAdvice.branchScope(branchParam, desk.branchForUsername(actor(authentication)));
    }

    private String back(Long branchId) {
        return "redirect:/staff/inventory" + StaffAdvice.carry(branchId);
    }

    private String actor(Authentication authentication) {
        return authentication == null ? "staff" : authentication.getName();
    }
}
