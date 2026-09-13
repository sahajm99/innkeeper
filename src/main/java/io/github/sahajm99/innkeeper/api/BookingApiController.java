package io.github.sahajm99.innkeeper.api;

import java.net.URI;

import io.github.sahajm99.innkeeper.api.dto.BookingDto;
import io.github.sahajm99.innkeeper.api.dto.CancelRequest;
import io.github.sahajm99.innkeeper.api.dto.CancelResponse;
import io.github.sahajm99.innkeeper.api.dto.CreateBookingRequest;
import io.github.sahajm99.innkeeper.api.dto.LookupRequest;
import io.github.sahajm99.innkeeper.domain.NotFoundException;
import io.github.sahajm99.innkeeper.model.Booking;
import io.github.sahajm99.innkeeper.model.Room;
import io.github.sahajm99.innkeeper.repository.RoomRepository;
import io.github.sahajm99.innkeeper.service.BookingService;
import io.github.sahajm99.innkeeper.service.BookingView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Booking, finding and cancelling a stay.
 *
 * <p>Reading a booking is a POST, which looks wrong until you see why: the code alone is not enough
 * to see a stay, the email has to come with it, and a guest email does not belong in a URL that
 * ends up in logs, history and referrers. So the lookup takes a body, and there is no GET by code.
 * </p>
 *
 * <p>Every refusal comes from the service, not from here: the dates, the party size, the room being
 * free and the booking being cancellable are domain rules that the booking form has to obey too.
 * </p>
 */
@RestController
@RequestMapping("/api/bookings")
@Tag(name = "Bookings", description = "Booking, finding and cancelling a stay")
public class BookingApiController {

    /** Who the booking event records when a booking arrives through the API. */
    private static final String ACTOR = "api";

    private final BookingService bookings;
    private final RoomRepository rooms;

    public BookingApiController(BookingService bookings, RoomRepository rooms) {
        this.bookings = bookings;
        this.rooms = rooms;
    }

    /**
     * Creates the stay. The {@code Location} is the page a person would open, not an API endpoint:
     * there is no GET by code to point at, and a confirmation code is something a guest follows.
     */
    @PostMapping
    @Operation(summary = "Book a room")
    public ResponseEntity<BookingDto> create(@Valid @RequestBody CreateBookingRequest request) {
        Booking booking = bookings.create(request.toCommand(ACTOR));
        BookingDto created = dtoOf(bookings.view(booking));
        return ResponseEntity.created(URI.create("/bookings/" + created.code())).body(created);
    }

    @PostMapping("/lookup")
    @Operation(summary = "Find a booking by its code and the email it was made with")
    public BookingDto lookup(@Valid @RequestBody LookupRequest request) {
        Booking booking = bookings.findByCodeAndEmail(request.code(), request.email())
            .orElseThrow(() -> new NotFoundException(
                "No booking matches that confirmation code and email"));
        return dtoOf(bookings.view(booking));
    }

    @PostMapping("/{code}/cancel")
    @Operation(summary = "Cancel a booking, returning what the cancellation cost")
    public CancelResponse cancel(@PathVariable("code") String code,
            @Valid @RequestBody CancelRequest request) {
        Booking booking = bookings.findByCodeAndEmail(code, request.email())
            .orElseThrow(() -> new NotFoundException(
                "No booking matches that confirmation code and email"));
        Booking cancelled = bookings.cancel(booking.getConfirmationCode(), ACTOR);
        return new CancelResponse(cancelled.getConfirmationCode(), cancelled.getStatus().name(),
            cancelled.getCancellationFee());
    }

    private BookingDto dtoOf(BookingView view) {
        Room room = rooms.findDetailed(view.roomId())
            .orElseThrow(() -> new NotFoundException("No room with id " + view.roomId()));
        return BookingDto.of(view, room);
    }
}
