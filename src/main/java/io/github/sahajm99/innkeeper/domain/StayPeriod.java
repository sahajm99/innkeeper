package io.github.sahajm99.innkeeper.domain;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/** The nights a booking holds: check-in inclusive, check-out exclusive. */
public record StayPeriod(LocalDate checkIn, LocalDate checkOut) {

    public static final int MAX_NIGHTS = 30;
    public static final int MAX_DAYS_AHEAD = 365;

    public static StayPeriod validated(LocalDate checkIn, LocalDate checkOut, LocalDate today) {
        if (!checkOut.isAfter(checkIn)) {
            throw new BookingRuleException("checkOut", "Check-out must be after check-in");
        }
        if (ChronoUnit.DAYS.between(checkIn, checkOut) > MAX_NIGHTS) {
            throw new BookingRuleException("checkOut", "Stays are limited to " + MAX_NIGHTS + " nights");
        }
        if (checkIn.isBefore(today)) {
            throw new BookingRuleException("checkIn", "Check-in cannot be before today");
        }
        if (ChronoUnit.DAYS.between(today, checkIn) > MAX_DAYS_AHEAD) {
            throw new BookingRuleException("checkIn", "Check-in can be at most " + MAX_DAYS_AHEAD + " days ahead");
        }
        return new StayPeriod(checkIn, checkOut);
    }

    public long nights() {
        return ChronoUnit.DAYS.between(checkIn, checkOut);
    }

    public List<LocalDate> nightDates() {
        return checkIn.datesUntil(checkOut).toList();
    }

    public boolean covers(LocalDate night) {
        return !night.isBefore(checkIn) && night.isBefore(checkOut);
    }

    public boolean overlaps(StayPeriod other) {
        return checkIn.isBefore(other.checkOut()) && other.checkIn().isBefore(checkOut);
    }
}
