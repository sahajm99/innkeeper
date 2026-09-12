package io.github.sahajm99.innkeeper.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;

import io.github.sahajm99.innkeeper.domain.InvoiceLineKind;
import io.github.sahajm99.innkeeper.model.Booking;
import io.github.sahajm99.innkeeper.model.Branch;
import io.github.sahajm99.innkeeper.model.Invoice;
import io.github.sahajm99.innkeeper.model.InvoiceLine;
import io.github.sahajm99.innkeeper.model.InvoiceStatus;
import io.github.sahajm99.innkeeper.model.RoomType;
import io.github.sahajm99.innkeeper.repository.InvoiceRepository;
import io.github.sahajm99.innkeeper.support.AbstractServiceTest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The bill behind a booking: a three night stay at $100 in a branch taxed at 8.25 percent, then
 * what a fine, a full payment and a part payment do to it.
 */
class InvoiceServiceTest extends AbstractServiceTest {

    private static final LocalDate OCT_1 = LocalDate.of(2026, 10, 1);
    private static final LocalDate OCT_4 = LocalDate.of(2026, 10, 4);

    @Autowired BookingService bookings;
    @Autowired InvoiceService invoices;
    @Autowired InvoiceRepository invoiceRepository;

    private Long roomId;
    private String code;

    @BeforeEach
    void bookThreeNightsAtAHundredADay() {
        inTransaction(data -> {
            Branch denton = data.branch("DEN");
            RoomType standard = data.roomType("STANDARD", 2);
            roomId = data.room(denton, standard, "101", "100.00").getId();
            return null;
        });
        code = bookings.create(new CreateBookingCommand(roomId, OCT_1, OCT_4, 2, 0,
            "Ada", "Lovelace", "ada@example.com", null, null, "guest")).getConfirmationCode();
    }

    @Test
    void theOpeningInvoiceTaxesTheRoomNightsOnly() {
        Invoice invoice = invoiceRepository.findByBookingId(booking().getId()).orElseThrow();

        assertThat(invoice.getTaxRate()).isEqualByComparingTo("0.0825");
        assertThat(invoice.getRoomSubtotal()).isEqualByComparingTo("300.00");
        assertThat(invoice.getTax()).isEqualByComparingTo("24.75");
        assertThat(invoice.getTotal()).isEqualByComparingTo("324.75");
        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.OPEN);
    }

    @Test
    void rebuildingAfterAFineAddsAFineLineAndLeavesTheTaxOnTheRoomNights() {
        Long bookingId = booking().getId();
        inTransaction(data -> {
            data.fine(entityManager.find(Booking.class, bookingId), "Smoking in the room", "50.00");
            return null;
        });

        Invoice invoice = invoices.rebuild(booking(), 3);

        assertThat(invoice.getRoomSubtotal()).isEqualByComparingTo("300.00");
        assertThat(invoice.getTax()).isEqualByComparingTo("24.75");
        assertThat(invoice.getFines()).isEqualByComparingTo("50.00");
        assertThat(invoice.getTotal()).isEqualByComparingTo("374.75");
        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.OPEN);
        assertThat(invoice.getLines()).extracting(InvoiceLine::getKind).containsExactly(
            InvoiceLineKind.ROOM_NIGHTS, InvoiceLineKind.TAX, InvoiceLineKind.FINE);
        assertThat(invoice.getLines()).extracting(InvoiceLine::getDescription)
            .contains("Smoking in the room");
        assertThat(count("invoice_line")).isEqualTo(3);
    }

    @Test
    void theInvoiceIsPaidOnceThePaymentsCoverTheTotal() {
        pay("324.75");

        Invoice invoice = invoices.rebuild(booking(), 3);

        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.PAID);
        assertThat(invoices.balance(invoice)).isEqualByComparingTo("0.00");
    }

    @Test
    void theBalanceIsWhatIsLeftAfterAPartPayment() {
        pay("100.00");

        Invoice invoice = invoices.rebuild(booking(), 3);

        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.OPEN);
        assertThat(invoices.balance(invoice)).isEqualByComparingTo("224.75");
    }

    @Test
    void rebuildingKeepsTheTaxRateTheInvoiceWasOpenedWith() {
        jdbc.update("update branch set tax_rate = ?", new BigDecimal("0.0000"));

        Invoice invoice = invoices.rebuild(booking(), 3);

        assertThat(invoice.getTaxRate()).isEqualByComparingTo("0.0825");
        assertThat(invoice.getTax()).isEqualByComparingTo("24.75");
    }

    @Test
    void anEarlyCheckOutRebuildsTheInvoiceOnFewerNights() {
        Invoice invoice = invoices.rebuild(booking(), 1);

        assertThat(invoice.getRoomSubtotal()).isEqualByComparingTo("100.00");
        assertThat(invoice.getTax()).isEqualByComparingTo("8.25");
        assertThat(invoice.getTotal()).isEqualByComparingTo("108.25");
        assertThat(count("invoice_line")).isEqualTo(2);
    }

    private Booking booking() {
        return bookings.requireByCode(code);
    }

    private void pay(String amount) {
        Long invoiceId = invoiceRepository.findByBookingId(booking().getId()).orElseThrow().getId();
        jdbc.update("insert into payment (invoice_id, amount, method) values (?, ?, 'CARD')",
            invoiceId, new BigDecimal(amount));
    }
}
