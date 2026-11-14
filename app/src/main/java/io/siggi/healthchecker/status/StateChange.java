package io.siggi.healthchecker.status;

import java.time.Instant;
import java.util.Objects;

/**
 * A transition of one endpoint's state, caused by {@code check}.
 *
 * <p>For a change to {@link State#DOWN}, the streak fields describe the failure streak
 * that led to it. For {@link State#DOWN} → {@link State#UP} they describe the outage that
 * just ended (its streak start is the outage start, its failure count includes every
 * failure while down). For {@link State#UNKNOWN} → {@link State#UP} they are {@code null}
 * and {@code 0}.
 *
 * @param endpointId          the endpoint
 * @param from                previous state
 * @param to                  new state
 * @param check               the check that caused the transition; {@code check.time()} is when it happened
 * @param streakStart         time of the first failed check in the streak, or {@code null}
 * @param firstFailureMessage message of that first failed check, or {@code null}
 * @param failureCount        number of consecutive failed checks in the streak
 */
public record StateChange(String endpointId, State from, State to, CheckRecord check,
                          Instant streakStart, String firstFailureMessage, int failureCount) {
    public StateChange {
        Objects.requireNonNull(endpointId, "endpointId");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(check, "check");
    }

    /** When the transition happened (the time of the check that caused it). */
    public Instant time() {
        return check.time();
    }
}
