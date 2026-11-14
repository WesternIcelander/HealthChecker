package io.siggi.healthchecker.status;

import java.time.Instant;
import java.util.List;

/**
 * Snapshot of one endpoint's live status.
 *
 * @param endpointId          the endpoint
 * @param state               current state
 * @param since               when the current state was entered ({@code null} while UNKNOWN)
 * @param failureThreshold    consecutive failures needed to go from UP to DOWN
 * @param failureCount        consecutive failed checks in the current streak (0 after a success)
 * @param streakStart         time of the first failed check in the current streak, or {@code null}
 * @param firstFailureMessage message of that first failed check, or {@code null}
 * @param lastCheck           most recent check, or {@code null}
 * @param recentChecks        up to {@link StatusRegistry#RECENT_CHECKS} checks, oldest first
 */
public record EndpointStatus(String endpointId, State state, Instant since, int failureThreshold,
                             int failureCount, Instant streakStart, String firstFailureMessage,
                             CheckRecord lastCheck, List<CheckRecord> recentChecks) {
    public EndpointStatus {
        recentChecks = List.copyOf(recentChecks);
    }
}
