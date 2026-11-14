package io.siggi.healthchecker.config;

import com.google.gson.JsonObject;

import java.util.List;

/**
 * One endpoint's common settings, with defaults already applied, plus its
 * type-specific settings (every key the app doesn't handle itself).
 *
 * @param category    category id, or {@code null} for the implicit "Other" category
 * @param notifyChannels   channel names; empty means never notify
 * @param settings    type-specific settings, passed to the check plugin
 */
public record EndpointDefinition(String id, String name, String type, String category,
                                 Visibility visibility, int intervalSeconds, int timeoutMillis,
                                 int failureThreshold, List<String> notifyChannels, List<Integer> reminders,
                                 int reminderRepeatMinutes, JsonObject settings) {
    public EndpointDefinition {
        notifyChannels = List.copyOf(notifyChannels);
        reminders = List.copyOf(reminders);
        settings = settings.deepCopy();
    }

    @Override
    public JsonObject settings() {
        return settings.deepCopy();
    }
}
