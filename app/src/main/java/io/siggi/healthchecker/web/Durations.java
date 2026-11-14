package io.siggi.healthchecker.web;

import java.time.Duration;

/** Human-readable durations for the dashboard. */
public final class Durations {
    private Durations() {
    }

    /**
     * E.g. {@code 0s}, {@code 45s}, {@code 12m}, {@code 1h 12m}, {@code 2d 3h}: at most two units,
     * truncated (seconds only below a minute).
     */
    public static String format(Duration duration) {
        long seconds = Math.max(0, duration.getSeconds());
        if (seconds < 60) {
            return seconds + "s";
        }
        long minutes = seconds / 60;
        long hours = minutes / 60;
        long days = hours / 24;
        if (days > 0) {
            return hours % 24 == 0 ? days + "d" : days + "d " + hours % 24 + "h";
        }
        if (hours > 0) {
            return minutes % 60 == 0 ? hours + "h" : hours + "h " + minutes % 60 + "m";
        }
        return minutes + "m";
    }
}
