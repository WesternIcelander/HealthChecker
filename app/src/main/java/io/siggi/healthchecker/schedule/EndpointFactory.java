package io.siggi.healthchecker.schedule;

import io.siggi.healthchecker.api.CheckPlugin;
import io.siggi.healthchecker.api.Checker;
import io.siggi.healthchecker.api.ConfigException;
import io.siggi.healthchecker.config.EndpointDefinition;
import io.siggi.healthchecker.plugin.JsonEndpointConfig;
import io.siggi.healthchecker.plugin.PluginManager;

import java.util.Objects;

/**
 * Builds {@link Endpoint}s: finds the plugin for the type, lets it validate the settings
 * and create a checker, and rejects settings nobody read.
 */
public final class EndpointFactory {
    private final PluginManager plugins;

    public EndpointFactory(PluginManager plugins) {
        this.plugins = Objects.requireNonNull(plugins, "plugins");
    }

    /**
     * @throws ConfigException if the type is unknown, the plugin rejects the settings,
     *                         a setting was never read, or the plugin fails
     */
    public Endpoint create(EndpointDefinition definition) throws ConfigException {
        CheckPlugin plugin = plugins.require(definition.type());
        JsonEndpointConfig config = new JsonEndpointConfig(definition.id(), definition.name(), definition.settings());
        Checker checker;
        try {
            checker = plugin.create(config);
        } catch (RuntimeException | LinkageError e) {
            throw new ConfigException("plugin \"" + definition.type() + "\" failed: " + e);
        }
        if (checker == null) {
            throw new ConfigException("plugin \"" + definition.type() + "\" returned no checker");
        }
        try {
            config.checkNoUnreadKeys();
        } catch (ConfigException e) {
            closeQuietly(checker);
            throw e;
        }
        return new Endpoint(definition, config.targets(), checker);
    }

    static void closeQuietly(Checker checker) {
        try {
            checker.close();
        } catch (Exception | LinkageError e) {
            System.getLogger(EndpointFactory.class.getName())
                    .log(System.Logger.Level.WARNING, "Closing a checker failed", e);
        }
    }
}
