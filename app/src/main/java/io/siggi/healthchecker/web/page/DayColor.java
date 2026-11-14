package io.siggi.healthchecker.web.page;

import io.siggi.healthchecker.config.DashboardConfig;
import io.siggi.healthchecker.history.DaySummary;

import java.util.Locale;
import java.util.OptionalLong;

/**
 * Colour of one day segment in the 60-day bar (and, later, of a calendar day). Thresholds come
 * from {@code dashboard.colors}: red below {@code redBelow}, orange below {@code orangeBelow},
 * yellow below {@code yellowBelow} when there was downtime, otherwise green. Blue marks a day with
 * scheduled maintenance and no counted downtime; grey a day without data.
 */
public enum DayColor {
    GREEN, YELLOW, ORANGE, RED, BLUE, GREY;

    /** Lowercase name, used as CSS class suffix and in the JSON API. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static DayColor of(DaySummary day, DashboardConfig colors) {
        OptionalLong hundredths = day.uptime().hundredths();
        if (hundredths.isEmpty()) {
            return GREY;
        }
        if (day.uptime().downtime().isZero()) {
            return day.maintenanceEvents() > 0 ? BLUE : GREEN;
        }
        double percent = hundredths.getAsLong() / 100.0;
        if (percent < colors.redBelow()) return RED;
        if (percent < colors.orangeBelow()) return ORANGE;
        if (percent < colors.yellowBelow()) return YELLOW;
        return GREEN;
    }
}
