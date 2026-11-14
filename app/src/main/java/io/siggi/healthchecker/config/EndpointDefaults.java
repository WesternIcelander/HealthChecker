package io.siggi.healthchecker.config;

import java.util.List;

/**
 * The {@code defaults} section: values used by endpoints that don't set their own.
 *
 * @param notifyChannels             channel names; empty means no notifications
 * @param reminders             minutes after the outage started, strictly increasing
 * @param reminderRepeatMinutes repeat interval after the last reminder; 0 = off
 */
public record EndpointDefaults(int intervalSeconds, int timeoutMillis, int failureThreshold,
                               Visibility visibility, List<String> notifyChannels, List<Integer> reminders,
                               int reminderRepeatMinutes) {
    public EndpointDefaults {
        notifyChannels = List.copyOf(notifyChannels);
        reminders = List.copyOf(reminders);
    }
}
