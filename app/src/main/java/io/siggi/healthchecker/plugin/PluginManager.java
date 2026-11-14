package io.siggi.healthchecker.plugin;

import io.siggi.healthchecker.api.CheckPlugin;
import io.siggi.healthchecker.api.Checker;
import io.siggi.healthchecker.api.ConfigException;
import io.siggi.healthchecker.config.EndpointDefinition;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * The check types available to endpoints: built-ins found with {@link ServiceLoader} on the
 * app class path, plus every {@code *.jar} in the plugins directory, each in its own
 * {@link URLClassLoader} whose parent is the app class loader (so plugins share the API and
 * Gson with the app).
 *
 * <p>Loading collects every problem before failing: a duplicate type (naming both
 * sources), an invalid type name, a plugin that needs a newer API version than
 * {@link #API_VERSION}, or a jar whose services can't be loaded.
 */
public final class PluginManager implements AutoCloseable {
    /** The plugin API version this app implements. Plugins needing a higher one are refused. */
    public static final int API_VERSION = 1;

    /** Where built-in plugins come from, in {@link LoadedPlugin#source()}. */
    public static final String BUILT_IN = "built-in";

    private static final Pattern TYPE = Pattern.compile("[a-z0-9-]+");

    /** One loaded plugin and where it came from ({@link #BUILT_IN} or the jar path). */
    public record LoadedPlugin(CheckPlugin plugin, String type, String source) {
        /** A log line: type, class and source. */
        public String describe() {
            return "type \"" + type + "\" (" + plugin.getClass().getName() + ") from " + source;
        }
    }

    private final Map<String, LoadedPlugin> plugins;
    private final List<String> warnings;
    private final List<URLClassLoader> loaders;

    private PluginManager(Map<String, LoadedPlugin> plugins, List<String> warnings, List<URLClassLoader> loaders) {
        this.plugins = Collections.unmodifiableMap(plugins);
        this.warnings = List.copyOf(warnings);
        this.loaders = List.copyOf(loaders);
    }

    /** Loads the built-ins from the app class path and the jars in {@code pluginsDir}. */
    public static PluginManager load(Path pluginsDir) throws PluginLoadException {
        ClassLoader appLoader = PluginManager.class.getClassLoader();
        List<CheckPlugin> builtIns = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        try {
            for (CheckPlugin p : ServiceLoader.load(CheckPlugin.class, appLoader)) {
                builtIns.add(p);
            }
        } catch (ServiceConfigurationError e) {
            errors.add(BUILT_IN + ": failed to load: " + e.getMessage());
        }
        return load(builtIns, pluginsDir, appLoader, errors);
    }

    /**
     * Loads the given built-ins plus the jars in {@code pluginsDir} (which may be missing,
     * meaning no plugins), using {@code parent} as the jars' parent class loader.
     */
    public static PluginManager load(Collection<? extends CheckPlugin> builtIns, Path pluginsDir,
                                     ClassLoader parent) throws PluginLoadException {
        return load(builtIns, pluginsDir, parent, new ArrayList<>());
    }

    private static PluginManager load(Collection<? extends CheckPlugin> builtIns, Path pluginsDir,
                                      ClassLoader parent, List<String> errors) throws PluginLoadException {
        Map<String, LoadedPlugin> plugins = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        List<URLClassLoader> loaders = new ArrayList<>();

        for (CheckPlugin p : builtIns) {
            register(p, BUILT_IN, plugins, errors);
        }

        for (Path jar : jars(pluginsDir, errors)) {
            String source = jar.toString();
            URLClassLoader loader;
            try {
                loader = new URLClassLoader("plugin:" + jar.getFileName(), new URL[]{jar.toUri().toURL()}, parent);
            } catch (MalformedURLException e) {
                errors.add(source + ": " + e.getMessage());
                continue;
            }
            loaders.add(loader);
            int found = 0;
            try {
                for (ServiceLoader.Provider<CheckPlugin> provider
                        : ServiceLoader.load(CheckPlugin.class, loader).stream().toList()) {
                    // ServiceLoader also sees the parent's services files; keep only this jar's own.
                    if (provider.type().getClassLoader() != loader) continue;
                    found++;
                    register(provider.get(), source, plugins, errors);
                }
            } catch (ServiceConfigurationError | LinkageError e) {
                errors.add(source + ": failed to load: " + e);
                continue;
            }
            if (found == 0) {
                warnings.add(source + ": no " + CheckPlugin.class.getName() + " found in META-INF/services");
            }
        }

        if (!errors.isEmpty()) {
            closeAll(loaders);
            throw new PluginLoadException(errors);
        }
        return new PluginManager(plugins, warnings, loaders);
    }

    private static List<Path> jars(Path dir, List<String> errors) {
        if (dir == null || Files.notExists(dir)) return List.of();
        if (!Files.isDirectory(dir)) {
            errors.add(dir + ": plugins directory is not a directory");
            return List.of();
        }
        List<Path> jars = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.jar")) {
            for (Path p : stream) {
                if (Files.isRegularFile(p)) jars.add(p);
            }
        } catch (IOException e) {
            errors.add(dir + ": can't list plugins directory: " + e);
        }
        jars.sort(Comparator.comparing(p -> p.getFileName().toString()));
        return jars;
    }

    private static void register(CheckPlugin plugin, String source, Map<String, LoadedPlugin> plugins,
                                 List<String> errors) {
        String className = plugin.getClass().getName();
        String type;
        int apiVersion;
        try {
            type = plugin.type();
            apiVersion = plugin.apiVersion();
        } catch (RuntimeException | LinkageError e) {
            errors.add(source + ": " + className + " failed: " + e);
            return;
        }
        if (type == null || !TYPE.matcher(type).matches()) {
            errors.add(source + ": " + className + " has invalid type "
                    + (type == null ? "null" : "\"" + type + "\"") + " (must match [a-z0-9-]+)");
            return;
        }
        if (apiVersion > API_VERSION) {
            errors.add(source + ": type \"" + type + "\" (" + className + ") needs plugin API version "
                    + apiVersion + ", but this HealthChecker supports up to " + API_VERSION);
            return;
        }
        LoadedPlugin existing = plugins.get(type);
        if (existing != null) {
            errors.add("duplicate type \"" + type + "\": " + existing.source() + " ("
                    + existing.plugin().getClass().getName() + ") and " + source + " (" + className + ")");
            return;
        }
        plugins.put(type, new LoadedPlugin(plugin, type, source));
    }

    /** Loaded plugins in load order (built-ins first, then jars by file name). */
    public List<LoadedPlugin> plugins() {
        return List.copyOf(plugins.values());
    }

    /** Non-fatal problems, e.g. a jar without any check plugin. */
    public List<String> warnings() {
        return warnings;
    }

    /** All known types, sorted. */
    public List<String> availableTypes() {
        return List.copyOf(new TreeSet<>(plugins.keySet()));
    }

    public Optional<CheckPlugin> find(String type) {
        LoadedPlugin p = plugins.get(type);
        return p == null ? Optional.empty() : Optional.of(p.plugin());
    }

    /**
     * The plugin for {@code type}.
     *
     * @throws ConfigException (setting key {@code "type"}) listing the available types
     */
    public CheckPlugin require(String type) throws ConfigException {
        Optional<CheckPlugin> p = find(type);
        if (p.isPresent()) return p.get();
        List<String> available = availableTypes();
        throw new ConfigException("type", "unknown type \"" + type + "\" (available: "
                + (available.isEmpty() ? "none" : String.join(", ", available)) + ")");
    }

    /**
     * Validates every endpoint against its plugin: the type must be known, the plugin's
     * {@code create()} must accept the settings, and every setting must have been read.
     * Checkers built here are closed again right away. Returns one error per bad endpoint,
     * prefixed with its position in {@code endpoints}, e.g.
     * {@code endpoints[2] "survival": unknown type "minecraft" (available: http)}.
     */
    public List<String> validateEndpoints(List<EndpointDefinition> endpoints) {
        List<String> errors = new ArrayList<>();
        for (int i = 0; i < endpoints.size(); i++) {
            EndpointDefinition e = endpoints.get(i);
            String prefix = "endpoints[" + i + "] \"" + e.id() + "\": ";
            try {
                CheckPlugin plugin = require(e.type());
                JsonEndpointConfig config = new JsonEndpointConfig(e.id(), e.name(), e.settings());
                Checker checker = plugin.create(config);
                try {
                    config.checkNoUnreadKeys();
                } finally {
                    if (checker != null) {
                        try {
                            checker.close();
                        } catch (Exception ignored) {
                            // a validation-only checker; nothing to report
                        }
                    }
                }
                if (checker == null) {
                    errors.add(prefix + "plugin \"" + e.type() + "\" returned no checker");
                }
            } catch (ConfigException ex) {
                errors.add(prefix + ex.getMessage());
            } catch (RuntimeException | LinkageError ex) {
                errors.add(prefix + "plugin \"" + e.type() + "\" failed: " + ex);
            }
        }
        return errors;
    }

    /** Closes the plugin jars' class loaders. Only call once no plugin code runs any more. */
    @Override
    public void close() {
        closeAll(loaders);
    }

    private static void closeAll(List<URLClassLoader> loaders) {
        for (URLClassLoader l : loaders) {
            try {
                l.close();
            } catch (IOException ignored) {
                // nothing useful to do
            }
        }
    }
}
