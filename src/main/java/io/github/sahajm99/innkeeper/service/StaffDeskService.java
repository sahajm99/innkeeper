package io.github.sahajm99.innkeeper.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import io.github.sahajm99.innkeeper.config.InnkeeperProperties;
import io.github.sahajm99.innkeeper.domain.BookingStatus;
import io.github.sahajm99.innkeeper.domain.BranchDates;
import io.github.sahajm99.innkeeper.domain.CheckInOutPolicy;
import io.github.sahajm99.innkeeper.domain.CheckInOutPolicy.CheckOutOutcome;
import io.github.sahajm99.innkeeper.domain.IllegalBookingStateException;
import io.github.sahajm99.innkeeper.domain.NotFoundException;
import io.github.sahajm99.innkeeper.model.Booking;
import io.github.sahajm99.innkeeper.model.BookingEvent;
import io.github.sahajm99.innkeeper.model.BookingEventType;
import io.github.sahajm99.innkeeper.model.Branch;
import io.github.sahajm99.innkeeper.model.ComplaintStatus;
import io.github.sahajm99.innkeeper.model.Employee;
import io.github.sahajm99.innkeeper.model.Fine;
import io.github.sahajm99.innkeeper.model.InventoryItem;
import io.github.sahajm99.innkeeper.model.Invoice;
import io.github.sahajm99.innkeeper.model.InvoiceStatus;
import io.github.sahajm99.innkeeper.model.MaintenanceRequest;
import io.github.sahajm99.innkeeper.model.MaintenanceStatus;
import io.github.sahajm99.innkeeper.model.ParkingKind;
import io.github.sahajm99.innkeeper.model.ParkingSpace;
import io.github.sahajm99.innkeeper.model.Payment;
import io.github.sahajm99.innkeeper.model.PaymentMethod;
import io.github.sahajm99.innkeeper.model.Priority;
import io.github.sahajm99.innkeeper.model.Role;
import io.github.sahajm99.innkeeper.model.RoomStatus;
import io.github.sahajm99.innkeeper.model.UserAccount;
import io.github.sahajm99.innkeeper.repository.BookingEventRepository;
import io.github.sahajm99.innkeeper.repository.BookingRepository;
import io.github.sahajm99.innkeeper.repository.BranchRepository;
import io.github.sahajm99.innkeeper.repository.ComplaintRepository;
import io.github.sahajm99.innkeeper.repository.FineRepository;
import io.github.sahajm99.innkeeper.repository.InventoryItemRepository;
import io.github.sahajm99.innkeeper.repository.InvoiceRepository;
import io.github.sahajm99.innkeeper.repository.MaintenanceRequestRepository;
import io.github.sahajm99.innkeeper.repository.ParkingSpaceRepository;
import io.github.sahajm99.innkeeper.repository.PaymentRepository;
import io.github.sahajm99.innkeeper.repository.RoomNightRepository;
import io.github.sahajm99.innkeeper.repository.RoomRepository;
import io.github.sahajm99.innkeeper.repository.UserAccountRepository;

import org.hibernate.Hibernate;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The front desk: what a shift needs to see, and every transition a clerk can make on a stay.
 *
 * <p>None of the rules live here. {@link CheckInOutPolicy} decides whether a stay may check in and
 * what checking out today costs and releases, {@link InvoiceService} prices the result, and this
 * class does the writing around them: the parking space, the late fine, the room nights an early
 * departure gives back and the audit trail. The one thing it decides for itself is the shape of
 * the dashboard, which is the two ledgers of rule 13 plus the state of the house.</p>
 *
 * <p>Every method that hands an entity back to a page initialises what that page reads first,
 * because {@code open-in-view} is off and a lazy association outside the session is an error
 * rather than another query.</p>
 */
@Service
public class StaffDeskService {

    /** One branch's rooms right now: how many are full, how many could be sold, and the percent. */
    public record Occupancy(Branch branch, long occupied, long inService, int percent) {
    }

    /**
     * The staff home page. {@code openMaintenance} and {@code openComplaints} count what nobody has
     * picked up yet - a job in progress already has somebody on it - and are ordered urgent first.
     */
    public record Dashboard(List<Booking> departures, List<Booking> arrivals,
        List<Occupancy> occupancy, Map<Priority, Long> openMaintenance,
        List<InventoryItem> lowStock, long openComplaints, LocalDate today) {
    }

    private static final BigDecimal ZERO_MONEY = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

    private final BookingRepository bookings;
    private final BranchRepository branches;
    private final RoomRepository rooms;
    private final RoomNightRepository roomNights;
    private final ParkingSpaceRepository parkingSpaces;
    private final FineRepository fines;
    private final PaymentRepository payments;
    private final InvoiceRepository invoices;
    private final BookingEventRepository events;
    private final MaintenanceRequestRepository maintenance;
    private final InventoryItemRepository inventory;
    private final ComplaintRepository complaints;
    private final UserAccountRepository accounts;
    private final InvoiceService invoiceService;
    private final Clock clock;
    private final ZoneId appZone;

    public StaffDeskService(BookingRepository bookings, BranchRepository branches,
            RoomRepository rooms, RoomNightRepository roomNights,
            ParkingSpaceRepository parkingSpaces, FineRepository fines, PaymentRepository payments,
            InvoiceRepository invoices, BookingEventRepository events,
            MaintenanceRequestRepository maintenance, InventoryItemRepository inventory,
            ComplaintRepository complaints, UserAccountRepository accounts,
            InvoiceService invoiceService, Clock clock, InnkeeperProperties properties) {
        this.bookings = bookings;
        this.branches = branches;
        this.rooms = rooms;
        this.roomNights = roomNights;
        this.parkingSpaces = parkingSpaces;
        this.fines = fines;
        this.payments = payments;
        this.invoices = invoices;
        this.events = events;
        this.maintenance = maintenance;
        this.inventory = inventory;
        this.complaints = complaints;
        this.accounts = accounts;
        this.invoiceService = invoiceService;
        this.clock = clock;
        this.appZone = properties.timezone() == null
            ? ZoneId.systemDefault()
            : ZoneId.of(properties.timezone());
    }

    // --- the dashboard --------------------------------------------------------------------------

    /**
     * What the shift is looking at: who is due out (overdue first, because an overstay is still
     * listed), who is due in, and the state of every branch asked for. A null branch is every
     * branch, which is what a manager sees.
     */
    @Transactional(readOnly = true)
    public Dashboard dashboard(Long branchId) {
        LocalDate today = BranchDates.today(clock, zoneOf(branchId));
        List<Booking> departures = bookings.departures(branchId, today);
        List<Booking> arrivals = bookings.arrivals(branchId, today);
        departures.forEach(this::initialize);
        arrivals.forEach(this::initialize);
        return new Dashboard(departures, arrivals, occupancy(branchId), openMaintenance(branchId),
            inventory.lowStock(branchId),
            complaints.countByStatus(ComplaintStatus.OPEN, branchId), today);
    }

    private List<Occupancy> occupancy(Long branchId) {
        List<Branch> wanted = branchId == null
            ? branches.findAllByOrderByName()
            : branches.findById(branchId).map(List::of).orElseGet(List::of);
        List<Occupancy> occupancy = new ArrayList<>();
        for (Branch branch : wanted) {
            long occupied = bookings.occupiedRooms(branch.getId());
            long inService = rooms.countByBranchIdAndStatus(branch.getId(), RoomStatus.AVAILABLE);
            int percent = inService == 0 ? 0 : (int) (occupied * 100 / inService);
            occupancy.add(new Occupancy(branch, occupied, inService, percent));
        }
        return List.copyOf(occupancy);
    }

    /**
     * Jobs nobody has started, counted by priority, urgent first. The board query is asked for
     * work finished from now on, so nothing but open and in-progress jobs comes back to be counted.
     */
    private Map<Priority, Long> openMaintenance(Long branchId) {
        List<MaintenanceRequest> outstanding = maintenance.board(branchId, clock.instant());
        Map<Priority, Long> counts = new LinkedHashMap<>();
        Priority[] priorities = Priority.values();
        for (int priority = priorities.length - 1; priority >= 0; priority--) {
            Priority level = priorities[priority];
            long open = outstanding.stream()
                .filter(request -> request.getStatus() == MaintenanceStatus.OPEN
                    && request.getPriority() == level)
                .count();
            if (open > 0) {
                counts.put(level, open);
            }
        }
        return Collections.unmodifiableMap(counts);
    }

    // --- checking in and out --------------------------------------------------------------------

    /**
     * Checks the guest in, and claims the parking space when one was chosen.
     *
     * <p>The space is claimed first, because claiming it is a conditional update that can fail:
     * two clerks offering the last space to two guests both see it free, and only one update
     * changes a row. The loser leaves with no space and no check-in, since the whole method is one
     * transaction.</p>
     *
     * @throws IllegalBookingStateException the stay is not confirmed, or today is outside it
     * @throws ParkingUnavailableException somebody else took the space first
     * @throws NotFoundException there is no booking with that code
     */
    @Transactional
    public Booking checkIn(String code, Long parkingSpaceId, String actor) {
        Booking booking = byCode(code);
        LocalDate today = BranchDates.today(clock, zoneOf(booking));
        CheckInOutPolicy.assertCanCheckIn(booking.getStatus(), booking.stay(), today);

        String space = null;
        if (parkingSpaceId != null) {
            if (parkingSpaces.assign(parkingSpaceId, booking.getId()) == 0) {
                throw new ParkingUnavailableException("That parking space has just been taken");
            }
            space = parkingSpaces.findById(parkingSpaceId)
                .map(claimed -> "Parking space " + claimed.getSpaceNumber())
                .orElse(null);
        }

        booking.setStatus(BookingStatus.CHECKED_IN);
        booking.setCheckedInAt(clock.instant());
        events.save(event(booking, BookingEventType.CHECKED_IN, actor, space));
        initialize(booking);
        return booking;
    }

    /**
     * Checks the guest out and returns what it cost the stay.
     *
     * <p>Rule 9 in order: an early departure gives back every night from
     * {@code releaseNightsFrom}, so the room can be sold again tonight; a late one is fined one
     * night of the rate per night past check-out; the parking space goes back; and the invoice is
     * rebuilt for the nights actually charged, which is not always the nights booked.</p>
     *
     * @throws IllegalBookingStateException the guest is not checked in
     * @throws NotFoundException there is no booking with that code
     */
    @Transactional
    public CheckOutOutcome checkOut(String code, String actor) {
        Booking booking = byCode(code);
        LocalDate today = BranchDates.today(clock, zoneOf(booking));
        CheckOutOutcome outcome =
            CheckInOutPolicy.checkOut(booking.getStatus(), booking.stay(), today);
        Instant now = clock.instant();

        if (outcome.releaseNightsFrom() != null) {
            roomNights.deleteByBookingIdAndNightDateGreaterThanEqual(booking.getId(),
                outcome.releaseNightsFrom());
        }
        String late = null;
        if (outcome.lateNights() > 0) {
            late = lateCheckOut(outcome.lateNights());
            BigDecimal amount = money(booking.getNightlyRate()
                .multiply(BigDecimal.valueOf(outcome.lateNights())));
            fines.saveAndFlush(fine(booking, late, amount, actor, now));
        }
        parkingSpaces.release(booking.getId());

        booking.setStatus(BookingStatus.CHECKED_OUT);
        booking.setCheckedOutAt(now);
        invoiceService.rebuild(booking, outcome.nightsCharged());
        events.save(event(booking, BookingEventType.CHECKED_OUT, actor, late));
        return outcome;
    }

    // --- money ------------------------------------------------------------------------------

    /**
     * Puts a charge on the stay and reprices the invoice.
     *
     * @throws IllegalBookingStateException the stay is already over or cancelled (rule 11)
     * @throws IllegalArgumentException the amount is not a charge, or no reason was given
     */
    @Transactional
    public Fine addFine(String code, String reason, BigDecimal amount, String actor) {
        Booking booking = byCode(code);
        if (booking.getStatus() != BookingStatus.CONFIRMED
                && booking.getStatus() != BookingStatus.CHECKED_IN) {
            throw new IllegalBookingStateException(
                "A fine can only be added while the stay is booked or the guest is in-house");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A fine needs a reason");
        }
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("A fine has to be more than zero");
        }

        Fine charge = fines.saveAndFlush(
            fine(booking, reason.trim(), money(amount), actor, clock.instant()));
        invoiceService.rebuild(booking, (int) booking.stay().nights());
        events.save(event(booking, BookingEventType.FINE_ADDED, actor,
            reason.trim() + " $" + charge.getAmount().toPlainString()));
        return charge;
    }

    /**
     * Takes money against the invoice. Nothing is collected before the guest leaves and nothing
     * over the balance is accepted, so an invoice can never end up overpaid; reaching zero settles
     * it (rules 11 and 13).
     *
     * @throws IllegalBookingStateException the guest has not checked out yet
     * @throws IllegalArgumentException the amount is not positive, or is more than is owed
     */
    @Transactional
    public Payment recordPayment(String code, BigDecimal amount, PaymentMethod method,
            String reference, String actor) {
        Booking booking = byCode(code);
        if (booking.getStatus() != BookingStatus.CHECKED_OUT) {
            throw new IllegalBookingStateException(
                "A payment can only be recorded once the guest has checked out");
        }
        if (method == null) {
            throw new IllegalArgumentException("A payment needs a method");
        }
        Invoice invoice = invoices.findByBookingId(booking.getId())
            .orElseThrow(() -> new NotFoundException(
                "No invoice for booking " + booking.getConfirmationCode()));

        BigDecimal paid = money(amount == null ? ZERO_MONEY : amount);
        BigDecimal balance = invoiceService.balance(invoice);
        if (paid.signum() <= 0) {
            throw new IllegalArgumentException("A payment has to be more than zero");
        }
        if (paid.compareTo(balance) > 0) {
            throw new IllegalArgumentException(
                "A payment cannot be more than the balance of $" + balance.toPlainString());
        }

        Payment payment = new Payment();
        payment.setInvoice(invoice);
        payment.setAmount(paid);
        payment.setMethod(method);
        payment.setReference(trimmed(reference));
        payment.setPaidAt(clock.instant());
        payment.setRecordedBy(employeeFor(actor));
        payments.saveAndFlush(payment);

        if (invoiceService.balance(invoice).signum() == 0) {
            invoice.setStatus(InvoiceStatus.PAID);
            invoice.setUpdatedAt(clock.instant());
        }
        events.save(event(booking, BookingEventType.PAYMENT_RECORDED, actor,
            method.name() + " $" + paid.toPlainString()));
        return payment;
    }

    // --- looking things up --------------------------------------------------------------------

    /** The desk search box: a confirmation code, an email address or a guest name. */
    @Transactional(readOnly = true)
    public List<Booking> search(String q) {
        if (q == null || q.isBlank()) {
            return List.of();
        }
        List<Booking> found = bookings.search(q.trim());
        found.forEach(this::initialize);
        return found;
    }

    /** The guest spaces nobody is parked in, which is what the check-in form offers. */
    @Transactional(readOnly = true)
    public List<ParkingSpace> freeGuestSpaces(Long branchId) {
        List<ParkingSpace> free = parkingSpaces
            .findByBranchIdAndBookingIsNullAndKindOrderBySpaceNumber(branchId, ParkingKind.GUEST);
        free.forEach(space -> Hibernate.initialize(space.getBranch()));
        return free;
    }

    /**
     * The branch a signed-in clerk works at, which is the branch their pages default to. Null for
     * a manager, who is responsible for every branch, and for an account with no employee behind
     * it at all.
     */
    @Transactional(readOnly = true)
    public Long branchForUsername(String username) {
        if (username == null || username.isBlank()) {
            return null;
        }
        return accounts.findByUsername(username.trim())
            .filter(account -> account.getRole() != Role.MANAGER)
            .map(UserAccount::getEmployee)
            .map(employee -> employee.getBranch().getId())
            .orElse(null);
    }

    // --- plumbing ---------------------------------------------------------------------------

    private Booking byCode(String code) {
        String wanted = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        return bookings.findByConfirmationCode(wanted)
            .orElseThrow(() -> new NotFoundException("No booking with code " + code));
    }

    /** "Late check-out (2 nights)", and the singular when it is only one. */
    private String lateCheckOut(int lateNights) {
        return "Late check-out (" + lateNights + (lateNights == 1 ? " night)" : " nights)");
    }

    private Fine fine(Booking booking, String reason, BigDecimal amount, String actor,
            Instant issuedAt) {
        Fine fine = new Fine();
        fine.setBooking(booking);
        fine.setReason(reason);
        fine.setAmount(amount);
        fine.setIssuedAt(issuedAt);
        fine.setIssuedBy(employeeFor(actor));
        return fine;
    }

    private BookingEvent event(Booking booking, BookingEventType type, String actor, String note) {
        BookingEvent moment = new BookingEvent();
        moment.setBooking(booking);
        moment.setEventType(type);
        moment.setOccurredAt(clock.instant());
        moment.setActor(actor == null || actor.isBlank() ? "staff" : actor.trim());
        moment.setNote(note);
        return moment;
    }

    /** The employee behind the username, so a fine or a payment says who took it. */
    private Employee employeeFor(String username) {
        if (username == null || username.isBlank()) {
            return null;
        }
        return accounts.findByUsername(username.trim())
            .map(UserAccount::getEmployee)
            .orElse(null);
    }

    /** Pulls in what a staff page reads off a booking, since the session closes before it does. */
    private void initialize(Booking booking) {
        Hibernate.initialize(booking.getRoom());
        Hibernate.initialize(booking.getRoom().getRoomType());
        Hibernate.initialize(booking.getRoom().getBranch());
        Hibernate.initialize(booking.getGuest());
    }

    /**
     * "Today" belongs to a branch, not to the server. A dashboard of every branch uses the first
     * branch, falling back to the configured application timezone when there are no branches.
     */
    private ZoneId zoneOf(Long branchId) {
        Optional<Branch> branch = branchId == null
            ? branches.findAllByOrderByName().stream().findFirst()
            : branches.findById(branchId);
        return branch.map(Branch::zone).orElse(appZone);
    }

    private ZoneId zoneOf(Booking booking) {
        return booking.getRoom().getBranch().zone();
    }

    private BigDecimal money(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP);
    }

    private String trimmed(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
