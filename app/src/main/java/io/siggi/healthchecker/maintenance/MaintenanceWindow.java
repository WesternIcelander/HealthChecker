package io.siggi.healthchecker.maintenance;

import io.siggi.healthchecker.history.EventNote;

import java.time.Instant;
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
 */
public record MaintenanceWindow(String id, int version, String title, String description, Instant start, Instant end,
                                List<String> endpoints, List<String> categories, List<String> notifyChannels,
                                List<EventNote> notes, String createdBy, String createdByName, Instant createdAt,
                                Instant updatedAt, Instant cancelledAt) {

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
    }

    /** The status at {@code now}: cancelled, else by {@code start} (inclusive) and {@code end} (exclusive). */
    public Status status(Instant now) {
        if (cancelledAt != null) return Status.CANCELLED;
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
