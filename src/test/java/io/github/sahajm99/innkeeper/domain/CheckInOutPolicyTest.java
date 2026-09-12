package io.github.sahajm99.innkeeper.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import io.github.sahajm99.innkeeper.domain.CheckInOutPolicy.CheckOutOutcome;

class CheckInOutPolicyTest {

    private static final LocalDate CHECK_IN = LocalDate.of(2026, 10, 1);
    private static final LocalDate CHECK_OUT = LocalDate.of(2026, 10, 4);
    private static final StayPeriod STAY = new StayPeriod(CHECK_IN, CHECK_OUT);

    @Test
    void checkInIsAllowedOnTheCheckInDate() {
        assertThatCode(() -> CheckInOutPolicy.assertCanCheckIn(BookingStatus.CONFIRMED, STAY, CHECK_IN))
            .doesNotThrowAnyException();
    }

    @Test
    void aLateArrivalCanStillCheckInTheDayAfterTheCheckInDate() {
        assertThatCode(() -> CheckInOutPolicy.assertCanCheckIn(BookingStatus.CONFIRMED, STAY, CHECK_IN.plusDays(1)))
            .doesNotThrowAnyException();
    }

    @Test
    void checkInIsRefusedTheDayBeforeTheCheckInDate() {
        assertThatThrownBy(() ->
                CheckInOutPolicy.assertCanCheckIn(BookingStatus.CONFIRMED, STAY, CHECK_IN.minusDays(1)))
            .isInstanceOf(IllegalBookingStateException.class)
            .hasMessage("Check-in cannot happen before the check-in date");
    }

    @Test
    void checkInIsRefusedOnTheCheckOutDate() {
        assertThatThrownBy(() -> CheckInOutPolicy.assertCanCheckIn(BookingStatus.CONFIRMED, STAY, CHECK_OUT))
            .isInstanceOf(IllegalBookingStateException.class)
            .hasMessage("This stay has already ended");
    }

    @Test
    void onlyAConfirmedBookingCanCheckIn() {
        assertThatThrownBy(() -> CheckInOutPolicy.assertCanCheckIn(BookingStatus.CHECKED_IN, STAY, CHECK_IN))
            .isInstanceOf(IllegalBookingStateException.class)
            .hasMessage("Only a confirmed booking can be checked in");
    }

    @Test
    void checkingOutOnTheCheckOutDateChargesEveryBookedNight() {
        CheckOutOutcome outcome = CheckInOutPolicy.checkOut(BookingStatus.CHECKED_IN, STAY, CHECK_OUT);

        assertThat(outcome.nightsCharged()).isEqualTo(3);
        assertThat(outcome.lateNights()).isZero();
        assertThat(outcome.releaseNightsFrom()).isNull();
    }

    @Test
    void checkingOutEarlyChargesTheNightsSleptAndReleasesTheRest() {
        CheckOutOutcome outcome =
            CheckInOutPolicy.checkOut(BookingStatus.CHECKED_IN, STAY, LocalDate.of(2026, 10, 3));

        assertThat(outcome.nightsCharged()).isEqualTo(2);
        assertThat(outcome.lateNights()).isZero();
        assertThat(outcome.releaseNightsFrom()).isEqualTo(LocalDate.of(2026, 10, 3));
    }

    @Test
    void checkingOutOnTheArrivalDayStillChargesOneNight() {
        CheckOutOutcome outcome = CheckInOutPolicy.checkOut(BookingStatus.CHECKED_IN, STAY, CHECK_IN);

        assertThat(outcome.nightsCharged()).isEqualTo(1);
        assertThat(outcome.lateNights()).isZero();
        assertThat(outcome.releaseNightsFrom()).isEqualTo(LocalDate.of(2026, 10, 2));
    }

    @Test
    void checkingOutLateChargesTheBookedNightsAndCountsTheLateOnes() {
        CheckOutOutcome outcome =
            CheckInOutPolicy.checkOut(BookingStatus.CHECKED_IN, STAY, LocalDate.of(2026, 10, 6));

        assertThat(outcome.nightsCharged()).isEqualTo(3);
        assertThat(outcome.lateNights()).isEqualTo(2);
        assertThat(outcome.releaseNightsFrom()).isNull();
    }

    @Test
    void onlyACheckedInBookingCanCheckOut() {
        assertThatThrownBy(() -> CheckInOutPolicy.checkOut(BookingStatus.CONFIRMED, STAY, CHECK_OUT))
            .isInstanceOf(IllegalBookingStateException.class)
            .hasMessage("Only a checked-in booking can be checked out");
    }
}
