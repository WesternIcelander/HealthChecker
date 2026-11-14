package io.siggi.healthchecker.history;

import java.time.Instant;
import java.util.Objects;

/**
 * A period during which an endpoint was being monitored. Time outside every period is
 * "no data", counted as neither up nor down.
 */
public record CoveragePeriod(Instant start, Instant end) {
    public CoveragePeriod {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        if (end.isBefore(start)) {
            throw new IllegalArgumentException("end " + end + " is before start " + start);
        }
    }
}
