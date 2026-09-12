package io.github.sahajm99.innkeeper.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import io.github.sahajm99.innkeeper.domain.BookingStatus;
import io.github.sahajm99.innkeeper.domain.InvoiceLineKind;
import io.github.sahajm99.innkeeper.model.BookingEventType;
import io.github.sahajm99.innkeeper.model.InvoiceStatus;
import io.github.sahajm99.innkeeper.model.PaymentMethod;

/**
 * Everything the booking page, the invoice page and the API say about one booking, read once and
 * detached.
 *
 * <p>It exists because {@code spring.jpa.open-in-view} is off: a template rendering a JPA entity
 * outside its session either fails on a lazy association or quietly issues more queries. A record
 * cannot do either. It also keeps the two computed answers a guest needs - what cancelling costs
 * right now and when that price changes - next to the booking they belong to, instead of making
 * every page ask for them separately.</p>
 */
public record BookingView(
    String code,
    BookingStatus status,
    Long roomId,
    String roomNumber,
    String roomTypeCode,
    String roomTypeName,
    Long branchId,
    String branchCode,
    String branchName,
    ZoneId zone,
    LocalDate checkIn,
    LocalDate checkOut,
    int nights,
    int adults,
    int children,
    String guestFirstName,
    String guestLastName,
    String guestEmail,
    String guestPhone,
    String specialRequests,
    BigDecimal nightlyRate,
    BigDecimal cancellationFee,
    Instant createdAt,
    Instant checkedInAt,
    Instant checkedOutAt,
    Instant cancelledAt,
    InvoiceView invoice,
    List<LineView> lines,
    List<PaymentView> payments,
    List<FineView> fines,
    List<EventView> events,
    BigDecimal cancellationFeeNow,
    Instant cancellationDeadline) {

    /** The stored invoice totals plus the balance, which is a total less its payments. */
    public record InvoiceView(BigDecimal taxRate, BigDecimal roomSubtotal, BigDecimal tax,
        BigDecimal fines, BigDecimal cancellationFee, BigDecimal total, BigDecimal balance,
        InvoiceStatus status, Instant issuedAt) {
    }

    public record LineView(InvoiceLineKind kind, String description, int quantity,
        BigDecimal unitAmount, BigDecimal amount) {
    }

    public record PaymentView(BigDecimal amount, PaymentMethod method, String reference,
        Instant paidAt) {
    }

    public record FineView(String reason, BigDecimal amount, Instant issuedAt) {
    }

    public record EventView(BookingEventType type, Instant occurredAt, String actor, String note) {
    }

    public String guestName() {
        return guestFirstName + " " + guestLastName;
    }

    /** Only a confirmed booking can still be cancelled, which is what hides the button. */
    public boolean cancellable() {
        return status == BookingStatus.CONFIRMED;
    }

    /** True once the fee has started applying, so the confirm step can say what it will cost. */
    public boolean cancellationCosts() {
        return cancellationFeeNow != null && cancellationFeeNow.signum() > 0;
    }
}
