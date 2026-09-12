package io.github.sahajm99.innkeeper.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

/** Free cancellation until 48 hours before 3 pm on the check-in date; one night's rate after that. */
public final class CancellationPolicy {

    public static final LocalTime CHECK_IN_TIME = LocalTime.of(15, 0);
    public static final Duration FREE_WINDOW = Duration.ofHours(48);

    private CancellationPolicy() {
    }

    public static Instant deadline(LocalDate checkIn, ZoneId zone) {
        return checkIn.atTime(CHECK_IN_TIME).atZone(zone).toInstant().minus(FREE_WINDOW);
    }

    public static BigDecimal feeAt(Instant now, LocalDate checkIn, ZoneId zone, BigDecimal nightlyRate) {
        return now.isAfter(deadline(checkIn, zone))
            ? nightlyRate.setScale(2, RoundingMode.HALF_UP)
            : BigDecimal.ZERO;
    }

    public static void assertCancellable(BookingStatus status) {
        if (status != BookingStatus.CONFIRMED) {
            throw new IllegalBookingStateException("Only a confirmed booking can be cancelled");
        }
    }
}
