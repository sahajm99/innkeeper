package io.github.sahajm99.innkeeper.web;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

import io.github.sahajm99.innkeeper.domain.CheckInOutPolicy.CheckOutOutcome;
import io.github.sahajm99.innkeeper.domain.IllegalBookingStateException;
import io.github.sahajm99.innkeeper.domain.NotFoundException;
import io.github.sahajm99.innkeeper.model.Booking;
import io.github.sahajm99.innkeeper.model.Payment;
import io.github.sahajm99.innkeeper.model.PaymentMethod;
import io.github.sahajm99.innkeeper.repository.BookingRepository;
import io.github.sahajm99.innkeeper.service.BookingService;
import io.github.sahajm99.innkeeper.service.BookingView;
import io.github.sahajm99.innkeeper.service.ParkingUnavailableException;
import io.github.sahajm99.innkeeper.service.StaffDeskService;

import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The booking desk: finding a stay, and every button that moves it.
 *
 * <p>The page shows only the actions the status allows, so the usual answer to "can I do this now"
 * is that the button is not there. A clerk who gets there anyway - two tabs open, or a colleague
 * who checked the guest out first - is not shown an error page: the refusal comes back as the
 * flash line on the page they were already looking at, which is where the next thing they try
 * will happen.</p>
 */
@Controller
public class StaffBookingController {

    /** The answer a search that found nothing gets. */
    static final String NO_MATCHES = "No bookings match.";

    /** What two people editing one stay at the same time are told. */
    private static final String CHANGED_UNDERNEATH =
        "Somebody else changed that while you were looking at it. Try again.";

    private final StaffDeskService desk;
    private final BookingService bookings;
    private final BookingRepository bookingRepository;

    public StaffBookingController(StaffDeskService desk, BookingService bookings,
            BookingRepository bookingRepository) {
        this.desk = desk;
        this.bookings = bookings;
        this.bookingRepository = bookingRepository;
    }

    @GetMapping("/staff/bookings")
    public String search(@RequestParam(name = "q", required = false) String query, Model model) {
        List<BookingView> results = desk.search(query).stream().map(bookings::view).toList();
        model.addAttribute("q", query == null ? "" : query);
        model.addAttribute("results", results);
        model.addAttribute("searched", query != null && !query.isBlank());
        return "staff/bookings";
    }

    @GetMapping("/staff/bookings/{code}")
    public String booking(@PathVariable("code") String code, Model model) {
        BookingView view = bookings.view(require(code));
        model.addAttribute("booking", view);
        model.addAttribute("spaces", desk.freeGuestSpaces(view.branchId()));
        return "staff/booking";
    }

    @PostMapping("/staff/bookings/{code}/check-in")
    public String checkIn(@PathVariable("code") String code,
            @RequestParam(name = "parkingSpaceId", required = false) Long parkingSpaceId,
            Authentication authentication, RedirectAttributes attributes) {
        try {
            Booking booking = desk.checkIn(code, parkingSpaceId, actor(authentication));
            Flash.message(attributes,
                "Checked in. Room " + booking.getRoom().getRoomNumber() + " is occupied.");
        } catch (IllegalBookingStateException | ParkingUnavailableException
                | IllegalArgumentException refused) {
            Flash.error(attributes, refused.getMessage());
        } catch (ObjectOptimisticLockingFailureException clash) {
            Flash.error(attributes, CHANGED_UNDERNEATH);
        }
        return back(code);
    }

    @PostMapping("/staff/bookings/{code}/check-out")
    public String checkOut(@PathVariable("code") String code, Authentication authentication,
            RedirectAttributes attributes) {
        try {
            CheckOutOutcome outcome = desk.checkOut(code, actor(authentication));
            Flash.message(attributes, checkedOut(outcome));
        } catch (IllegalBookingStateException | IllegalArgumentException refused) {
            Flash.error(attributes, refused.getMessage());
        } catch (ObjectOptimisticLockingFailureException clash) {
            Flash.error(attributes, CHANGED_UNDERNEATH);
        }
        return back(code);
    }

    @PostMapping("/staff/bookings/{code}/fines")
    public String addFine(@PathVariable("code") String code,
            @RequestParam(name = "reason", required = false) String reason,
            @RequestParam(name = "amount", required = false) BigDecimal amount,
            Authentication authentication, RedirectAttributes attributes) {
        try {
            desk.addFine(code, reason, amount, actor(authentication));
            Flash.message(attributes, "Fine added to the invoice.");
        } catch (IllegalBookingStateException | IllegalArgumentException refused) {
            Flash.error(attributes, refused.getMessage());
        } catch (ObjectOptimisticLockingFailureException clash) {
            Flash.error(attributes, CHANGED_UNDERNEATH);
        }
        return back(code);
    }

    @PostMapping("/staff/bookings/{code}/payments")
    public String recordPayment(@PathVariable("code") String code,
            @RequestParam(name = "amount", required = false) BigDecimal amount,
            @RequestParam(name = "method", required = false) PaymentMethod method,
            @RequestParam(name = "reference", required = false) String reference,
            Authentication authentication, RedirectAttributes attributes) {
        try {
            Payment payment =
                desk.recordPayment(code, amount, method, reference, actor(authentication));
            Flash.message(attributes, "Payment recorded: $" + payment.getAmount().toPlainString()
                + " by " + payment.getMethod().name().toLowerCase(Locale.ROOT) + ".");
        } catch (IllegalBookingStateException | IllegalArgumentException refused) {
            Flash.error(attributes, refused.getMessage());
        } catch (ObjectOptimisticLockingFailureException clash) {
            Flash.error(attributes, CHANGED_UNDERNEATH);
        }
        return back(code);
    }

    /** "Checked out." plus whatever else the departure did to the invoice and the room. */
    private String checkedOut(CheckOutOutcome outcome) {
        StringBuilder said = new StringBuilder("Checked out.");
        if (outcome.lateNights() > 0) {
            said.append(" A late check-out fine for ")
                .append(outcome.lateNights() == 1 ? "one night" : outcome.lateNights() + " nights")
                .append(" is on the invoice.");
        }
        if (outcome.releaseNightsFrom() != null) {
            said.append(" The room is free to sell again tonight.");
        }
        return said.toString();
    }

    private Booking require(String code) {
        return bookingRepository.findByConfirmationCode(normalised(code))
            .orElseThrow(() -> new NotFoundException("No booking with code " + code));
    }

    private String normalised(String code) {
        return code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
    }

    private String back(String code) {
        return "redirect:/staff/bookings/" + normalised(code);
    }

    private String actor(Authentication authentication) {
        return authentication == null ? "staff" : authentication.getName();
    }
}
