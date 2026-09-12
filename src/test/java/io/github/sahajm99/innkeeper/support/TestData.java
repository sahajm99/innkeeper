package io.github.sahajm99.innkeeper.support;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Locale;

import io.github.sahajm99.innkeeper.domain.BookingStatus;
import io.github.sahajm99.innkeeper.domain.ConfirmationCodes;
import io.github.sahajm99.innkeeper.domain.InvoiceLineKind;
import io.github.sahajm99.innkeeper.model.Booking;
import io.github.sahajm99.innkeeper.model.BookingEvent;
import io.github.sahajm99.innkeeper.model.BookingEventType;
import io.github.sahajm99.innkeeper.model.Branch;
import io.github.sahajm99.innkeeper.model.Employee;
import io.github.sahajm99.innkeeper.model.Fine;
import io.github.sahajm99.innkeeper.model.Guest;
import io.github.sahajm99.innkeeper.model.Invoice;
import io.github.sahajm99.innkeeper.model.InvoiceLine;
import io.github.sahajm99.innkeeper.model.InvoiceStatus;
import io.github.sahajm99.innkeeper.model.Room;
import io.github.sahajm99.innkeeper.model.RoomNight;
import io.github.sahajm99.innkeeper.model.RoomStatus;
import io.github.sahajm99.innkeeper.model.RoomType;
import jakarta.persistence.EntityManager;

import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

/**
 * Persists the small object graphs the repository, service and web tests need.
 *
 * <p>Deliberately not a Spring bean: construct it with the {@link EntityManager} of the test so
 * that it works under {@code DataJpaTest} and {@code SpringBootTest} alike. Every helper returns
 * the managed entity it inserted, so tests can chain them.</p>
 */
public class TestData {

    /** The timezone every test branch runs in, matching the configured innkeeper timezone. */
    public static final String TIMEZONE = "America/Chicago";

    /** A fixed instant for audit columns, so tests never depend on the wall clock. */
    public static final Instant CREATED_AT = Instant.parse("2026-01-01T12:00:00Z");

    private final EntityManager em;
    private int sequence;

    public TestData(EntityManager em) {
        this.em = em;
    }

    public TestData(TestEntityManager em) {
        this(em.getEntityManager());
    }

    // --- branches, room types and rooms ----------------------------------------------------

    /** A branch with the given code, an 8.25 percent tax rate and the default timezone. */
    public Branch branch(String code) {
        return branch(code, code + " Square", new BigDecimal("0.0825"));
    }

    public Branch branch(String code, String name, BigDecimal taxRate) {
        Branch branch = new Branch();
        branch.setCode(code);
        branch.setName(name);
        branch.setTagline("Rest well at " + name + ".");
        branch.setDescription("A branch used by the tests.");
        branch.setAddressLine("1 Test Street");
        branch.setCity("Denton");
        branch.setState("TX");
        branch.setPostalCode("76201");
        branch.setPhone("940-555-0100");
        branch.setEmail(code.toLowerCase(Locale.ROOT) + "@example.com");
        branch.setTaxRate(taxRate);
        branch.setTimezone(TIMEZONE);
        branch.setCreatedAt(CREATED_AT);
        return persist(branch);
    }

    public RoomType roomType(String code, int maxOccupancy) {
        RoomType type = new RoomType();
        type.setCode(code);
        type.setName(code.charAt(0) + code.substring(1).toLowerCase(Locale.ROOT));
        type.setDescription("A " + code.toLowerCase(Locale.ROOT) + " room.");
        type.setMaxOccupancy(maxOccupancy);
        type.setBedSetup(maxOccupancy > 2 ? "Two queen beds" : "One king bed");
        type.setSortOrder(++sequence);
        return persist(type);
    }

    /** An AVAILABLE room; the floor is the first digit of the room number. */
    public Room room(Branch branch, RoomType type, String number, String nightlyRate) {
        return room(branch, type, number, nightlyRate, RoomStatus.AVAILABLE);
    }

    public Room room(Branch branch, RoomType type, String number, String nightlyRate, RoomStatus status) {
        Room room = new Room();
        room.setBranch(branch);
        room.setRoomType(type);
        room.setRoomNumber(number);
        room.setFloor(Character.getNumericValue(number.charAt(0)));
        room.setNightlyRate(new BigDecimal(nightlyRate));
        room.setStatus(status);
        return persist(room);
    }

    // --- guests, employees and bookings -----------------------------------------------------

    public Guest guest(String email) {
        return guest("Ada", "Lovelace", email);
    }

    public Guest guest(String firstName, String lastName, String email) {
        Guest guest = new Guest();
        guest.setFirstName(firstName);
        guest.setLastName(lastName);
        guest.setEmail(email);
        guest.setCreatedAt(CREATED_AT);
        return persist(guest);
    }

    public Employee employee(Branch branch, String firstName, String lastName, String email) {
        Employee employee = new Employee();
        employee.setBranch(branch);
        employee.setFirstName(firstName);
        employee.setLastName(lastName);
        employee.setEmail(email);
        employee.setPosition("Front desk");
        employee.setHiredOn(LocalDate.of(2025, 1, 6));
        employee.setActive(true);
        return persist(employee);
    }

    /**
     * A booking for two adults, plus one {@link RoomNight} per night of the stay. A CANCELLED
     * booking gets no nights, because cancelling releases them.
     */
    public Booking booking(Room room, Guest guest, LocalDate checkIn, LocalDate checkOut, BookingStatus status) {
        Booking booking = new Booking();
        booking.setConfirmationCode(nextConfirmationCode());
        booking.setRoom(room);
        booking.setGuest(guest);
        booking.setCheckInDate(checkIn);
        booking.setCheckOutDate(checkOut);
        booking.setAdults(2);
        booking.setChildren(0);
        booking.setStatus(status);
        booking.setNightlyRate(room.getNightlyRate());
        booking.setCancellationFee(new BigDecimal("0.00"));
        booking.setCreatedAt(CREATED_AT);
        if (status == BookingStatus.CHECKED_IN || status == BookingStatus.CHECKED_OUT) {
            booking.setCheckedInAt(CREATED_AT);
        }
        if (status == BookingStatus.CHECKED_OUT) {
            booking.setCheckedOutAt(CREATED_AT);
        }
        if (status == BookingStatus.CANCELLED) {
            booking.setCancelledAt(CREATED_AT);
        }
        persist(booking);
        if (status != BookingStatus.CANCELLED) {
            checkIn.datesUntil(checkOut).forEach(night -> night(booking, night));
        }
        return booking;
    }

    public RoomNight night(Booking booking, LocalDate nightDate) {
        RoomNight night = new RoomNight();
        night.setRoom(booking.getRoom());
        night.setNightDate(nightDate);
        night.setBooking(booking);
        return persist(night);
    }

    public BookingEvent event(Booking booking, BookingEventType type, String actor) {
        BookingEvent event = new BookingEvent();
        event.setBooking(booking);
        event.setEventType(type);
        event.setOccurredAt(CREATED_AT);
        event.setActor(actor);
        return persist(event);
    }

    public Fine fine(Booking booking, String reason, String amount) {
        Fine fine = new Fine();
        fine.setBooking(booking);
        fine.setReason(reason);
        fine.setAmount(new BigDecimal(amount));
        fine.setIssuedAt(CREATED_AT);
        return persist(fine);
    }

    // --- invoices ----------------------------------------------------------------------------

    /** An OPEN invoice whose stored total is the sum of its parts. */
    public Invoice invoice(Booking booking, String roomSubtotal, String tax) {
        Invoice invoice = new Invoice();
        invoice.setBooking(booking);
        invoice.setTaxRate(booking.getRoom().getBranch().getTaxRate());
        invoice.setRoomSubtotal(new BigDecimal(roomSubtotal));
        invoice.setTax(new BigDecimal(tax));
        invoice.setFines(new BigDecimal("0.00"));
        invoice.setCancellationFee(new BigDecimal("0.00"));
        invoice.setTotal(new BigDecimal(roomSubtotal).add(new BigDecimal(tax)));
        invoice.setStatus(InvoiceStatus.OPEN);
        invoice.setIssuedAt(CREATED_AT);
        invoice.setUpdatedAt(CREATED_AT);
        return persist(invoice);
    }

    public InvoiceLine invoiceLine(Invoice invoice, int lineOrder, InvoiceLineKind kind, String amount) {
        InvoiceLine line = new InvoiceLine();
        line.setLineOrder(lineOrder);
        line.setKind(kind);
        line.setDescription(kind.name());
        line.setQuantity(1);
        line.setUnitAmount(new BigDecimal(amount));
        line.setAmount(new BigDecimal(amount));
        invoice.addLine(line);
        return persist(line);
    }

    // --- plumbing ----------------------------------------------------------------------------

    /**
     * Every message in the cause chain of a failure, joined. Database drivers name the violated
     * constraint somewhere in the chain rather than in the top-level message.
     */
    public static String messageChain(Throwable error) {
        StringBuilder messages = new StringBuilder();
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            messages.append(cause.getMessage()).append('\n');
            if (cause.getCause() == cause) {
                break;
            }
        }
        return messages.toString();
    }

    public void flush() {
        em.flush();
    }

    public void flushAndClear() {
        em.flush();
        em.clear();
    }

    /** A unique code that satisfies the confirmation code pattern. */
    public String nextConfirmationCode() {
        int value = ++sequence;
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            body.insert(0, ConfirmationCodes.ALPHABET.charAt(value % ConfirmationCodes.ALPHABET.length()));
            value /= ConfirmationCodes.ALPHABET.length();
        }
        return "INN-T" + body;
    }

    private <T> T persist(T entity) {
        em.persist(entity);
        return entity;
    }
}
