package io.github.sahajm99.innkeeper.service;

import java.time.LocalDate;

/**
 * Everything a booking needs, gathered from the booking form or the API before any rule has run.
 *
 * <p>It carries no defaults and no validation: {@code BookingService.create} is the one place the
 * rules are applied, so a command that breaks one still exists and can be reported field by field.
 * {@code actor} is the username that asked for it, or "guest" for a public booking.</p>
 */
public record CreateBookingCommand(Long roomId, LocalDate checkIn, LocalDate checkOut, int adults,
    int children, String firstName, String lastName, String email, String phone,
    String specialRequests, String actor) {
}
