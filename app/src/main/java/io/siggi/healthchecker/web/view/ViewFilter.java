package io.siggi.healthchecker.web.view;

import io.siggi.healthchecker.config.Visibility;
import io.siggi.healthchecker.history.EventNote;
import io.siggi.healthchecker.history.HistoryEvent;
import io.siggi.healthchecker.schedule.Endpoint;
import io.siggi.healthchecker.status.CheckRecord;
import io.siggi.healthchecker.status.EndpointStatus;
import io.siggi.healthchecker.status.State;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Decides what a viewer may see of endpoints, checks and events. Used by the HTML pages
 * and the JSON API alike.
 *
 * <p>Views are built by copying allowed fields (an allow-list), never by removing fields
 * from the source data, so anything new is private until it's added here. For viewers
 * without {@code view.private}:
 * <ul>
 *   <li>{@code private} endpoints are not visible at all;</li>
 *   <li>{@code public-status} endpoints show name, state and recent checks as pass/fail,
 *       plus event titles, times and public notes;</li>
 *   <li>{@code public} endpoints additionally show type, latency, check messages, details
 *       and failure messages on events, each passed through the leak filter;</li>
 *   <li>targets are never shown.</li>
 * </ul>
 *
 * <p>Leak filter: a message, detail entry (key or value) or failure message that contains
 * any declared target value, current or past (case-insensitively), is left out entirely, with
 * no placeholder.
 */
public final class ViewFilter {
    /** Whether the viewer may see the endpoint at all (a {@code 404} otherwise). */
    public boolean canSee(Viewer viewer, Endpoint endpoint) {
        return viewer.viewPrivate() || endpoint.visibility() != Visibility.PRIVATE;
    }

    /**
     * The endpoint as the viewer may see it, or empty if it's not visible.
     *
     * @param status its live status, or {@code null} if none is known yet
     */
    public Optional<EndpointView> endpoint(Viewer viewer, Endpoint endpoint, EndpointStatus status) {
        if (!canSee(viewer, endpoint)) {
            return Optional.empty();
        }
        Level level = level(viewer, endpoint);
        State state = status != null ? status.state() : State.UNKNOWN;
        CheckView lastCheck = null;
        List<CheckView> recent = new ArrayList<>();
        if (status != null) {
            if (level != Level.STATUS && status.lastCheck() != null) {
                lastCheck = check(level, endpoint, status.lastCheck());
            }
            for (CheckRecord record : status.recentChecks()) {
                recent.add(check(level, endpoint, record));
            }
        }
        boolean full = level == Level.PRIVILEGED;
        return Optional.of(new EndpointView(
                endpoint.id(),
                endpoint.name(),
                level != Level.STATUS ? endpoint.type() : null,
                state,
                status != null ? status.since() : null,
                lastCheck,
                recent,
                full ? endpoint.visibility() : null,
                full ? endpoint.targets() : null,
                full ? endpoint.definition().notifyChannels() : null,
                full ? endpoint.definition().reminders() : null,
                full ? endpoint.definition().reminderRepeatMinutes() : null));
    }

    /** One check of the endpoint as the viewer may see it, or empty if the endpoint isn't visible. */
    public Optional<CheckView> check(Viewer viewer, Endpoint endpoint, CheckRecord record) {
        if (!canSee(viewer, endpoint)) {
            return Optional.empty();
        }
        return Optional.of(check(level(viewer, endpoint), endpoint, record));
    }

    /**
     * One event of the endpoint as the viewer may see it, or empty if the endpoint isn't visible.
     *
     * @throws IllegalArgumentException if the event belongs to another endpoint
     */
    public Optional<EventView> event(Viewer viewer, Endpoint endpoint, HistoryEvent event) {
        if (!event.endpointId().equals(endpoint.id())) {
            throw new IllegalArgumentException("event " + event.id() + " belongs to " + event.endpointId()
                    + ", not " + endpoint.id());
        }
        if (!canSee(viewer, endpoint)) {
            return Optional.empty();
        }
        Level level = level(viewer, endpoint);
        boolean full = level == Level.PRIVILEGED;
        List<NoteView> notes = new ArrayList<>();
        for (EventNote note : event.notes()) {
            if (full) {
                notes.add(new NoteView(note.id(), note.author(), note.createdAt(), note.editedAt(), note.text(),
                        note.visibility()));
            } else if (note.visibility() == EventNote.Visibility.PUBLIC) {
                notes.add(new NoteView(note.id(), note.author(), note.createdAt(), note.editedAt(), note.text(),
                        null));
            }
        }
        return Optional.of(new EventView(
                event.id(),
                event.endpointId(),
                event.kind(),
                event.start(),
                event.end(),
                event.title() != null ? event.title() : defaultTitle(endpoint.name()),
                full ? event.title() : null,
                text(level, endpoint, event.firstFailureMessage()),
                text(level, endpoint, event.lastFailureMessage()),
                notes,
                full ? event.endedUnobserved() : null,
                full ? event.endedByConfigChange() : null,
                full ? event.maintenanceId() : null));
    }

    /** The title shown for an event without a custom title. */
    public static String defaultTitle(String endpointName) {
        return endpointName + " outage";
    }

    /**
     * Whether the text contains any of the endpoint's declared target values, current or
     * {@linkplain Endpoint#pastTargets() past} (case-insensitively).
     */
    public static boolean leaks(Endpoint endpoint, String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        String haystack = text.toLowerCase(Locale.ROOT);
        for (String value : endpoint.leakValues()) {
            if (!value.isEmpty() && haystack.contains(value.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private enum Level {
        /** Everything, including targets. */
        PRIVILEGED,
        /** A {@code public} endpoint for a non-privileged viewer: details with the leak filter. */
        PUBLIC,
        /** A {@code public-status} endpoint for a non-privileged viewer: status only. */
        STATUS
    }

    private static Level level(Viewer viewer, Endpoint endpoint) {
        if (viewer.viewPrivate()) {
            return Level.PRIVILEGED;
        }
        return switch (endpoint.visibility()) {
            case PUBLIC -> Level.PUBLIC;
            case PUBLIC_STATUS -> Level.STATUS;
            case PRIVATE -> throw new IllegalStateException("private endpoint " + endpoint.id() + " is not visible");
        };
    }

    private static CheckView check(Level level, Endpoint endpoint, CheckRecord record) {
        return switch (level) {
            case STATUS -> new CheckView(record.time(), record.success(), null, null, null);
            case PRIVILEGED -> new CheckView(record.time(), record.success(), record.latency(), record.message(),
                    record.result().details());
            case PUBLIC -> {
                Map<String, Object> details = new LinkedHashMap<>();
                for (Map.Entry<String, Object> e : record.result().details().entrySet()) {
                    if (!leaks(endpoint, e.getKey()) && !leaks(endpoint, String.valueOf(e.getValue()))) {
                        details.put(e.getKey(), e.getValue());
                    }
                }
                yield new CheckView(record.time(), record.success(), record.latency(),
                        text(level, endpoint, record.message()), Collections.unmodifiableMap(details));
            }
        };
    }

    /** A message or failure message: as is when privileged, leak-filtered when public, hidden otherwise. */
    private static String text(Level level, Endpoint endpoint, String text) {
        return switch (level) {
            case PRIVILEGED -> text;
            case PUBLIC -> leaks(endpoint, text) ? null : text;
            case STATUS -> null;
        };
    }
}
