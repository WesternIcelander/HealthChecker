package io.siggi.healthchecker.notify;

import java.time.Instant;

/**
 * Delivery state of one channel, for logged-in users.
 *
 * @param lastSuccess    when a message was last accepted, or {@code null}
 * @param lastErrorTime  when the last failed attempt happened, or {@code null}
 * @param lastError      that failure's message, or {@code null}; kept after a later success,
 *                       so compare the times to see whether it's current
 * @param pending        messages waiting to be sent (queued or being retried)
 */
public record ChannelStatus(Instant lastSuccess, Instant lastErrorTime, String lastError, int pending) {
}
