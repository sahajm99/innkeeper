package io.github.sahajm99.innkeeper.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowable;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

class OccupancyRuleTest {

    @Test
    void aBookingWithoutAnAdultIsRejected() {
        BookingRuleException thrown = rejected(() -> OccupancyRule.check(0, 2, 4));

        assertThat(thrown.field()).isEqualTo("adults");
        assertThat(thrown.getMessage()).isEqualTo("At least one adult is required");
    }

    @Test
    void moreGuestsThanTheRoomSleepsIsRejected() {
        BookingRuleException thrown = rejected(() -> OccupancyRule.check(2, 1, 2));

        assertThat(thrown.field()).isEqualTo("children");
        assertThat(thrown.getMessage()).isEqualTo("This room sleeps up to 2 guests");
    }

    @Test
    void tooManyAdultsAndNoChildrenBlamesTheAdultsField() {
        BookingRuleException thrown = rejected(() -> OccupancyRule.check(3, 0, 2));

        assertThat(thrown.field()).isEqualTo("adults");
        assertThat(thrown.getMessage()).isEqualTo("This room sleeps up to 2 guests");
    }

    @Test
    void aRoomThatSleepsOneIsDescribedInTheSingular() {
        BookingRuleException thrown = rejected(() -> OccupancyRule.check(2, 0, 1));

        assertThat(thrown.getMessage()).isEqualTo("This room sleeps up to 1 guest");
    }

    @Test
    void guestsWithinTheMaximumOccupancyAreAccepted() {
        assertThatCode(() -> OccupancyRule.check(2, 0, 2)).doesNotThrowAnyException();
    }

    private static BookingRuleException rejected(ThrowingCallable call) {
        Throwable thrown = catchThrowable(call);

        assertThat(thrown).isInstanceOf(BookingRuleException.class);
        return (BookingRuleException) thrown;
    }
}
