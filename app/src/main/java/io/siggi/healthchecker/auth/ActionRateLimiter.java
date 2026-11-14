package io.siggi.healthchecker.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;

/**
 * Limits how often a key (e.g. a user id) may do something: at most {@code maxActions} within a
 * sliding {@code window}. Every allowed action counts; a refused one doesn't. In memory only.
 */
public final class ActionRateLimiter {
    /** Expired keys are swept after this many recorded actions. */
    private static final int SWEEP_EVERY = 1024;

    private final int maxActions;
    private final Duration window;
    private final Clock clock;
    private final Map<String, ArrayDeque<Instant>> actions = new HashMap<>();
    private int sinceSweep;

    public ActionRateLimiter(int maxActions, Duration window, Clock clock) {
        if (maxActions < 1) throw new IllegalArgumentException("maxActions must be at least 1");
        if (window.isNegative() || window.isZero()) throw new IllegalArgumentException("window must be positive");
        this.maxActions = maxActions;
        this.window = window;
        this.clock = clock;
    }

    /**
     * Records an action for {@code key} if it's allowed.
     *
     * @return empty if the action is allowed (and now counted), else how long until it would be
     */
    public synchronized Optional<Duration> tryAcquire(String key) {
        Instant now = clock.instant();
        ArrayDeque<Instant> times = actions.computeIfAbsent(key, k -> new ArrayDeque<>());
        prune(times, now);
        if (times.size() >= maxActions) {
            return Optional.of(Duration.between(now, times.peekFirst().plus(window)));
        }
        times.addLast(now);
        if (++sinceSweep >= SWEEP_EVERY) {
            sinceSweep = 0;
            for (Iterator<ArrayDeque<Instant>> it = actions.values().iterator(); it.hasNext(); ) {
                ArrayDeque<Instant> other = it.next();
                prune(other, now);
                if (other.isEmpty()) it.remove();
            }
        }
        return Optional.empty();
    }

    private void prune(ArrayDeque<Instant> times, Instant now) {
        Instant cutoff = now.minus(window);
        while (!times.isEmpty() && !times.peekFirst().isAfter(cutoff)) times.removeFirst();
    }
}
