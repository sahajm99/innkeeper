package io.github.sahajm99.innkeeper.web;

import java.util.List;

import io.github.sahajm99.innkeeper.model.Branch;
import io.github.sahajm99.innkeeper.repository.BranchRepository;

import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * The three things every staff page needs and none of them should have to remember.
 *
 * <p>A staff page is wider than a reading page, prints no guest email in full, and can be pointed
 * at one branch or at all of them. Putting the width, the masking rule and the branch list here
 * means a new staff page gets all three by existing, and that no page can accidentally render a
 * guest's address by forgetting to ask for the masker.</p>
 */
@ControllerAdvice(assignableTypes = {
    StaffDeskController.class,
    StaffBookingController.class,
    MaintenanceController.class,
    InventoryController.class,
    StaffComplaintController.class,
    EmployeeController.class
})
public class StaffAdvice {

    /** What the branch switcher calls no branch at all. */
    public static final String ALL_BRANCHES = "All branches";

    private final BranchRepository branches;

    public StaffAdvice(BranchRepository branches) {
        this.branches = branches;
    }

    /** Staff pages run to 88 rem, so the header and footer have to as well. */
    @ModelAttribute("shell")
    public String shell() {
        return "wide";
    }

    @ModelAttribute("mask")
    public Masking mask() {
        return Masking.forPages();
    }

    @ModelAttribute("branchList")
    public List<Branch> branchList() {
        return branches.findAllByOrderByName();
    }

    /**
     * Which branch a staff page is looking at.
     *
     * <p>Three answers, not two: no parameter at all means "wherever you work", which is the
     * clerk's own branch and nothing for a manager; an empty parameter is the switcher's
     * "{@value #ALL_BRANCHES}" and means every branch; anything else is the branch chosen. A
     * parameter that is not a number is treated as no choice rather than as an error, because a
     * hand-edited query string should not be able to break a page.</p>
     */
    public static Long branchScope(String parameter, Long fallback) {
        if (parameter == null) {
            return fallback;
        }
        String chosen = parameter.trim();
        if (chosen.isEmpty()) {
            return null;
        }
        try {
            return Long.valueOf(chosen);
        } catch (NumberFormatException notANumber) {
            return fallback;
        }
    }

    /** The query string that keeps the chosen branch across a redirect. */
    public static String carry(Long branchId) {
        return branchId == null ? "?branchId=" : "?branchId=" + branchId;
    }
}
