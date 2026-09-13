package io.github.sahajm99.innkeeper.ops;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An abuse floor for a public demo: so many writes an hour from one address.
 *
 * <p>A fixed window rather than a token bucket, because the point is not smoothing traffic - it is
 * stopping a script from filling the demo database between two nightly resets, and a window that
 * anyone can reason about ("twenty an hour") is the one to print on the 429 page.</p>
 *
 * <p>It is in memory on purpose. One free-tier instance has one process, a restart is a reset and
 * nobody is harmed by that. What would harm it is unbounded growth, so the map is capped at
 * {@value #MAX_KEYS} entries: entries are re-inserted whenever a window starts, which makes the
 * iteration order window-start order, and the eldest window is dropped when the cap is reached.</p>
 */
public class RateLimiter {

    /** Roughly a megabyte of addresses, which is more than a free-tier demo will ever see. */
    public static final int MAX_KEYS = 10_000;

    private static final Duration WINDOW = Duration.ofHours(1);

    private final int perHour;
    private final Clock clock;
    private final Map<String, Window> windows = new BoundedWindows();

    public RateLimiter(int perHour, Clock clock) {
        this.perHour = perHour;
        this.clock = clock;
    }

    /**
     * Counts one attempt against the key and says whether it is allowed. An expired window is
     * thrown away rather than counted, so an address that was refused an hour ago starts again.
     */
    public synchronized boolean tryAcquire(String key) {
        Instant now = clock.instant();
        Window window = windows.get(key);
        if (window == null || !now.isBefore(window.expiresAt())) {
            windows.remove(key);
            window = new Window(now.plus(WINDOW));
            windows.put(key, window);
        }
        return window.take(perHour);
    }

    /** How many addresses are being tracked; never more than {@value #MAX_KEYS}. */
    public synchronized int size() {
        return windows.size();
    }

    /** One address for one hour: when the window ends and how much of it has been used. */
    private static final class Window {

        private final Instant expiresAt;
        private int used;

        private Window(Instant expiresAt) {
            this.expiresAt = expiresAt;
        }

        private Instant expiresAt() {
            return expiresAt;
        }

        private boolean take(int allowance) {
            if (used >= allowance) {
                return false;
            }
            used++;
            return true;
        }
    }

    /** Insertion-ordered, so the eldest entry is the window that started longest ago. */
    private static final class BoundedWindows extends LinkedHashMap<String, Window> {

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Window> eldest) {
            return size() > MAX_KEYS;
        }
    }
}
