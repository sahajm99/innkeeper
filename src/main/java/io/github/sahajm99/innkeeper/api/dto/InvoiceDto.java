package io.github.sahajm99.innkeeper.api.dto;

import java.math.BigDecimal;
import java.util.List;

import io.github.sahajm99.innkeeper.service.BookingView;

/**
 * The invoice as stored, plus the balance.
 *
 * <p>The totals are read back rather than recomputed, because the invoice is a record of what the
 * guest was told, and a tax rate that changes next year must not silently reprice a stay from last
 * year.</p>
 */
public record InvoiceDto(BigDecimal taxRate, BigDecimal roomSubtotal, BigDecimal tax,
    BigDecimal fines, BigDecimal cancellationFee, BigDecimal total, BigDecimal balance,
    String status, List<InvoiceLineDto> lines) {

    /** Null when the booking has no invoice, which only happens to data nobody has seeded. */
    public static InvoiceDto of(BookingView view) {
        BookingView.InvoiceView invoice = view.invoice();
        if (invoice == null) {
            return null;
        }
        return new InvoiceDto(invoice.taxRate(), invoice.roomSubtotal(), invoice.tax(),
            invoice.fines(), invoice.cancellationFee(), invoice.total(), invoice.balance(),
            invoice.status().name(),
            view.lines().stream().map(InvoiceLineDto::of).toList());
    }
}
