package io.siggi.healthchecker.web.view;

import io.siggi.healthchecker.history.HistoryEvent;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * A downtime event as a viewer may see it. Fields the viewer may not see are {@code null}.
 *
 * @param title               the displayed title (custom, or {@code "{endpoint name} outage"})
 * @param customTitle         the custom title, or {@code null} (privileged only, for the rename form)
 * @param firstFailureMessage {@code public} endpoints (leak filter applied) and privileged viewers only
 * @param lastFailureMessage  {@code public} endpoints (leak filter applied) and privileged viewers only
 * @param notes               visible notes, oldest first
 * @param endedUnobserved     privileged only
 * @param endedByConfigChange privileged only
 * @param maintenanceId       privileged only
 */
public record EventView(String id, String endpointId, HistoryEvent.Kind kind, Instant start, Instant end,
                        String title, String customTitle, String firstFailureMessage, String lastFailureMessage,
                        List<NoteView> notes, Boolean endedUnobserved, Boolean endedByConfigChange,
                        String maintenanceId) {
    public EventView {
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
}
