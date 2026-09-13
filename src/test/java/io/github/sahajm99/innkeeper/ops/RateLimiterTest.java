package io.github.sahajm99.innkeeper.ops;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import io.github.sahajm99.innkeeper.support.MutableClock;

import org.junit.jupiter.api.Test;

/** The abuse floor: twenty a window, a window an hour, and a map that cannot grow without end. */
class RateLimiterTest {

    private static final Instant NOON = Instant.parse("2026-09-12T17:00:00Z");
    private static final int PER_HOUR = 20;

    private final MutableClock clock = new MutableClock(NOON, ZoneOffset.UTC);
    private final RateLimiter limiter = new RateLimiter(PER_HOUR, clock);

    @Test
    void theFirstTwentyAreLetThroughAndTheTwentyFirstIsNot() {
        for (int attempt = 1; attempt <= PER_HOUR; attempt++) {
            assertThat(limiter.tryAcquire("203.0.113.9")).as("attempt %d", attempt).isTrue();
        }

        assertThat(limiter.tryAcquire("203.0.113.9")).isFalse();
    }

    @Test
    void theWindowReopensAnHourLater() {
        for (int attempt = 1; attempt <= PER_HOUR; attempt++) {
            limiter.tryAcquire("203.0.113.9");
        }
        assertThat(limiter.tryAcquire("203.0.113.9")).isFalse();

        clock.set(NOON.plus(Duration.ofHours(1)));

        assertThat(limiter.tryAcquire("203.0.113.9")).isTrue();
    }

    @Test
    void oneAddressRunningOutDoesNotStopAnother() {
        for (int attempt = 1; attempt <= PER_HOUR; attempt++) {
            limiter.tryAcquire("203.0.113.9");
        }

        assertThat(limiter.tryAcquire("203.0.113.9")).isFalse();
        assertThat(limiter.tryAcquire("198.51.100.4")).isTrue();
    }

    @Test
    void tenThousandAndOneAddressesKeepTheMapAtTenThousand() {
        for (int key = 0; key <= RateLimiter.MAX_KEYS; key++) {
            assertThat(limiter.tryAcquire("10.0." + (key / 256) + "." + (key % 256))).isTrue();
        }

        assertThat(limiter.size()).isEqualTo(RateLimiter.MAX_KEYS);
    }

    @Test
    void anExpiredWindowIsForgottenRatherThanCounted() {
        limiter.tryAcquire("203.0.113.9");
        clock.set(NOON.plus(Duration.ofHours(2)));

        for (int attempt = 1; attempt <= PER_HOUR; attempt++) {
            assertThat(limiter.tryAcquire("203.0.113.9")).as("attempt %d", attempt).isTrue();
        }
        assertThat(limiter.tryAcquire("203.0.113.9")).isFalse();
    }
}
