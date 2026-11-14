package io.siggi.healthchecker.config;

import com.google.gson.JsonObject;

import java.nio.file.Path;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The loaded and validated {@code config.json}.
 *
 * @param notificationChannels names of the configured notification channels
 * @param auth                 the raw {@code auth} section (env-substituted), parsed by the auth module
 * @param notifications        the raw {@code notifications} section (env-substituted), parsed by the notify module
 * @param warnings             problems that don't prevent startup
 * @param endpointEntries      the {@code endpoints} entries as written in the file, by id (for "Copy to dashboard")
 * @param categoryEntries      the {@code categories} entries as written in the file, by id
 */
public record Config(ServerConfig server, Path dataDir, ZoneId timezone, Path pluginsDir,
                     DashboardConfig dashboard, MaintenanceSettings maintenance,
                     EndpointDefaults defaults, List<SiteConfig> sites, List<CategoryConfig> categories,
                     List<EndpointDefinition> endpoints, Set<String> notificationChannels,
                     JsonObject auth, JsonObject notifications, List<String> warnings,
                     Map<String, Entry> endpointEntries, Map<String, Entry> categoryEntries) {
    /**
     * One {@code endpoints} or {@code categories} entry of the file.
     *
     * @param written  the entry as written, with {@code ${env:…}} placeholders
     * @param resolved the entry with placeholders substituted (what was parsed)
     */
    public record Entry(JsonObject written, JsonObject resolved) {
        public Entry {
            written = written.deepCopy();
            resolved = resolved.deepCopy();
        }

        @Override
        public JsonObject written() {
            return written.deepCopy();
        }

        @Override
        public JsonObject resolved() {
            return resolved.deepCopy();
        }

        /** Whether the entry uses {@code ${env:…}} placeholders. */
        public boolean usesPlaceholders() {
            return !written.equals(resolved);
        }
    }

    /** A config without the written entries (not available for "Copy to dashboard"). */
    public Config(ServerConfig server, Path dataDir, ZoneId timezone, Path pluginsDir,
                  DashboardConfig dashboard, MaintenanceSettings maintenance,
                  EndpointDefaults defaults, List<SiteConfig> sites, List<CategoryConfig> categories,
                  List<EndpointDefinition> endpoints, Set<String> notificationChannels,
                  JsonObject auth, JsonObject notifications, List<String> warnings) {
        this(server, dataDir, timezone, pluginsDir, dashboard, maintenance, defaults, sites, categories, endpoints,
                notificationChannels, auth, notifications, warnings, Map.of(), Map.of());
    }

    public Config {
        endpointEntries = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(endpointEntries));
        categoryEntries = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(categoryEntries));
        sites = List.copyOf(sites);
        categories = List.copyOf(categories);
        endpoints = List.copyOf(endpoints);
        notificationChannels = Set.copyOf(notificationChannels);
        auth = auth.deepCopy();
        notifications = notifications.deepCopy();
        warnings = List.copyOf(warnings);
    }

    /** The same config with the entries as written in the file. */
    Config withEntries(Map<String, Entry> endpointEntries, Map<String, Entry> categoryEntries) {
        return new Config(server, dataDir, timezone, pluginsDir, dashboard, maintenance, defaults, sites, categories,
                endpoints, notificationChannels, auth, notifications, warnings, endpointEntries, categoryEntries);
    }

    @Override
    public JsonObject auth() {
        return auth.deepCopy();
    }

    @Override
    public JsonObject notifications() {
        return notifications.deepCopy();
    }
}
