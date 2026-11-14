package io.siggi.healthchecker.config;

import com.google.gson.JsonObject;

import java.nio.file.Path;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

/**
 * The loaded and validated {@code config.json}.
 *
 * @param notificationChannels names of the configured notification channels
 * @param auth                 the raw {@code auth} section (env-substituted), parsed by the auth module
 * @param notifications        the raw {@code notifications} section (env-substituted), parsed by the notify module
 * @param warnings             problems that don't prevent startup
 */
public record Config(ServerConfig server, Path dataDir, ZoneId timezone, Path pluginsDir,
                     DashboardConfig dashboard, MaintenanceSettings maintenance,
                     EndpointDefaults defaults, List<SiteConfig> sites, List<CategoryConfig> categories,
                     List<EndpointDefinition> endpoints, Set<String> notificationChannels,
                     JsonObject auth, JsonObject notifications, List<String> warnings) {
    public Config {
        sites = List.copyOf(sites);
        categories = List.copyOf(categories);
        endpoints = List.copyOf(endpoints);
        notificationChannels = Set.copyOf(notificationChannels);
        auth = auth.deepCopy();
        notifications = notifications.deepCopy();
        warnings = List.copyOf(warnings);
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
