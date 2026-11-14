package io.siggi.healthchecker.maintenance;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * A change to one occurrence of a series, by the date the rule gives it (in the configured timezone): it's
 * skipped, or it has its own title, description and times. The rest (services, channels) follows the series.
 *
 * @param date        the occurrence's date from the rule
 * @param skipped     whether it's skipped; the other fields are then {@code null}
 * @param title       its title (edited occurrences)
 * @param description its description, or {@code null}
 * @param start       its start (edited occurrences)
 * @param end         its end (edited occurrences)
 */
public record OccurrenceChange(LocalDate date, boolean skipped, String title, String description, Instant start,
                               Instant end) {
    public OccurrenceChange {
        Objects.requireNonNull(date, "date");
        if (!skipped) {
            Objects.requireNonNull(title, "title");
            Objects.requireNonNull(start, "start");
            Objects.requireNonNull(end, "end");
        }
    }

    public static OccurrenceChange skip(LocalDate date) {
        return new OccurrenceChange(date, true, null, null, null, null);
    }

    public static OccurrenceChange edit(LocalDate date, String title, String description, Instant start,
                                        Instant end) {
        return new OccurrenceChange(date, false, title, description, start, end);
    }
}
