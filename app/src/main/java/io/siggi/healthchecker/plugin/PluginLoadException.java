package io.siggi.healthchecker.plugin;

import java.util.List;

/** Thrown when plugins can't be loaded. Carries every problem found, not just the first. */
public class PluginLoadException extends Exception {
    private final List<String> errors;

    public PluginLoadException(List<String> errors) {
        super(format(errors));
        this.errors = List.copyOf(errors);
    }

    public List<String> errors() {
        return errors;
    }

    private static String format(List<String> errors) {
        StringBuilder sb = new StringBuilder();
        sb.append(errors.size()).append(errors.size() == 1 ? " plugin error:" : " plugin errors:");
        for (String error : errors) {
            sb.append("\n  ").append(error);
        }
        return sb.toString();
    }
}
