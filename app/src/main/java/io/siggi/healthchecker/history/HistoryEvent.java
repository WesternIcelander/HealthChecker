package io.siggi.healthchecker.history;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * One downtime event (an outage, or downtime during scheduled maintenance).
 *
 * @param id                  unique id
 * @param endpointId          the endpoint
 * @param kind                outage or maintenance
 * @param start               time of the first failed check of the streak that led to DOWN
 * @param end                 time of the check that brought it back UP, or (if
 *                            {@code endedUnobserved}) the last observed failed check;
 *                            {@code null} while ongoing
 * @param title               custom title, or {@code null} for the default
 * @param firstFailureMessage message of the first failed check
 * @param lastFailureMessage  message of the most recent failed check
 * @param lastFailureAt       time of the most recent failed check
 * @param notes               notes, oldest first
 * @param endedUnobserved     monitoring stopped (app shutdown or crash, endpoint removed) while it was down
 * @param endedByConfigChange closed because the endpoint's check settings changed
 * @param maintenanceId       linked maintenance window, or {@code null}
 */
public record HistoryEvent(String id, String endpointId, Kind kind, Instant start, Instant end, String title,
                           String firstFailureMessage, String lastFailureMessage, Instant lastFailureAt,
                           List<EventNote> notes, boolean endedUnobserved, boolean endedByConfigChange,
                           String maintenanceId) {
    public enum Kind {
        OUTAGE, MAINTENANCE
    }

    public HistoryEvent {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(endpointId, "endpointId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(lastFailureAt, "lastFailureAt");
        if (end != null && end.isBefore(start)) {
            throw new IllegalArgumentException("end " + end + " is before start " + start);
        }
        notes = List.copyOf(notes);
    }

    public boolean ongoing() {
        return end == null;
    }

    /** {@code end - start}, or until {@code now} while ongoing. */
    public Duration duration(Instant now) {
        Instant until = end != null ? end : now;
        return until.isBefore(start) ? Duration.ZERO : Duration.between(start, until);
    }

    HistoryEvent withLastFailure(Instant at, String message) {
        return new HistoryEvent(id, endpointId, kind, start, end, title, firstFailureMessage, message, at, notes,
                endedUnobserved, endedByConfigChange, maintenanceId);
    }

    HistoryEvent withTitle(String newTitle) {
        return new HistoryEvent(id, endpointId, kind, start, end, newTitle, firstFailureMessage, lastFailureMessage,
                lastFailureAt, notes, endedUnobserved, endedByConfigChange, maintenanceId);
    }

    HistoryEvent withNotes(List<EventNote> newNotes) {
        return new HistoryEvent(id, endpointId, kind, start, end, title, firstFailureMessage, lastFailureMessage,
                lastFailureAt, newNotes, endedUnobserved, endedByConfigChange, maintenanceId);
    }

    HistoryEvent closed(Instant at, boolean unobserved) {
        Instant closeAt = at.isBefore(start) ? start : at;
        return new HistoryEvent(id, endpointId, kind, start, closeAt, title, firstFailureMessage, lastFailureMessage,
                lastFailureAt, notes, unobserved, endedByConfigChange, maintenanceId);
    }
}
