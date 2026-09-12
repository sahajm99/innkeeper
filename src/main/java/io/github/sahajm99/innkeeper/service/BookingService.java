package io.github.sahajm99.innkeeper.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Random;

import io.github.sahajm99.innkeeper.domain.BookingStatus;
import io.github.sahajm99.innkeeper.domain.BranchDates;
import io.github.sahajm99.innkeeper.domain.CancellationPolicy;
import io.github.sahajm99.innkeeper.domain.ConfirmationCodes;
import io.github.sahajm99.innkeeper.domain.NotFoundException;
import io.github.sahajm99.innkeeper.domain.OccupancyRule;
import io.github.sahajm99.innkeeper.domain.RoomUnavailableException;
import io.github.sahajm99.innkeeper.domain.StayPeriod;
import io.github.sahajm99.innkeeper.model.Booking;
import io.github.sahajm99.innkeeper.model.BookingEvent;
import io.github.sahajm99.innkeeper.model.BookingEventType;
import io.github.sahajm99.innkeeper.model.Branch;
import io.github.sahajm99.innkeeper.model.Guest;
import io.github.sahajm99.innkeeper.model.Invoice;
import io.github.sahajm99.innkeeper.model.Room;
import io.github.sahajm99.innkeeper.model.RoomNight;
import io.github.sahajm99.innkeeper.model.RoomStatus;
import io.github.sahajm99.innkeeper.repository.BookingEventRepository;
import io.github.sahajm99.innkeeper.repository.BookingRepository;
import io.github.sahajm99.innkeeper.repository.FineRepository;
import io.github.sahajm99.innkeeper.repository.GuestRepository;
import io.github.sahajm99.innkeeper.repository.InvoiceRepository;
import io.github.sahajm99.innkeeper.repository.ParkingSpaceRepository;
import io.github.sahajm99.innkeeper.repository.PaymentRepository;
import io.github.sahajm99.innkeeper.repository.RoomNightRepository;
import io.github.sahajm99.innkeeper.repository.RoomRepository;

import org.hibernate.Hibernate;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Creating, finding and cancelling a stay.
 *
 * <p>The interesting part is what stops two guests holding one room on one night, and it is not
 * this class: it is {@code UNIQUE (room_id, night_date)}. Checking availability and then inserting
 * is a race no amount of application code closes, so the check here is only there to give an
 * ordinary request a civil answer, and the guarantee comes from the insert failing. That failure
 * arrives as a {@code DataIntegrityViolationException}, and it has to be read outside the
 * transaction rather than inside it, because only then is the loser's work - the guest row
 * included - actually gone.</p>
 */
@Service
public class BookingService {

    /** How many confirmation codes to draw before giving up; a collision is already a long shot. */
    public static final int CODE_ATTEMPTS = 3;

    /** Substrings, not whole names: H2 reports {@code public.uq_room_night_index_4}. */
    private static final String ROOM_NIGHT_CONSTRAINT = "uq_room_night";
    private static final String BOOKING_CODE_CONSTRAINT = "uq_booking_code";

    private static final BigDecimal ZERO_MONEY = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    private static final String DEFAULT_ACTOR = "guest";

    private final BookingRepository bookings;
    private final RoomRepository rooms;
    private final RoomNightRepository roomNights;
    private final GuestRepository guests;
    private final BookingEventRepository events;
    private final InvoiceRepository invoices;
    private final PaymentRepository payments;
    private final FineRepository fines;
    private final ParkingSpaceRepository parkingSpaces;
    private final InvoiceService invoiceService;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final Random random;

    public BookingService(BookingRepository bookings, RoomRepository rooms,
            RoomNightRepository roomNights, GuestRepository guests, BookingEventRepository events,
            InvoiceRepository invoices, PaymentRepository payments, FineRepository fines,
            ParkingSpaceRepository parkingSpaces, InvoiceService invoiceService,
            PlatformTransactionManager transactionManager, Clock clock,
            ObjectProvider<Random> codeSource) {
        this.bookings = bookings;
        this.rooms = rooms;
        this.roomNights = roomNights;
        this.guests = guests;
        this.events = events;
        this.invoices = invoices;
        this.payments = payments;
        this.fines = fines;
        this.parkingSpaces = parkingSpaces;
        this.invoiceService = invoiceService;
        this.transactions = requiresNew(transactionManager);
        this.clock = clock;
        this.random = codeSource.getIfAvailable(SecureRandom::new);
    }

    /**
     * A template that always starts a transaction of its own, suspending one the caller already
     * has. The unit of work has to be able to roll back alone: on plain REQUIRED propagation a
     * loser of a race would mark the caller's transaction rollback-only, and the caller would get
     * an {@code UnexpectedRollbackException} at commit instead of the refusal it already handled.
     */
    private static TransactionTemplate requiresNew(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }

    // --- creating -----------------------------------------------------------------------------

    /**
     * Books the room and returns the stay, or refuses with the reason.
     *
     * <p>Deliberately not {@code @Transactional}. The unit of work runs inside
     * {@link TransactionTemplate}, and the constraint violation that decides a race only surfaces
     * once that transaction has rolled back, so it is caught out here where the rollback has
     * already happened. A clash on the room nights means the room went to somebody else; a clash
     * on the confirmation code means nothing more than bad luck, so another code is drawn.</p>
     *
     * @throws io.github.sahajm99.innkeeper.domain.BookingRuleException the dates or the party size
     *     break a rule
     * @throws RoomUnavailableException the room is out of service or already held for a night
     * @throws NotFoundException there is no such room
     */
    public Booking create(CreateBookingCommand command) {
        DataIntegrityViolationException collision = null;
        for (int attempt = 1; attempt <= CODE_ATTEMPTS; attempt++) {
            String code = ConfirmationCodes.generate(random);
            try {
                return transactions.execute(status -> insert(command, code));
            } catch (DataIntegrityViolationException violation) {
                String constraint = ConstraintNames.of(violation);
                if (constraint.contains(ROOM_NIGHT_CONSTRAINT)) {
                    throw new RoomUnavailableException(justTaken(command.roomId()));
                }
                if (!constraint.contains(BOOKING_CODE_CONSTRAINT)) {
                    throw violation;
                }
                collision = violation;
            }
        }
        throw collision;
    }

    /**
     * The whole unit of work: a guest row of its own, the booking, one room night per night, the
     * opening invoice and the CREATED event, flushed so that anything the database objects to is
     * raised here rather than at an unrelated commit later.
     *
     * <p>Availability is three questions, not one: the room is in service, it holds none of the
     * nights asked for, and - when the stay would start today - nobody is still in it who should
     * already have left. The third is what {@code RoomRepository.findAvailable} also asks, because
     * an overstay has no room nights left to collide with.</p>
     */
    private Booking insert(CreateBookingCommand command, String code) {
        Room room = rooms.findById(command.roomId())
            .orElseThrow(() -> new NotFoundException("No room with id " + command.roomId()));
        LocalDate today = BranchDates.today(clock, room.getBranch().zone());
        StayPeriod stay = StayPeriod.validated(command.checkIn(), command.checkOut(), today);
        OccupancyRule.check(command.adults(), command.children(),
            room.getRoomType().getMaxOccupancy());
        if (room.getStatus() != RoomStatus.AVAILABLE) {
            throw new RoomUnavailableException("Room " + room.getRoomNumber() + " is out of service");
        }
        if (!roomNights.bookedNights(room.getId(), stay.checkIn(), stay.checkOut()).isEmpty()) {
            throw new RoomUnavailableException(
                "Room " + room.getRoomNumber() + " is not free for those dates");
        }
        if (!stay.checkIn().isAfter(today) && bookings.existsByRoomIdAndStatusAndCheckOutDateLessThanEqual(
                room.getId(), BookingStatus.CHECKED_IN, today)) {
            throw new RoomUnavailableException("Room " + room.getRoomNumber() + " is still occupied");
        }

        Instant now = clock.instant();
        Guest guest = new Guest();
        guest.setFirstName(command.firstName());
        guest.setLastName(command.lastName());
        guest.setEmail(command.email());
        guest.setPhone(trimmed(command.phone()));
        guest.setCreatedAt(now);
        guests.save(guest);

        Booking booking = new Booking();
        booking.setConfirmationCode(code);
        booking.setRoom(room);
        booking.setGuest(guest);
        booking.setCheckInDate(stay.checkIn());
        booking.setCheckOutDate(stay.checkOut());
        booking.setAdults(command.adults());
        booking.setChildren(command.children());
        booking.setSpecialRequests(trimmed(command.specialRequests()));
        booking.setStatus(BookingStatus.CONFIRMED);
        booking.setNightlyRate(room.getNightlyRate());
        booking.setCancellationFee(ZERO_MONEY);
        booking.setCreatedAt(now);

        bookings.save(booking);
        for (LocalDate night : stay.nightDates()) {
            RoomNight held = new RoomNight();
            held.setRoom(room);
            held.setNightDate(night);
            held.setBooking(booking);
            roomNights.save(held);
        }
        invoiceService.rebuild(booking, (int) stay.nights());
        events.save(event(booking, BookingEventType.CREATED, command.actor(), null));
        bookings.flush();
        detach(booking);
        return booking;
    }

    // --- finding ------------------------------------------------------------------------------

    /** The booking behind a confirmation code, with the associations the pages read loaded. */
    @Transactional(readOnly = true)
    public Booking requireByCode(String code) {
        Booking booking = byCode(code);
        detach(booking);
        return booking;
    }

    /**
     * The pair a guest was given when they booked. The email is normalised and compared before
     * anything is returned, and every way of getting it wrong - no such code, wrong email, no
     * email at all - answers with the same empty result, so the endpoint behind this cannot be
     * used to find out which confirmation codes exist.
     */
    @Transactional(readOnly = true)
    public Optional<Booking> findByCodeAndEmail(String code, String email) {
        String wanted = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        if (wanted.isEmpty()) {
            return Optional.empty();
        }
        Optional<Booking> found = bookings.findByConfirmationCode(normalised(code));
        if (found.isEmpty() || !wanted.equals(found.get().getGuest().getEmail())) {
            return Optional.empty();
        }
        detach(found.get());
        return found;
    }

    /** What cancelling would add to the invoice if the guest did it now: nothing, or one night. */
    @Transactional(readOnly = true)
    public BigDecimal cancellationFeeNow(Booking booking) {
        return CancellationPolicy.feeAt(clock.instant(), booking.getCheckInDate(),
            zoneOf(booking), booking.getNightlyRate());
    }

    /** The instant the free window closes, which the booking page prints in the branch timezone. */
    @Transactional(readOnly = true)
    public Instant cancellationDeadline(Booking booking) {
        return CancellationPolicy.deadline(booking.getCheckInDate(), zoneOf(booking));
    }

    // --- cancelling ---------------------------------------------------------------------------

    /**
     * Cancels a confirmed booking: prices the cancellation, releases every night it held and its
     * parking space, rebuilds the invoice - VOID when nothing is owed, OPEN when the fee is - and
     * records the transition.
     *
     * @throws io.github.sahajm99.innkeeper.domain.IllegalBookingStateException the booking has
     *     already been checked in, checked out or cancelled
     * @throws NotFoundException there is no booking with that code
     */
    @Transactional
    public Booking cancel(String code, String actor) {
        Booking booking = byCode(code);
        CancellationPolicy.assertCancellable(booking.getStatus());

        Instant now = clock.instant();
        BigDecimal fee = CancellationPolicy.feeAt(now, booking.getCheckInDate(),
            zoneOf(booking), booking.getNightlyRate());
        booking.setStatus(BookingStatus.CANCELLED);
        booking.setCancelledAt(now);
        booking.setCancellationFee(fee);

        roomNights.deleteByBookingId(booking.getId());
        parkingSpaces.release(booking.getId());
        invoiceService.rebuild(booking, 0);
        events.save(event(booking, BookingEventType.CANCELLED, actor,
            fee.signum() > 0 ? "Cancellation fee $" + fee.toPlainString() : null));

        detach(booking);
        return booking;
    }

    // --- reading ------------------------------------------------------------------------------

    /** The whole booking as the pages and the API need it, read in one transaction and detached. */
    @Transactional(readOnly = true)
    public BookingView view(Booking booking) {
        Booking loaded = bookings.findById(booking.getId())
            .orElseThrow(() -> new NotFoundException("No booking with id " + booking.getId()));
        Room room = loaded.getRoom();
        Branch branch = room.getBranch();
        Guest guest = loaded.getGuest();
        Invoice invoice = invoices.findByBookingId(loaded.getId()).orElse(null);

        List<BookingView.LineView> lines = invoice == null ? List.of()
            : invoice.getLines().stream()
                .map(line -> new BookingView.LineView(line.getKind(), line.getDescription(),
                    line.getQuantity(), line.getUnitAmount(), line.getAmount()))
                .toList();
        List<BookingView.PaymentView> paid = invoice == null ? List.of()
            : payments.findByInvoiceIdOrderByPaidAt(invoice.getId()).stream()
                .map(payment -> new BookingView.PaymentView(payment.getAmount(),
                    payment.getMethod(), payment.getReference(), payment.getPaidAt()))
                .toList();

        return new BookingView(
            loaded.getConfirmationCode(),
            loaded.getStatus(),
            room.getId(),
            room.getRoomNumber(),
            room.getRoomType().getCode(),
            room.getRoomType().getName(),
            branch.getId(),
            branch.getCode(),
            branch.getName(),
            branch.zone(),
            loaded.getCheckInDate(),
            loaded.getCheckOutDate(),
            (int) loaded.stay().nights(),
            loaded.getAdults(),
            loaded.getChildren(),
            guest.getFirstName(),
            guest.getLastName(),
            guest.getEmail(),
            guest.getPhone(),
            loaded.getSpecialRequests(),
            loaded.getNightlyRate(),
            loaded.getCancellationFee(),
            loaded.getCreatedAt(),
            loaded.getCheckedInAt(),
            loaded.getCheckedOutAt(),
            loaded.getCancelledAt(),
            invoice == null ? null : new BookingView.InvoiceView(invoice.getTaxRate(),
                invoice.getRoomSubtotal(), invoice.getTax(), invoice.getFines(),
                invoice.getCancellationFee(), invoice.getTotal(), invoiceService.balance(invoice),
                invoice.getStatus(), invoice.getIssuedAt()),
            lines,
            paid,
            fines.findByBookingIdOrderByIssuedAt(loaded.getId()).stream()
                .map(fine -> new BookingView.FineView(fine.getReason(), fine.getAmount(),
                    fine.getIssuedAt()))
                .toList(),
            events.findByBookingIdOrderByOccurredAt(loaded.getId()).stream()
                .map(moment -> new BookingView.EventView(moment.getEventType(),
                    moment.getOccurredAt(), moment.getActor(), moment.getNote()))
                .toList(),
            cancellationFeeNow(loaded),
            cancellationDeadline(loaded));
    }

    // --- plumbing -----------------------------------------------------------------------------

    private Booking byCode(String code) {
        return bookings.findByConfirmationCode(normalised(code))
            .orElseThrow(() -> new NotFoundException("No booking with code " + code));
    }

    /** Codes are printed and pasted, so leading space and lower case are the guest's, not an error. */
    private String normalised(String code) {
        return code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * Pulls in the associations a caller reads after the transaction closes. Without this the
     * booking leaves here holding lazy proxies and the first getter outside fails, because
     * {@code open-in-view} is off.
     */
    private void detach(Booking booking) {
        Hibernate.initialize(booking.getRoom());
        Hibernate.initialize(booking.getRoom().getRoomType());
        Hibernate.initialize(booking.getRoom().getBranch());
        Hibernate.initialize(booking.getGuest());
    }

    private ZoneId zoneOf(Booking booking) {
        return booking.getRoom().getBranch().zone();
    }

    /** The room number for the message a loser of a race gets; the room itself is long detached. */
    private String justTaken(Long roomId) {
        return rooms.findById(roomId)
            .map(room -> "Room " + room.getRoomNumber() + " was just taken for those dates")
            .orElse("That room was just taken for those dates");
    }

    private BookingEvent event(Booking booking, BookingEventType type, String actor, String note) {
        BookingEvent moment = new BookingEvent();
        moment.setBooking(booking);
        moment.setEventType(type);
        moment.setOccurredAt(clock.instant());
        moment.setActor(actor == null || actor.isBlank() ? DEFAULT_ACTOR : actor.trim());
        moment.setNote(note);
        return moment;
    }

    private String trimmed(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
