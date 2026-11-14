package io.siggi.healthchecker.notify;

import io.siggi.healthchecker.config.EndpointDefinition;
import io.siggi.healthchecker.status.State;
import io.siggi.healthchecker.status.StateChange;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * One thing to tell a channel about. Several of them may be combined into one message
 * ({@link NotificationBatch}).
 *
 * @param kind           what happened
 * @param endpointId     the endpoint, or {@code null} for notifications not about one endpoint
 *                       (tests, maintenance announcements)
 * @param endpointName   the endpoint's display name, or {@code null} like {@code endpointId}
 * @param time           when it happened
 * @param outageStart    when the outage started (down, reminder, recovered), else {@code null}
 * @param failureMessage the latest failure message, or {@code null}
 * @param text           free text (tests, announcements), or {@code null}
 * @param link           absolute link to the event permalink or page, or {@code null}
 */
public record Notification(Kind kind, String endpointId, String endpointName, Instant time,
                           Instant outageStart, String failureMessage, String text, String link) {
    public enum Kind { DOWN, REMINDER, RECOVERED, TEST, ANNOUNCEMENT }

    public Notification {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(time, "time");
    }

    /** How long the outage has lasted at {@link #time()}, if this is about an outage. */
    public Optional<Duration> outageDuration() {
        return outageStart == null ? Optional.empty() : Optional.of(Duration.between(outageStart, time));
    }

    /**
     * The notification a state change causes: DOWN for any change to DOWN, RECOVERED for
     * DOWN → UP, nothing otherwise (UNKNOWN → UP at startup is silent).
     */
    public static Optional<Notification> forChange(EndpointDefinition endpoint, StateChange change, String link) {
        if (change.to() == State.DOWN) {
            return Optional.of(new Notification(Kind.DOWN, endpoint.id(), endpoint.name(), change.time(),
                    change.streakStart() != null ? change.streakStart() : change.time(),
                    change.check().message(), null, link));
        }
        if (change.from() == State.DOWN && change.to() == State.UP) {
            return Optional.of(new Notification(Kind.RECOVERED, endpoint.id(), endpoint.name(), change.time(),
                    change.streakStart(), null, null, link));
        }
        return Optional.empty();
    }
}
