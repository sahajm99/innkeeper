package io.github.sahajm99.innkeeper.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.time.LocalDate;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

class StayPeriodTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 1);

    @Test
    void checkOutOnTheCheckInDateIsRejected() {
        BookingRuleException thrown = rejected(() -> StayPeriod.validated(TODAY, TODAY, TODAY));

        assertThat(thrown.field()).isEqualTo("checkOut");
        assertThat(thrown.getMessage()).isEqualTo("Check-out must be after check-in");
    }

    @Test
    void checkOutBeforeTheCheckInDateIsRejected() {
        BookingRuleException thrown =
            rejected(() -> StayPeriod.validated(TODAY.plusDays(3), TODAY.plusDays(1), TODAY));

        assertThat(thrown.field()).isEqualTo("checkOut");
        assertThat(thrown.getMessage()).isEqualTo("Check-out must be after check-in");
    }

    @Test
    void aStayOfThirtyOneNightsIsRejected() {
        BookingRuleException thrown = rejected(() -> StayPeriod.validated(TODAY, TODAY.plusDays(31), TODAY));

        assertThat(thrown.field()).isEqualTo("checkOut");
        assertThat(thrown.getMessage()).isEqualTo("Stays are limited to 30 nights");
    }

    @Test
    void aStayOfThirtyNightsIsAccepted() {
        StayPeriod stay = StayPeriod.validated(TODAY, TODAY.plusDays(30), TODAY);

        assertThat(stay.nights()).isEqualTo(30);
    }

    @Test
    void checkInYesterdayIsRejected() {
        BookingRuleException thrown =
            rejected(() -> StayPeriod.validated(TODAY.minusDays(1), TODAY.plusDays(1), TODAY));

        assertThat(thrown.field()).isEqualTo("checkIn");
        assertThat(thrown.getMessage()).isEqualTo("Check-in cannot be before today");
    }

    @Test
    void checkInTodayIsAccepted() {
        StayPeriod stay = StayPeriod.validated(TODAY, TODAY.plusDays(1), TODAY);

        assertThat(stay.checkIn()).isEqualTo(TODAY);
        assertThat(stay.nights()).isEqualTo(1);
    }

    @Test
    void checkInThreeHundredSixtySixDaysAheadIsRejected() {
        LocalDate checkIn = TODAY.plusDays(366);

        BookingRuleException thrown =
            rejected(() -> StayPeriod.validated(checkIn, checkIn.plusDays(1), TODAY));

        assertThat(thrown.field()).isEqualTo("checkIn");
        assertThat(thrown.getMessage()).isEqualTo("Check-in can be at most 365 days ahead");
    }

    @Test
    void checkInThreeHundredSixtyFiveDaysAheadIsAccepted() {
        LocalDate checkIn = TODAY.plusDays(365);

        StayPeriod stay = StayPeriod.validated(checkIn, checkIn.plusDays(1), TODAY);

        assertThat(stay.checkIn()).isEqualTo(checkIn);
    }

    @Test
    void nightsCountsTheNightsBetweenTheDates() {
        StayPeriod stay = new StayPeriod(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 4));

        assertThat(stay.nights()).isEqualTo(3);
    }

    @Test
    void nightDatesListsEveryNightSlept() {
        StayPeriod stay = new StayPeriod(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 4));

        assertThat(stay.nightDates()).containsExactly(
            LocalDate.of(2026, 10, 1),
            LocalDate.of(2026, 10, 2),
            LocalDate.of(2026, 10, 3));
    }

    @Test
    void coversEveryNightOfTheStayButNotTheCheckOutDate() {
        StayPeriod stay = new StayPeriod(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 4));

        assertThat(stay.covers(LocalDate.of(2026, 9, 30))).isFalse();
        assertThat(stay.covers(LocalDate.of(2026, 10, 1))).isTrue();
        assertThat(stay.covers(LocalDate.of(2026, 10, 3))).isTrue();
        assertThat(stay.covers(LocalDate.of(2026, 10, 4))).isFalse();
    }

    @Test
    void staysThatShareANightOverlap() {
        StayPeriod stay = new StayPeriod(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 4));
        StayPeriod overlapping = new StayPeriod(LocalDate.of(2026, 10, 3), LocalDate.of(2026, 10, 6));

        assertThat(stay.overlaps(overlapping)).isTrue();
        assertThat(overlapping.overlaps(stay)).isTrue();
    }

    @Test
    void backToBackStaysDoNotOverlap() {
        StayPeriod first = new StayPeriod(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 4));
        StayPeriod second = new StayPeriod(LocalDate.of(2026, 10, 4), LocalDate.of(2026, 10, 6));

        assertThat(first.overlaps(second)).isFalse();
        assertThat(second.overlaps(first)).isFalse();
    }

    private static BookingRuleException rejected(ThrowingCallable call) {
        Throwable thrown = catchThrowable(call);

        assertThat(thrown).isInstanceOf(BookingRuleException.class);
        return (BookingRuleException) thrown;
    }
}
