package io.github.sahajm99.innkeeper.api.dto;

import java.time.LocalDate;

import io.github.sahajm99.innkeeper.service.CreateBookingCommand;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * A booking as a client sends it.
 *
 * <p>The annotations here are the shape of the request - present, plausible, short enough for the
 * column. Whether the dates make sense, whether the party fits and whether the room is free are
 * domain rules, and they belong to {@code BookingService}, which is also what the booking form
 * calls. So this record refuses nonsense early and leaves the rules to one place.</p>
 */
public record CreateBookingRequest(
    @NotNull Long roomId,
    @NotNull LocalDate checkIn,
    @NotNull LocalDate checkOut,
    @Min(1) @Max(8) int adults,
    @Min(0) @Max(8) int children,
    @NotBlank @Size(max = 80) String firstName,
    @NotBlank @Size(max = 80) String lastName,
    @NotBlank @Email @Size(max = 254) String email,
    @Size(max = 32) String phone,
    @Size(max = 500) String specialRequests) {

    public CreateBookingCommand toCommand(String actor) {
        return new CreateBookingCommand(roomId, checkIn, checkOut, adults, children, firstName,
            lastName, email, phone, specialRequests, actor);
    }
}
