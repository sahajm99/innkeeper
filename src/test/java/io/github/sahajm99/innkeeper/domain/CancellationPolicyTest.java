package io.github.sahajm99.innkeeper.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

class CancellationPolicyTest {

    private static final ZoneId CHICAGO = ZoneId.of("America/Chicago");
    private static final LocalDate CHECK_IN = LocalDate.of(2026, 10, 3);
    private static final BigDecimal RATE = new BigDecimal("89.00");

    @Test
    void theDeadlineIsFortyEightHoursBeforeThreePmOnTheCheckInDate() {
        Instant deadline = CancellationPolicy.deadline(CHECK_IN, CHICAGO);

        assertThat(deadline).isEqualTo(OffsetDateTime.parse("2026-10-01T15:00-05:00").toInstant());
    }

    @Test
    void cancellingOneSecondBeforeTheDeadlineIsFree() {
        Instant now = CancellationPolicy.deadline(CHECK_IN, CHICAGO).minusSeconds(1);

        assertThat(CancellationPolicy.feeAt(now, CHECK_IN, CHICAGO, RATE)).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void cancellingExactlyAtTheDeadlineIsFree() {
        Instant now = CancellationPolicy.deadline(CHECK_IN, CHICAGO);

        assertThat(CancellationPolicy.feeAt(now, CHECK_IN, CHICAGO, RATE)).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void cancellingOneSecondAfterTheDeadlineCostsOneNight() {
        Instant now = CancellationPolicy.deadline(CHECK_IN, CHICAGO).plusSeconds(1);

        assertThat(CancellationPolicy.feeAt(now, CHECK_IN, CHICAGO, RATE)).isEqualTo(new BigDecimal("89.00"));
    }

    @Test
    void theFreeWindowStaysFortyEightHoursWideAcrossTheEndOfDaylightSaving() {
        Instant threePmOnTheCheckInDate = OffsetDateTime.parse("2026-11-02T15:00-06:00").toInstant();

        Instant deadline = CancellationPolicy.deadline(LocalDate.of(2026, 11, 2), CHICAGO);

        assertThat(Duration.between(deadline, threePmOnTheCheckInDate)).isEqualTo(Duration.ofHours(48));
        // Daylight saving ends on 2026-11-01, so a fixed 48 hours back lands at 16:00 local, not 15:00.
        assertThat(deadline).isEqualTo(OffsetDateTime.parse("2026-10-31T16:00-05:00").toInstant());
    }

    @Test
    void aConfirmedBookingCanBeCancelled() {
        assertThatCode(() -> CancellationPolicy.assertCancellable(BookingStatus.CONFIRMED))
            .doesNotThrowAnyException();
    }

    @Test
    void aCheckedInBookingCannotBeCancelled() {
        assertThatThrownBy(() -> CancellationPolicy.assertCancellable(BookingStatus.CHECKED_IN))
            .isInstanceOf(IllegalBookingStateException.class)
            .hasMessage("Only a confirmed booking can be cancelled");
    }
}
