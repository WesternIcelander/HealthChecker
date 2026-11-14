package io.siggi.healthchecker.config;

/** The {@code maintenance} section of the config. */
public record MaintenanceSettings(boolean countsAsDowntime, int maxLengthDays, int upcomingDays) {
}
