package io.siggi.healthchecker;

import io.siggi.healthchecker.auth.PasswordHasher;
import io.siggi.healthchecker.config.Config;
import io.siggi.healthchecker.config.ConfigLoadException;
import io.siggi.healthchecker.config.ConfigLoader;

import java.io.Console;
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

    static int execute(String[] args, PrintStream out, PrintStream err) {
        if (args.length == 0) {
            return run(Path.of(DEFAULT_CONFIG), out, err);
        }
        switch (args[0]) {
            case "run":
                if (args.length > 2) {
                    return usage(err);
                }
                return run(Path.of(args.length == 2 ? args[1] : DEFAULT_CONFIG), out, err);
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
                    return run(Path.of(args[0]), out, err);
                }
                return usage(err);
        }
    }

    static int run(Path configFile, PrintStream out, PrintStream err) {
        Config config;
        try {
            config = new ConfigLoader().load(configFile);
        } catch (ConfigLoadException e) {
            printWarnings(e.warnings(), err);
            err.println(configFile + ": " + e.getMessage());
            return EXIT_ERROR;
        }
        printWarnings(config.warnings(), err);
        out.println("Loaded " + configFile + ": " + config.endpoints().size() + " endpoint(s), "
                + config.categories().size() + " categor" + (config.categories().size() == 1 ? "y" : "ies") + ".");
        // Plugins, scheduling and the web server are started here in later milestones.
        return EXIT_OK;
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
