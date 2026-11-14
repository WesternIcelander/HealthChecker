package io.siggi.healthchecker.history;

import java.time.Instant;
import java.util.Objects;

/**
 * A note on a downtime event.
 *
 * @param id         unique id
 * @param author     display name of the user who wrote it
 * @param createdAt  when it was written
 * @param editedAt   when it was last edited, or {@code null}
 * @param text       plain text, newlines kept
 * @param visibility who may see it
 */
public record EventNote(String id, String author, Instant createdAt, Instant editedAt, String text,
                        Visibility visibility) {
    public enum Visibility {
        PUBLIC, PRIVATE
    }

    public EventNote {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(author, "author");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(visibility, "visibility");
    }
}
