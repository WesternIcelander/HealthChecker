package io.siggi.healthchecker.history;

import java.time.LocalDate;
import java.util.Objects;

/**
 * One calendar day of an endpoint, in the configured timezone.
 *
 * @param date               the day
 * @param uptime             coverage and counted downtime within the day (up to now for today)
 * @param events             downtime events counted against uptime that overlap the day
 * @param maintenanceEvents  {@code maintenance} events that overlap the day and aren't counted
 *                           against uptime (always 0 when maintenance counts as downtime)
 */
public record DaySummary(LocalDate date, Uptime uptime, int events, int maintenanceEvents) {
    public DaySummary {
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(uptime, "uptime");
    }
}
