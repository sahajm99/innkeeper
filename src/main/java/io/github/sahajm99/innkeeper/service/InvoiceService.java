package io.github.sahajm99.innkeeper.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import io.github.sahajm99.innkeeper.domain.BookingStatus;
import io.github.sahajm99.innkeeper.domain.InvoiceCalculator;
import io.github.sahajm99.innkeeper.domain.InvoiceCalculator.FineLine;
import io.github.sahajm99.innkeeper.model.Booking;
import io.github.sahajm99.innkeeper.model.Invoice;
import io.github.sahajm99.innkeeper.model.InvoiceLine;
import io.github.sahajm99.innkeeper.model.InvoiceStatus;
import io.github.sahajm99.innkeeper.model.Payment;
import io.github.sahajm99.innkeeper.repository.BookingRepository;
import io.github.sahajm99.innkeeper.repository.FineRepository;
import io.github.sahajm99.innkeeper.repository.InvoiceRepository;
import io.github.sahajm99.innkeeper.repository.PaymentRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps a booking's invoice in step with the booking.
 *
 * <p>Nothing here decides an amount: {@link InvoiceCalculator} does, and this stores what it
 * returns, so the lines a guest reads and the totals a report sums always come from the same
 * arithmetic. The invoice is rebuilt from scratch on every transition while the booking is open -
 * created, cancelled, fined, checked out - which is cheaper to reason about than patching totals
 * and cannot drift.</p>
 *
 * <p>The tax rate is snapshotted when the invoice is first opened and never read from the branch
 * again, so a rate change tomorrow does not reprice a stay agreed today.</p>
 */
@Service
public class InvoiceService {

    private static final BigDecimal ZERO_MONEY = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

    private final InvoiceRepository invoices;
    private final BookingRepository bookings;
    private final FineRepository fines;
    private final PaymentRepository payments;
    private final Clock clock;

    public InvoiceService(InvoiceRepository invoices, BookingRepository bookings,
            FineRepository fines, PaymentRepository payments, Clock clock) {
        this.invoices = invoices;
        this.bookings = bookings;
        this.fines = fines;
        this.payments = payments;
        this.clock = clock;
    }

    /**
     * Recomputes the invoice for the booking and returns it, opening one the first time.
     *
     * <p>{@code nightsCharged} is what the stay is billed for, which is not always what it booked:
     * a cancellation charges none, an early check-out charges the nights actually slept.</p>
     */
    @Transactional
    public Invoice rebuild(Booking booking, int nightsCharged) {
        Invoice invoice = invoices.findByBookingId(booking.getId()).orElseGet(() -> open(booking));
        List<FineLine> billed = fines.findByBookingIdOrderByIssuedAt(booking.getId()).stream()
            .map(fine -> new FineLine(fine.getReason(), fine.getAmount()))
            .toList();

        InvoiceCalculator.Result result = InvoiceCalculator.calculate(Math.max(0, nightsCharged),
            booking.getNightlyRate(), invoice.getTaxRate(), billed,
            Objects.requireNonNullElse(booking.getCancellationFee(), ZERO_MONEY));

        invoice.setRoomSubtotal(result.roomSubtotal());
        invoice.setTax(result.tax());
        invoice.setFines(result.fines());
        invoice.setCancellationFee(result.cancellationFee());
        invoice.setTotal(result.total());
        invoice.setStatus(statusOf(result.total(), paidSoFar(invoice), booking.getStatus()));
        invoice.setUpdatedAt(clock.instant());
        invoice.replaceLines(linesOf(invoice, result));
        return invoices.save(invoice);
    }

    /** What is still owed: the stored total less every payment recorded against the invoice. */
    public BigDecimal balance(Invoice invoice) {
        return invoice.getTotal().subtract(paidSoFar(invoice)).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * An empty invoice carrying the branch tax rate. The booking is re-read through the repository
     * because a caller may hand over a detached one, and the branch behind it has to be reachable
     * for exactly as long as it takes to copy the rate.
     */
    private Invoice open(Booking booking) {
        Booking attached = bookings.getReferenceById(booking.getId());
        Instant now = clock.instant();
        Invoice invoice = new Invoice();
        invoice.setBooking(attached);
        invoice.setTaxRate(attached.getRoom().getBranch().getTaxRate());
        invoice.setRoomSubtotal(ZERO_MONEY);
        invoice.setTax(ZERO_MONEY);
        invoice.setFines(ZERO_MONEY);
        invoice.setCancellationFee(ZERO_MONEY);
        invoice.setTotal(ZERO_MONEY);
        invoice.setStatus(InvoiceStatus.OPEN);
        invoice.setIssuedAt(now);
        invoice.setUpdatedAt(now);
        return invoice;
    }

    /**
     * OPEN while anything is owed, PAID once the payments reach the total, VOID when a cancelled
     * booking owes nothing at all - which is what a free cancellation leaves behind.
     */
    private InvoiceStatus statusOf(BigDecimal total, BigDecimal paid, BookingStatus booking) {
        if (total.signum() == 0) {
            return booking == BookingStatus.CANCELLED ? InvoiceStatus.VOID : InvoiceStatus.OPEN;
        }
        return paid.compareTo(total) >= 0 ? InvoiceStatus.PAID : InvoiceStatus.OPEN;
    }

    private BigDecimal paidSoFar(Invoice invoice) {
        if (invoice.getId() == null) {
            return ZERO_MONEY;
        }
        return payments.findByInvoiceIdOrderByPaidAt(invoice.getId()).stream()
            .map(Payment::getAmount)
            .reduce(ZERO_MONEY, BigDecimal::add);
    }

    private List<InvoiceLine> linesOf(Invoice invoice, InvoiceCalculator.Result result) {
        List<InvoiceLine> lines = new ArrayList<>();
        int order = 0;
        for (InvoiceCalculator.Line computed : result.lines()) {
            InvoiceLine line = new InvoiceLine();
            line.setInvoice(invoice);
            line.setLineOrder(++order);
            line.setKind(computed.kind());
            line.setDescription(computed.description());
            line.setQuantity(computed.quantity());
            line.setUnitAmount(computed.unitAmount());
            line.setAmount(computed.amount());
            lines.add(line);
        }
        return lines;
    }
}
