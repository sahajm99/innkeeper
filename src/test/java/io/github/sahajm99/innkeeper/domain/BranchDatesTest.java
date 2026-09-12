package io.github.sahajm99.innkeeper.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

class BranchDatesTest {

    @Test
    void todayIsTheCalendarDateInTheBranchTimezone() {
        Clock clock = Clock.fixed(Instant.parse("2026-09-13T00:30:00Z"), ZoneOffset.UTC);

        assertThat(BranchDates.today(clock, ZoneId.of("America/Chicago"))).isEqualTo(LocalDate.of(2026, 9, 12));
        assertThat(BranchDates.today(clock, ZoneOffset.UTC)).isEqualTo(LocalDate.of(2026, 9, 13));
    }
}
