package io.siggi.healthchecker.config;

import java.util.List;

/** Thrown when a config has one or more errors. Carries all of them, not just the first. */
public class ConfigLoadException extends Exception {
    private final List<String> errors;
    private final List<String> warnings;

    public ConfigLoadException(List<String> errors, List<String> warnings) {
        super(format(errors));
        this.errors = List.copyOf(errors);
        this.warnings = List.copyOf(warnings);
    }

    public List<String> errors() {
        return errors;
    }

    public List<String> warnings() {
        return warnings;
    }

    private static String format(List<String> errors) {
        StringBuilder sb = new StringBuilder();
        sb.append(errors.size()).append(errors.size() == 1 ? " configuration error:" : " configuration errors:");
        for (String error : errors) {
            sb.append("\n  ").append(error);
        }
        return sb.toString();
    }
}
