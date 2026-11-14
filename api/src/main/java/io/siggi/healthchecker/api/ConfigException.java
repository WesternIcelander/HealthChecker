package io.siggi.healthchecker.api;

import java.util.Optional;

/**
 * An invalid endpoint setting. Can carry the setting key, so the dashboard shows the
 * error next to that field.
 */
public class ConfigException extends Exception {
    private final String settingKey;

    public ConfigException(String message) {
        this(null, message);
    }

    public ConfigException(String settingKey, String message) {
        super(message);
        this.settingKey = settingKey;
    }

    /** The setting this error is about, if it's about one. */
    public Optional<String> settingKey() {
        return Optional.ofNullable(settingKey);
    }
}
