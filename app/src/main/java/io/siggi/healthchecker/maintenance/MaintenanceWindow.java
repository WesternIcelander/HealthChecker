package io.siggi.healthchecker.maintenance;

import io.siggi.healthchecker.history.EventNote;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A scheduled maintenance window.
 *
 * @param id             unique id (a UUID)
 * @param version        counts this window's changes, starting at 1; edits name the version they're based on
 * @param title          e.g. "Weekly reboot"
 * @param description    optional longer text, or {@code null}
 * @param start          planned start
 * @param end            planned end, after {@code start}
 * @param endpoints      affected endpoint ids
 * @param categories     affected category ids; they cover the category's members at any moment of the window
 * @param notifyChannels notification channels for announcements; empty for none
 * @param notes          notes, oldest first
 * @param createdBy      the creator's user id, or {@code null}
 * @param createdByName  the creator's display name at the time, or {@code null}
 * @param createdAt      when it was created
 * @param updatedAt      when it was last changed (any change, including notes and cancelling)
 * @param cancelledAt    when it was cancelled, or {@code null}
 * @param recurrence     for a series: its rule, else {@code null}. A series' {@code start} and {@code end} are its
 *                       first possible occurrence and give every occurrence its local times; the series itself
 *                       never runs, its occurrences do (see {@link MaintenanceStore#occurrences})
 * @param exceptions     for a series: skipped and edited occurrences, by date; empty otherwise
 * @param seriesId       for an occurrence of a series: the series' id, else {@code null}
 * @param occurrence     for an occurrence of a series: its date from the rule, else {@code null}
 */
public record MaintenanceWindow(String id, int version, String title, String description, Instant start, Instant end,
                                List<String> endpoints, List<String> categories, List<String> notifyChannels,
                                List<EventNote> notes, String createdBy, String createdByName, Instant createdAt,
                                Instant updatedAt, Instant cancelledAt, Recurrence recurrence,
                                List<OccurrenceChange> exceptions, String seriesId, LocalDate occurrence) {

    /** Where a window is at a given moment. */
    public enum Status {
        SCHEDULED, IN_PROGRESS, COMPLETED, CANCELLED
    }

    public MaintenanceWindow {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        endpoints = List.copyOf(endpoints);
        categories = List.copyOf(categories);
        notifyChannels = List.copyOf(notifyChannels);
        notes = List.copyOf(notes);
        exceptions = exceptions == null ? List.of() : List.copyOf(exceptions);
        if ((seriesId == null) != (occurrence == null)) {
            throw new IllegalArgumentException("seriesId and occurrence go together");
        }
        if (recurrence != null && seriesId != null) {
            throw new IllegalArgumentException("an occurrence can't be a series");
        }
    }

    /** A one-off window. */
    public MaintenanceWindow(String id, int version, String title, String description, Instant start, Instant end,
                             List<String> endpoints, List<String> categories, List<String> notifyChannels,
                             List<EventNote> notes, String createdBy, String createdByName, Instant createdAt,
                             Instant updatedAt, Instant cancelledAt) {
        this(id, version, title, description, start, end, endpoints, categories, notifyChannels, notes, createdBy,
                createdByName, createdAt, updatedAt, cancelledAt, null, List.of(), null, null);
    }

    /** Whether this is a recurring series (rather than a one-off window or an occurrence). */
    public boolean isSeries() {
        return recurrence != null;
    }

    /** The change for the occurrence on {@code date} of this series, if any. */
    public Optional<OccurrenceChange> exception(LocalDate date) {
        return exceptions.stream().filter(e -> e.date().equals(date)).findFirst();
    }

    /**
     * The status at {@code now}: cancelled, else by {@code start} (inclusive) and {@code end} (exclusive). A
     * series that isn't cancelled is always {@code SCHEDULED}; its occurrences have their own status.
     */
    public Status status(Instant now) {
        if (cancelledAt != null) return Status.CANCELLED;
        if (recurrence != null) return Status.SCHEDULED;
        if (now.isBefore(start)) return Status.SCHEDULED;
        if (now.isBefore(end)) return Status.IN_PROGRESS;
        return Status.COMPLETED;
    }

    /** Whether it covers an endpoint, directly or through the category it's in (may be {@code null}). */
    public boolean affects(String endpointId, String categoryId) {
        return endpoints.contains(endpointId) || (categoryId != null && categories.contains(categoryId));
    }

    public Optional<EventNote> note(String noteId) {
        return notes.stream().filter(n -> n.id().equals(noteId)).findFirst();
    }
}
