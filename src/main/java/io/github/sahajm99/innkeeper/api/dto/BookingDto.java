package io.github.sahajm99.innkeeper.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import io.github.sahajm99.innkeeper.model.Room;
import io.github.sahajm99.innkeeper.service.BookingView;

/**
 * One stay, with everything a client needs to render it without asking again: the room, the
 * invoice, what cancelling costs right now and when that price changes.
 */
public record BookingDto(String code, String status, RoomDto room, LocalDate checkIn,
    LocalDate checkOut, long nights, int adults, int children, GuestDto guest,
    String specialRequests, InvoiceDto invoice, BigDecimal cancellationFeeNow,
    Instant cancellationDeadline, Instant createdAt) {

    /**
     * The room comes in beside the view because {@link BookingView} carries only what the booking
     * pages print of it - number, type and branch - and the API also hands back the floor, the
     * occupancy and the status.
     */
    public static BookingDto of(BookingView view, Room room) {
        return new BookingDto(
            view.code(),
            view.status().name(),
            RoomDto.of(room),
            view.checkIn(),
            view.checkOut(),
            view.nights(),
            view.adults(),
            view.children(),
            new GuestDto(view.guestFirstName(), view.guestLastName(), view.guestEmail()),
            view.specialRequests(),
            InvoiceDto.of(view),
            view.cancellationFeeNow(),
            view.cancellationDeadline(),
            view.createdAt());
    }
}
