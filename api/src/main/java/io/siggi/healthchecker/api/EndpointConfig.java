package io.siggi.healthchecker.api;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/**
 * Read-only view of one endpoint's type-specific settings. Common settings ({@code id},
 * {@code name}, {@code type}, {@code visibility}, …) are handled by the app and aren't
 * visible here. Keys that no getter reads are rejected by the app, which catches typos.
 *
 * <p>Getters throw {@link ConfigException} (carrying the setting key) when a required
 * value is missing or a value has the wrong type.
 */
public interface EndpointConfig {
    /** The endpoint's id. */
    String id();

    /** The endpoint's display name. */
    String name();

    /** A required string setting. */
    String getString(String key) throws ConfigException;

    /** An optional string setting. */
    Optional<String> optString(String key) throws ConfigException;

    /** A required integer setting. */
    int getInt(String key) throws ConfigException;

    /** An optional integer setting. */
    OptionalInt optInt(String key) throws ConfigException;

    /** An optional boolean setting, {@code def} when absent. */
    boolean optBoolean(String key, boolean def) throws ConfigException;

    /** An optional list of strings, empty when absent. */
    List<String> optStringList(String key) throws ConfigException;

    /** All type-specific setting keys present for this endpoint. */
    Set<String> keys();

    /**
     * Declares a value that identifies the target (URL, host, port, …). Targets are
     * shown to logged-in users only, and any public message or detail containing one
     * is hidden from anonymous viewers.
     */
    void declareTarget(String label, String value);
}
