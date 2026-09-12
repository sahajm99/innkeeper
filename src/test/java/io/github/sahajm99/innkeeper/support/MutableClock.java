package io.github.sahajm99.innkeeper.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A {@link Clock} a test can move, so the policies that read "now" can be walked past a deadline
 * without sleeping.
 *
 * <p>{@link #withZone(ZoneId)} shares the instant with the clock it came from, because
 * {@code BranchDates.today(clock, zone)} calls it on every lookup and would otherwise keep
 * handing back the instant the test started at.</p>
 */
public final class MutableClock extends Clock {

    private final AtomicReference<Instant> now;
    private final Instant start;
    private final ZoneId zone;

    public MutableClock(Instant start, ZoneId zone) {
        this(new AtomicReference<>(start), start, zone);
    }

    private MutableClock(AtomicReference<Instant> now, Instant start, ZoneId zone) {
        this.now = now;
        this.start = start;
        this.zone = zone;
    }

    /** Moves every view of this clock to the given instant. */
    public void set(Instant instant) {
        now.set(instant);
    }

    /** Puts the clock back to the instant it was built at. */
    public void reset() {
        now.set(start);
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId other) {
        return other.equals(zone) ? this : new MutableClock(now, start, other);
    }

    @Override
    public Instant instant() {
        return now.get();
    }
}
