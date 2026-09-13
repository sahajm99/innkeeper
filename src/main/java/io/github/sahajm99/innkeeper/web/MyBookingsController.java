package io.github.sahajm99.innkeeper.web;

import java.util.List;
import java.util.Optional;

import io.github.sahajm99.innkeeper.model.Booking;
import io.github.sahajm99.innkeeper.repository.BookingRepository;
import io.github.sahajm99.innkeeper.service.BookingService;
import io.github.sahajm99.innkeeper.service.BookingView;
import io.github.sahajm99.innkeeper.web.form.LookupForm;
import jakarta.validation.Valid;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Finding a booking again.
 *
 * <p>For a visitor this is a code and an email, checked together; a match unlocks the code in the
 * session and the confirmation page opens. The pair is submitted as a POST rather than typed into
 * the address bar, so no email address ends up in browser history or in a request log.</p>
 *
 * <p>A signed-in GUEST account skips the form: its display name is an email address, and the page
 * lists every stay booked with it.</p>
 */
@Controller
public class MyBookingsController {

    /** The one answer a wrong pair gets, whichever half was wrong. */
    static final String NO_MATCH = "We couldn't find a booking with that code and email.";

    private final BookingService bookings;
    private final BookingRepository bookingRepository;
    private final BookingAccess access;

    public MyBookingsController(BookingService bookings, BookingRepository bookingRepository,
            BookingAccess access) {
        this.bookings = bookings;
        this.bookingRepository = bookingRepository;
        this.access = access;
    }

    @GetMapping("/my-bookings")
    public String lookupForm(@ModelAttribute("lookup") LookupForm form,
            @RequestParam(name = "code", required = false) String code,
            Authentication authentication, Model model) {
        if (form.getCode() == null && code != null) {
            form.setCode(code);
        }
        addAccountBookings(authentication, model);
        return "my-bookings";
    }

    @PostMapping("/my-bookings")
    public String lookup(@Valid @ModelAttribute("lookup") LookupForm form, BindingResult binding,
            Authentication authentication, Model model) {
        if (binding.hasErrors()) {
            addAccountBookings(authentication, model);
            return "my-bookings";
        }
        Optional<Booking> found =
            bookings.findByCodeAndEmail(form.normalisedCode(), form.getEmail());
        if (found.isEmpty()) {
            model.addAttribute("noMatch", NO_MATCH);
            addAccountBookings(authentication, model);
            return "my-bookings";
        }
        access.unlock(found.get().getConfirmationCode());
        return "redirect:/bookings/" + found.get().getConfirmationCode();
    }

    /**
     * The stays a signed-in guest account has, read straight from the email its display name is.
     * Anonymous visitors get nothing here and use the form instead.
     */
    private void addAccountBookings(Authentication authentication, Model model) {
        String email = access.accountEmail(authentication);
        if (email == null || email.isBlank()) {
            return;
        }
        List<BookingView> mine = bookingRepository.findByGuestEmail(email).stream()
            .map(bookings::view)
            .toList();
        model.addAttribute("accountEmail", email);
        model.addAttribute("accountBookings", mine);
    }
}
