package io.github.sahajm99.innkeeper.api.dto;

import java.time.LocalDate;

/**
 * What ten simultaneous bookings for one room on one night did.
 *
 * <p>{@code created} is always one and {@code rejected} always nine, which is the entire point of
 * the demo: the unique index over (room, night), not the application, is what makes that true.</p>
 */
public record RaceResult(int attempts, int created, int rejected, String winnerCode, String room,
    LocalDate night, long durationMs) {
}
