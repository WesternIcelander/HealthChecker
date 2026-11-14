package io.siggi.healthchecker.status;

import java.time.Instant;
import java.util.List;

/**
 * Snapshot of one endpoint's live status.
 *
 * @param endpointId          the endpoint
 * @param state               current state as shown: {@link State#MAINTENANCE} during a maintenance window,
 *                            else {@code checkState}
 * @param since               when the current state was entered ({@code null} while UNKNOWN)
 * @param failureThreshold    consecutive failures needed to go from UP to DOWN
 * @param failureCount        consecutive failed checks in the current streak (0 after a success)
 * @param streakStart         time of the first failed check in the current streak, or {@code null}
 * @param firstFailureMessage message of that first failed check, or {@code null}
 * @param lastCheck           most recent check, or {@code null}
 * @param recentChecks        up to {@link StatusRegistry#RECENT_CHECKS} checks, oldest first
 * @param checkState          the state the checks give ({@code UNKNOWN}, {@code UP} or {@code DOWN}), also
 *                            during maintenance
 * @param maintenanceId       the maintenance window the endpoint is in, or {@code null}
 */
public record EndpointStatus(String endpointId, State state, Instant since, int failureThreshold,
                             int failureCount, Instant streakStart, String firstFailureMessage,
                             CheckRecord lastCheck, List<CheckRecord> recentChecks, State checkState,
                             String maintenanceId) {
    public EndpointStatus {
        recentChecks = List.copyOf(recentChecks);
        if (checkState == null) {
            checkState = state;
        }
    }

    /** Outside maintenance: {@code checkState} is {@code state}. */
    public EndpointStatus(String endpointId, State state, Instant since, int failureThreshold,
                          int failureCount, Instant streakStart, String firstFailureMessage,
                          CheckRecord lastCheck, List<CheckRecord> recentChecks) {
        this(endpointId, state, since, failureThreshold, failureCount, streakStart, firstFailureMessage, lastCheck,
                recentChecks, state, null);
    }

    public boolean inMaintenance() {
        return maintenanceId != null;
    }
}
