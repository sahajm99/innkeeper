package io.github.sahajm99.innkeeper.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import io.github.sahajm99.innkeeper.domain.BookingRuleException;
import io.github.sahajm99.innkeeper.domain.CancellationPolicy;
import io.github.sahajm99.innkeeper.domain.InvoiceCalculator;
import io.github.sahajm99.innkeeper.domain.NotFoundException;
import io.github.sahajm99.innkeeper.domain.RoomUnavailableException;
import io.github.sahajm99.innkeeper.model.Booking;
import io.github.sahajm99.innkeeper.model.Room;
import io.github.sahajm99.innkeeper.repository.RoomRepository;
import io.github.sahajm99.innkeeper.service.BookingService;
import io.github.sahajm99.innkeeper.service.BookingView;
import io.github.sahajm99.innkeeper.service.CreateBookingCommand;
import io.github.sahajm99.innkeeper.web.form.BookingForm;
import jakarta.validation.Valid;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.security.core.Authentication;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Booking a room, and everything the guest can do with the booking afterwards.
 *
 * <p>The price shown on the form is worked out here from the room and the branch tax rate rather
 * than carried in the request, so the number on the button is the number the server will charge.
 * Creating is a POST that redirects, and the code it made is unlocked in the session on the way
 * past, which is what lets the confirmation page open without an account.</p>
 */
@Controller
public class BookingPagesController {

    /** The actor recorded on a booking nobody signed in to make. */
    private static final String PUBLIC_ACTOR = "guest";

    private static final String LOCKED =
        "Enter the email used for the booking to open it.";

    private static final String GONE = "We couldn't find that booking.";

    /** The money a stay costs before anybody has typed a name. */
    public record Quote(int nights, BigDecimal nightlyRate, BigDecimal roomSubtotal, BigDecimal tax,
        BigDecimal total, BigDecimal taxRate, Instant cancellationDeadline) {
    }

    private final BookingService bookings;
    private final RoomRepository rooms;
    private final BookingAccess access;
    private final Dates dates;

    public BookingPagesController(BookingService bookings, RoomRepository rooms,
            BookingAccess access, Dates dates) {
        this.bookings = bookings;
        this.rooms = rooms;
        this.access = access;
        this.dates = dates;
    }

    // --- making one ---------------------------------------------------------------------------

    @GetMapping("/book")
    public String bookingForm(@ModelAttribute("booking") BookingForm form,
            @RequestParam(name = "guests", required = false) Integer guests,
            @RequestParam(name = "night", required = false) String night, Model model) {
        applyStripNight(form, night);
        applyGuests(form, guests);
        Room room = requireRoom(form.getRoomId());
        model.addAttribute("room", room);
        model.addAttribute("branch", room.getBranch());
        model.addAttribute("type", room.getRoomType());
        model.addAttribute("quote", quote(room, form));
        return "book";
    }

    @PostMapping("/book")
    public String book(@Valid @ModelAttribute("booking") BookingForm form, BindingResult binding,
            RedirectAttributes flash, Model model) {
        if (form.looksAutomated()) {
            return "redirect:/";
        }
        Room room = requireRoom(form.getRoomId());
        model.addAttribute("room", room);
        model.addAttribute("branch", room.getBranch());
        model.addAttribute("type", room.getRoomType());
        model.addAttribute("quote", quote(room, form));

        if (binding.hasErrors()) {
            return "book";
        }
        try {
            Booking made = bookings.create(new CreateBookingCommand(form.getRoomId(),
                form.getCheckIn(), form.getCheckOut(), form.getAdults(), form.getChildren(),
                form.getFirstName().trim(), form.getLastName().trim(), form.getEmail().trim(),
                form.getPhone(), form.getSpecialRequests(), PUBLIC_ACTOR));
            access.unlock(made.getConfirmationCode());
            flash.addFlashAttribute(Flash.MESSAGE,
                "Booked. Keep this code and the email you used.");
            return "redirect:/bookings/" + made.getConfirmationCode();
        } catch (BookingRuleException broken) {
            binding.rejectValue(broken.field(), "booking.invalid", broken.getMessage());
            return "book";
        } catch (RoomUnavailableException taken) {
            model.addAttribute("taken",
                "Room " + room.getRoomNumber() + " was just taken for those dates");
            return "book";
        }
    }

    // --- reading one --------------------------------------------------------------------------

    @GetMapping("/bookings/{code}")
    public String booking(@PathVariable("code") String code, Authentication authentication,
            RedirectAttributes flash, Model model) {
        Optional<BookingView> found = viewable(code, authentication, flash);
        if (found.isEmpty()) {
            return "redirect:/my-bookings?code=" + code;
        }
        model.addAttribute("booking", found.get());
        return "booking";
    }

    @GetMapping("/bookings/{code}/invoice")
    public String invoice(@PathVariable("code") String code, Authentication authentication,
            RedirectAttributes flash, Model model) {
        Optional<BookingView> found = viewable(code, authentication, flash);
        if (found.isEmpty()) {
            return "redirect:/my-bookings?code=" + code;
        }
        model.addAttribute("booking", found.get());
        return "invoice";
    }

    // --- cancelling one -----------------------------------------------------------------------

    @GetMapping("/bookings/{code}/cancel")
    public String cancelStep(@PathVariable("code") String code, Authentication authentication,
            RedirectAttributes flash, Model model) {
        Optional<BookingView> found = viewable(code, authentication, flash);
        if (found.isEmpty()) {
            return "redirect:/my-bookings?code=" + code;
        }
        BookingView booking = found.get();
        if (!booking.cancellable()) {
            flash.addFlashAttribute(Flash.ERROR, "That booking is not confirmed, so there is "
                + "nothing to cancel.");
            return "redirect:/bookings/" + booking.code();
        }
        model.addAttribute("booking", booking);
        return "booking-cancel";
    }

    @PostMapping("/bookings/{code}/cancel")
    public String cancel(@PathVariable("code") String code, Authentication authentication,
            RedirectAttributes flash) {
        Optional<BookingView> found = viewable(code, authentication, flash);
        if (found.isEmpty()) {
            return "redirect:/my-bookings?code=" + code;
        }
        Booking cancelled = bookings.cancel(found.get().code(), PUBLIC_ACTOR);
        BigDecimal fee = cancelled.getCancellationFee();
        flash.addFlashAttribute(Flash.MESSAGE, fee != null && fee.signum() > 0
            ? "Booking cancelled. A fee of " + dates.money(fee) + " is recorded on your invoice."
            : "Booking cancelled. There is nothing to pay.");
        return "redirect:/bookings/" + cancelled.getConfirmationCode();
    }

    // --- shared -------------------------------------------------------------------------------

    /**
     * The booking behind the code when the caller is allowed to see it, and an empty answer with a
     * flash message when they are not.
     *
     * <p>A code that no longer exists and a code the session has not unlocked are answered the same
     * way - back to the lookup form - because the difference between them is exactly the fact a
     * guessing visitor wants. After a nightly reset every old code is in the first group.</p>
     */
    private Optional<BookingView> viewable(String code, Authentication authentication,
            RedirectAttributes flash) {
        BookingView booking;
        try {
            booking = bookings.view(bookings.requireByCode(code));
        } catch (NotFoundException missing) {
            flash.addFlashAttribute(Flash.ERROR, GONE);
            return Optional.empty();
        }
        if (!access.canView(code, authentication, booking)) {
            flash.addFlashAttribute(Flash.ERROR, LOCKED);
            return Optional.empty();
        }
        return Optional.of(booking);
    }

    private Room requireRoom(Long roomId) {
        if (roomId == null) {
            throw new NotFoundException("No room was chosen");
        }
        return rooms.findDetailed(roomId)
            .orElseThrow(() -> new NotFoundException("No room with id " + roomId));
    }

    /** A cell of the register submitted without JavaScript books the one night it names. */
    private void applyStripNight(BookingForm form, String night) {
        if (night == null || night.isBlank() || form.getCheckIn() != null) {
            return;
        }
        LocalDate date = LocalDate.parse(night);
        form.setCheckIn(date);
        form.setCheckOut(date.plusDays(1));
    }

    /** A room link carries one guests number; the form splits it into adults and children. */
    private void applyGuests(BookingForm form, Integer guests) {
        if (guests == null || guests < 1) {
            return;
        }
        form.setAdults(Math.min(guests, 4));
        form.setChildren(0);
    }

    /**
     * What the stay costs, using the same calculator the invoice uses, so the number on the button
     * and the number on the invoice cannot drift apart.
     */
    private Quote quote(Room room, BookingForm form) {
        LocalDate checkIn = form.getCheckIn();
        LocalDate checkOut = form.getCheckOut();
        int nights = checkIn == null || checkOut == null || !checkOut.isAfter(checkIn)
            ? 0
            : (int) ChronoUnit.DAYS.between(checkIn, checkOut);
        BigDecimal taxRate = room.getBranch().getTaxRate();
        InvoiceCalculator.Result money = InvoiceCalculator.calculate(nights, room.getNightlyRate(),
            taxRate, List.of(), BigDecimal.ZERO);
        Instant deadline = checkIn == null
            ? null
            : CancellationPolicy.deadline(checkIn, room.getBranch().zone());
        return new Quote(nights, room.getNightlyRate(), money.roomSubtotal(), money.tax(),
            money.total(), taxRate, deadline);
    }
}
