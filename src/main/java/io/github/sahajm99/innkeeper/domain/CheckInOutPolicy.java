package io.github.sahajm99.innkeeper.domain;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/** When a booking may check in, and what checking out today costs and releases. */
public final class CheckInOutPolicy {

    /** {@code releaseNightsFrom} is null when the stay kept every night it booked. */
    public record CheckOutOutcome(int nightsCharged, int lateNights, LocalDate releaseNightsFrom) {
    }

    private CheckInOutPolicy() {
    }

    public static void assertCanCheckIn(BookingStatus status, StayPeriod stay, LocalDate today) {
        if (status != BookingStatus.CONFIRMED) {
            throw new IllegalBookingStateException("Only a confirmed booking can be checked in");
        }
        if (today.isBefore(stay.checkIn())) {
            throw new IllegalBookingStateException("Check-in cannot happen before the check-in date");
        }
        if (!today.isBefore(stay.checkOut())) {
            throw new IllegalBookingStateException("This stay has already ended");
        }
    }

    public static CheckOutOutcome checkOut(BookingStatus status, StayPeriod stay, LocalDate today) {
        if (status != BookingStatus.CHECKED_IN) {
            throw new IllegalBookingStateException("Only a checked-in booking can be checked out");
        }
        long slept = Math.max(1, ChronoUnit.DAYS.between(stay.checkIn(), today));
        long nightsCharged = Math.min(stay.nights(), slept);
        long lateNights = Math.max(0, ChronoUnit.DAYS.between(stay.checkOut(), today));
        LocalDate releaseFrom = stay.checkIn().plusDays(nightsCharged);
        return new CheckOutOutcome((int) nightsCharged, (int) lateNights,
            releaseFrom.isBefore(stay.checkOut()) ? releaseFrom : null);
    }
}
