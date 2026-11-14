package io.siggi.healthchecker.notify;

import java.time.Duration;
import java.util.Objects;

/**
 * Exponential backoff: attempt {@code n} (1-based) failing waits
 * {@code initialDelay × 2^(n-1)}, capped at {@code maxDelay}, before the next one; after
 * {@code maxAttempts} failed attempts the message is given up.
 */
public record RetryPolicy(Duration initialDelay, Duration maxDelay, int maxAttempts) {
    /** 10 s, 20 s, 40 s, … up to 10 min between attempts; 10 attempts (about 40 minutes). */
    public static final RetryPolicy DEFAULT = new RetryPolicy(Duration.ofSeconds(10), Duration.ofMinutes(10), 10);

    public RetryPolicy {
        Objects.requireNonNull(initialDelay, "initialDelay");
        Objects.requireNonNull(maxDelay, "maxDelay");
        if (initialDelay.isNegative() || maxDelay.compareTo(initialDelay) < 0 || maxAttempts < 1) {
            throw new IllegalArgumentException("invalid retry policy");
        }
    }

    /** The wait after failed attempt {@code attempt} (1-based). */
    public Duration delayAfter(int attempt) {
        Duration d = initialDelay;
        for (int i = 1; i < attempt && d.compareTo(maxDelay) < 0; i++) {
            d = d.multipliedBy(2);
        }
        return d.compareTo(maxDelay) > 0 ? maxDelay : d;
    }
}
