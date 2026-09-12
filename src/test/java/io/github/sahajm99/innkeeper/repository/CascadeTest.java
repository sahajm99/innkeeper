package io.github.sahajm99.innkeeper.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import io.github.sahajm99.innkeeper.domain.BookingStatus;
import io.github.sahajm99.innkeeper.domain.InvoiceLineKind;
import io.github.sahajm99.innkeeper.model.Booking;
import io.github.sahajm99.innkeeper.model.BookingEventType;
import io.github.sahajm99.innkeeper.model.Branch;
import io.github.sahajm99.innkeeper.model.Guest;
import io.github.sahajm99.innkeeper.model.Invoice;
import io.github.sahajm99.innkeeper.model.InvoiceLine;
import io.github.sahajm99.innkeeper.model.Room;
import io.github.sahajm99.innkeeper.model.RoomType;
import io.github.sahajm99.innkeeper.support.TestData;
import io.github.sahajm99.innkeeper.support.UnseededDatabase;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * The ON DELETE rules of V1 and the orphan removal on Invoice.lines are part of the contract, so
 * they get their own tests.
 *
 * <p>Each test clears the persistence context before deleting. Hibernate refuses to flush a delete
 * while other managed entities still point at the row, which would mask the database rule these
 * tests are about.</p>
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@UnseededDatabase
class CascadeTest {

    private static final LocalDate OCT_3 = LocalDate.of(2026, 10, 3);
    private static final LocalDate OCT_5 = LocalDate.of(2026, 10, 5);

    @Autowired BookingRepository bookings;
    @Autowired BranchRepository branches;
    @Autowired RoomTypeRepository roomTypes;
    @Autowired InvoiceRepository invoices;
    @Autowired TestEntityManager entityManager;
    @Autowired JdbcTemplate jdbc;

    private TestData data;
    private Branch denton;
    private RoomType standard;
    private Room den101;
    private Guest guest;

    @BeforeEach
    void setUp() {
        data = new TestData(entityManager);
        denton = data.branch("DEN");
        standard = data.roomType("STANDARD", 2);
        den101 = data.room(denton, standard, "101", "89.00");
        guest = data.guest("ada@example.com");
    }

    @Test
    void deletingABookingRemovesItsNightsInvoiceLinesEventsAndFines() {
        Booking booking = data.booking(den101, guest, OCT_3, OCT_5, BookingStatus.CONFIRMED);
        Invoice invoice = data.invoice(booking, "178.00", "14.69");
        data.invoiceLine(invoice, 1, InvoiceLineKind.ROOM_NIGHTS, "178.00");
        data.invoiceLine(invoice, 2, InvoiceLineKind.TAX, "14.69");
        data.fine(booking, "Smoking in a non-smoking room", "150.00");
        data.event(booking, BookingEventType.CREATED, "guest");
        data.flushAndClear();
        Long bookingId = booking.getId();
        Long invoiceId = invoice.getId();
        assertThat(rowsWhere("room_night", "booking_id", bookingId)).isEqualTo(2);
        assertThat(rowsWhere("invoice_line", "invoice_id", invoiceId)).isEqualTo(2);

        bookings.delete(bookings.findById(bookingId).orElseThrow());
        bookings.flush();

        assertThat(rowsWhere("booking", "id", bookingId)).isZero();
        assertThat(rowsWhere("room_night", "booking_id", bookingId)).isZero();
        assertThat(rowsWhere("invoice", "booking_id", bookingId)).isZero();
        assertThat(rowsWhere("invoice_line", "invoice_id", invoiceId)).isZero();
        assertThat(rowsWhere("fine", "booking_id", bookingId)).isZero();
        assertThat(rowsWhere("booking_event", "booking_id", bookingId)).isZero();
    }

    @Test
    void replacingTheLinesOfAManagedInvoiceDeletesTheOldRows() {
        Booking booking = data.booking(den101, guest, OCT_3, OCT_5, BookingStatus.CONFIRMED);
        Invoice invoice = data.invoice(booking, "178.00", "14.69");
        data.invoiceLine(invoice, 1, InvoiceLineKind.ROOM_NIGHTS, "178.00");
        data.invoiceLine(invoice, 2, InvoiceLineKind.TAX, "14.69");
        data.flushAndClear();
        Long invoiceId = invoice.getId();
        assertThat(rowsWhere("invoice_line", "invoice_id", invoiceId)).isEqualTo(2);

        Invoice managed = invoices.findById(invoiceId).orElseThrow();
        managed.replaceLines(List.of(cancellationFeeLine()));
        invoices.flush();
        entityManager.clear();

        assertThat(rowsWhere("invoice_line", "invoice_id", invoiceId)).isEqualTo(1);
        assertThat(invoices.findById(invoiceId).orElseThrow().getLines())
            .singleElement()
            .extracting(InvoiceLine::getKind, InvoiceLine::getAmount)
            .containsExactly(InvoiceLineKind.CANCELLATION_FEE, new BigDecimal("89.00"));
    }

    @Test
    void deletingABranchRemovesItsRooms() {
        Branch spare = data.branch("SPR");
        Room spareRoom = data.room(spare, standard, "301", "79.00");
        data.flushAndClear();
        Long branchId = spare.getId();
        Long roomId = spareRoom.getId();

        branches.delete(branches.findById(branchId).orElseThrow());
        branches.flush();

        assertThat(rowsWhere("branch", "id", branchId)).isZero();
        assertThat(rowsWhere("room", "id", roomId)).isZero();
        assertThat(rowsWhere("room", "id", den101.getId())).isEqualTo(1);
    }

    @Test
    void deletingARoomTypeThatARoomStillUsesIsRefused() {
        data.flushAndClear();
        Long typeId = standard.getId();

        assertThatThrownBy(() -> {
            roomTypes.delete(roomTypes.findById(typeId).orElseThrow());
            roomTypes.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    private InvoiceLine cancellationFeeLine() {
        InvoiceLine line = new InvoiceLine();
        line.setLineOrder(1);
        line.setKind(InvoiceLineKind.CANCELLATION_FEE);
        line.setDescription("Cancellation fee");
        line.setQuantity(1);
        line.setUnitAmount(new BigDecimal("89.00"));
        line.setAmount(new BigDecimal("89.00"));
        return line;
    }

    private int rowsWhere(String table, String column, Long value) {
        Integer count = jdbc.queryForObject(
            "select count(*) from " + table + " where " + column + " = ?", Integer.class, value);
        return count == null ? 0 : count;
    }
}
