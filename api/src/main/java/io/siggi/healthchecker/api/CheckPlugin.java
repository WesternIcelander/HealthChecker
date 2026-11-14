package io.siggi.healthchecker.api;

import java.util.List;

/**
 * A check type. Discovered via {@link java.util.ServiceLoader}
 * ({@code META-INF/services/io.siggi.healthchecker.api.CheckPlugin}).
 */
public interface CheckPlugin {
    /** Config {@code "type"} value this plugin handles, e.g. {@code "minecraft"}. Lowercase, unique. */
    String type();

    /** Highest API version this plugin needs. The app refuses plugins newer than itself. */
    default int apiVersion() {
        return 1;
    }

    /** Human-readable name for the dashboard's type dropdown, e.g. "Minecraft (Java Edition)". */
    default String displayName() {
        return type();
    }

    /** Describes the type-specific settings, so the dashboard can render a form for them. */
    List<SettingSpec> settings();

    /**
     * Validates this endpoint's type-specific settings and builds a checker. Called at
     * startup, and whenever an endpoint is created or edited in the dashboard.
     *
     * @throws ConfigException if a setting is missing or invalid
     */
    Checker create(EndpointConfig config) throws ConfigException;
}
