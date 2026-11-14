package io.siggi.healthchecker;

import io.siggi.healthchecker.api.ConfigException;
import io.siggi.healthchecker.auth.PasswordHasher;
import io.siggi.healthchecker.auth.SessionManager;
import io.siggi.healthchecker.config.Config;
import io.siggi.healthchecker.config.ConfigLoadException;
import io.siggi.healthchecker.config.ConfigLoader;
import io.siggi.healthchecker.history.HistoryStore;
import io.siggi.healthchecker.plugin.PluginLoadException;
import io.siggi.healthchecker.plugin.PluginManager;

import java.io.Console;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * Command line entry point.
 *
 * <pre>
 * java -jar HealthChecker.jar [run] [config.json]
 * java -jar HealthChecker.jar hash-password
 * </pre>
 */
public final class Main {
    static final String DEFAULT_CONFIG = "config.json";
    static final int EXIT_OK = 0;
    static final int EXIT_ERROR = 1;
    static final int EXIT_USAGE = 2;

    private Main() {
    }

    public static void main(String[] args) {
        int code = execute(args, System.out, System.err);
        if (code != EXIT_OK) {
            System.exit(code);
        }
    }

    /** What {@code run} does once the daemon is up; returning stops it. */
    interface Runner {
        void running(Daemon daemon) throws InterruptedException;
    }

    /** Runs until the JVM shuts down (Ctrl-C, SIGTERM), then stops the daemon. */
    static final Runner UNTIL_SHUTDOWN = daemon -> {
        Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().name("HealthChecker-shutdown").unstarted(daemon::close));
        daemon.awaitClose();
    };

    static int execute(String[] args, PrintStream out, PrintStream err) {
        return execute(args, out, err, UNTIL_SHUTDOWN);
    }

    static int execute(String[] args, PrintStream out, PrintStream err, Runner runner) {
        if (args.length == 0) {
            return run(Path.of(DEFAULT_CONFIG), out, err, runner);
        }
        switch (args[0]) {
            case "run":
                if (args.length > 2) {
                    return usage(err);
                }
                return run(Path.of(args.length == 2 ? args[1] : DEFAULT_CONFIG), out, err, runner);
            case "hash-password":
                if (args.length != 1) {
                    return usage(err);
                }
                return hashPassword(System.console(), out, err);
            case "help":
            case "-h":
            case "--help":
                usage(out);
                return EXIT_OK;
            default:
                // `java -jar HealthChecker.jar config.json` is shorthand for `run config.json`.
                if (args.length == 1 && !args[0].startsWith("-")) {
                    return run(Path.of(args[0]), out, err, runner);
                }
                return usage(err);
        }
    }

    static int run(Path configFile, PrintStream out, PrintStream err, Runner runner) {
        Config config;
        try {
            config = new ConfigLoader().load(configFile);
        } catch (ConfigLoadException e) {
            printWarnings(e.warnings(), err);
            err.println(configFile + ": " + e.getMessage());
            return EXIT_ERROR;
        }
        printWarnings(config.warnings(), err);
        PluginManager plugins;
        try {
            plugins = PluginManager.load(config.pluginsDir());
        } catch (PluginLoadException e) {
            err.println(config.pluginsDir() + ": " + e.getMessage());
            return EXIT_ERROR;
        }
        try (plugins) {
            printWarnings(plugins.warnings(), err);
            for (PluginManager.LoadedPlugin p : plugins.plugins()) {
                out.println("Loaded plugin " + p.describe());
            }
            List<String> endpointErrors = new java.util.ArrayList<>(plugins.validateEndpoints(config.endpoints()));
            try {
                SessionManager.sessionDays(config.auth());
            } catch (IllegalArgumentException e) {
                endpointErrors.add(e.getMessage());
            }
            if (!endpointErrors.isEmpty()) {
                err.println(configFile + ": " + new ConfigLoadException(endpointErrors, List.of()).getMessage());
                return EXIT_ERROR;
            }
            HistoryStore history;
            try {
                history = HistoryStore.open(config.dataDir());
            } catch (IOException e) {
                err.println("Couldn't load history: " + e.getMessage());
                return EXIT_ERROR;
            }
            out.println("Loaded history of " + history.endpointIds().size() + " endpoint(s) from " + config.dataDir());
            out.println("Loaded " + configFile + ": " + config.endpoints().size() + " endpoint(s), "
                    + config.categories().size() + " categor" + (config.categories().size() == 1 ? "y" : "ies") + ".");
            Daemon daemon;
            try {
                daemon = Daemon.start(config, plugins, history);
            } catch (IOException | ConfigException e) {
                err.println("Couldn't start: " + e.getMessage());
                return EXIT_ERROR;
            }
            try (daemon) {
                out.println("Listening on " + config.server().bind() + ":" + daemon.port());
                runner.running(daemon);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return EXIT_OK;
        }
    }

    static int hashPassword(Console console, PrintStream out, PrintStream err) {
        if (console == null) {
            err.println("hash-password needs an interactive console to read the password without echo.");
            return EXIT_ERROR;
        }
        char[] password = console.readPassword("Password: ");
        if (password == null) {
            return EXIT_ERROR;
        }
        char[] repeat = console.readPassword("Repeat password: ");
        try {
            if (repeat == null || !Arrays.equals(password, repeat)) {
                err.println("Passwords don't match.");
                return EXIT_ERROR;
            }
            if (password.length == 0) {
                err.println("The password is empty.");
                return EXIT_ERROR;
            }
            out.println(new PasswordHasher().hash(password));
            return EXIT_OK;
        } catch (UnsupportedOperationException e) {
            err.println("hash-password: " + e.getMessage());
            return EXIT_ERROR;
        } finally {
            Arrays.fill(password, '\0');
            if (repeat != null) {
                Arrays.fill(repeat, '\0');
            }
        }
    }

    private static void printWarnings(List<String> warnings, PrintStream err) {
        for (String warning : warnings) {
            err.println("warning: " + warning);
        }
    }

    private static int usage(PrintStream stream) {
        stream.println("Usage:");
        stream.println("  java -jar HealthChecker.jar [run] [config.json]   start the daemon (default config: "
                + DEFAULT_CONFIG + ")");
        stream.println("  java -jar HealthChecker.jar hash-password         read a password and print its hash");
        return EXIT_USAGE;
    }
}
