package io.github.sahajm99.innkeeper.domain;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

/** The only way "today" is computed: the calendar date in the branch timezone. */
public final class BranchDates {

    private BranchDates() {
    }

    public static LocalDate today(Clock clock, ZoneId zone) {
        return LocalDate.now(clock.withZone(zone));
    }
}
