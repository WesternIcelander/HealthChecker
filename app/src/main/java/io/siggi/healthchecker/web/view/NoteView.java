package io.siggi.healthchecker.web.view;

import io.siggi.healthchecker.history.EventNote;

import java.time.Instant;

/**
 * A note as a viewer may see it.
 *
 * @param visibility the note's visibility; {@code null} for non-privileged viewers (who only see public notes)
 */
public record NoteView(String id, String author, Instant createdAt, Instant editedAt, String text,
                       EventNote.Visibility visibility) {
}
