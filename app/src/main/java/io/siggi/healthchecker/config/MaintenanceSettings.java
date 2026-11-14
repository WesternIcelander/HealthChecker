package io.siggi.healthchecker.config;

/**
 * The {@code maintenance} section of the config.
 *
 * @param announceBeforeHours hours before a window's start to send an "upcoming" announcement to its
 *                            {@code notifyChannels}; 0 for none
 */
public record MaintenanceSettings(boolean countsAsDowntime, int maxLengthDays, int upcomingDays,
                                  int announceBeforeHours) {
    /** Without an "upcoming" announcement. */
    public MaintenanceSettings(boolean countsAsDowntime, int maxLengthDays, int upcomingDays) {
        this(countsAsDowntime, maxLengthDays, upcomingDays, 0);
    }
}
