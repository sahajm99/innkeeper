package io.github.sahajm99.innkeeper.web;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import io.github.sahajm99.innkeeper.model.Booking;
import io.github.sahajm99.innkeeper.repository.BranchRepository;
import io.github.sahajm99.innkeeper.repository.InvoiceRepository;
import io.github.sahajm99.innkeeper.service.InvoiceService;
import io.github.sahajm99.innkeeper.service.ResetService;
import io.github.sahajm99.innkeeper.service.StaffDeskService;
import io.github.sahajm99.innkeeper.service.StaffDeskService.Dashboard;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The first screen of a shift.
 *
 * <p>It is ordered the way the morning is: who is leaving, who is arriving, and only then the
 * state of the house. Both ledgers carry the one button that moves the stay along, because the
 * clerk with a guest at the counter should not have to open a page to check somebody out.</p>
 */
@Controller
public class StaffDeskController {

    /** One line of the departures or arrivals ledger, read once so the template asks nothing. */
    public record StayRow(String code, String guestName, String guestEmail, String roomNumber,
        String branchName, LocalDate checkIn, LocalDate checkOut, int nights, BigDecimal balance,
        boolean overdue) {
    }

    private final StaffDeskService desk;
    private final InvoiceRepository invoices;
    private final InvoiceService invoiceService;
    private final ResetService reset;
    private final BranchRepository branches;

    public StaffDeskController(StaffDeskService desk, InvoiceRepository invoices,
            InvoiceService invoiceService, ResetService reset, BranchRepository branches) {
        this.desk = desk;
        this.invoices = invoices;
        this.invoiceService = invoiceService;
        this.reset = reset;
        this.branches = branches;
    }

    @GetMapping("/staff")
    public String desk(@RequestParam(name = "branchId", required = false) String branchParam,
            Authentication authentication, Model model) {
        Long branchId = StaffAdvice.branchScope(branchParam, defaultBranch(authentication));
        Dashboard dashboard = desk.dashboard(branchId);

        model.addAttribute("dashboard", dashboard);
        model.addAttribute("departures", rows(dashboard.departures(), dashboard.today()));
        model.addAttribute("arrivals", rows(dashboard.arrivals(), dashboard.today()));
        model.addAttribute("branchId", branchId);
        model.addAttribute("scope", scopeName(branchId));
        return "staff/desk";
    }

    /**
     * Throws the demo data away and seeds it again. Managers only, which the security chain
     * enforces rather than this method: a clerk never sees the button and cannot reach the path.
     */
    @PostMapping("/staff/reset")
    public String resetDemoData(Authentication authentication, RedirectAttributes attributes) {
        ResetService.Outcome outcome = reset.reset(actor(authentication));
        if (outcome.performed()) {
            Flash.message(attributes,
                "Demo data reset. Every booking, job and complaint is back to its seeded state.");
        } else if (ResetService.BUSY.equals(outcome.reason())) {
            Flash.error(attributes, "A reset is already running. Try again in a moment.");
        } else {
            Flash.error(attributes, "Nothing was reset.");
        }
        return "redirect:/staff";
    }

    private List<StayRow> rows(List<Booking> bookings, LocalDate today) {
        return bookings.stream().map(booking -> new StayRow(
            booking.getConfirmationCode(),
            booking.getGuest().getFirstName() + " " + booking.getGuest().getLastName(),
            booking.getGuest().getEmail(),
            booking.getRoom().getRoomNumber(),
            booking.getRoom().getBranch().getName(),
            booking.getCheckInDate(),
            booking.getCheckOutDate(),
            (int) booking.stay().nights(),
            balanceOf(booking),
            booking.getCheckOutDate().isBefore(today))).toList();
    }

    /** What the stay still owes, or null when it has no invoice yet. */
    private BigDecimal balanceOf(Booking booking) {
        return invoices.findByBookingId(booking.getId()).map(invoiceService::balance).orElse(null);
    }

    private String scopeName(Long branchId) {
        if (branchId == null) {
            return StaffAdvice.ALL_BRANCHES;
        }
        return branches.findById(branchId).map(branch -> branch.getName())
            .orElse(StaffAdvice.ALL_BRANCHES);
    }

    private Long defaultBranch(Authentication authentication) {
        return desk.branchForUsername(actor(authentication));
    }

    private String actor(Authentication authentication) {
        return authentication == null ? "staff" : authentication.getName();
    }
}
