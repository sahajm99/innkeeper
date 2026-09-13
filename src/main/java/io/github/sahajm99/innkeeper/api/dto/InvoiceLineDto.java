package io.github.sahajm99.innkeeper.api.dto;

import java.math.BigDecimal;

import io.github.sahajm99.innkeeper.service.BookingView;

/** One line of the invoice: nights, tax, a fine or a cancellation fee. */
public record InvoiceLineDto(String kind, String description, int quantity, BigDecimal unitAmount,
    BigDecimal amount) {

    public static InvoiceLineDto of(BookingView.LineView line) {
        return new InvoiceLineDto(line.kind().name(), line.description(), line.quantity(),
            line.unitAmount(), line.amount());
    }
}
