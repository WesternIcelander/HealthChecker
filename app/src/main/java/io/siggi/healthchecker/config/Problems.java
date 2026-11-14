package io.siggi.healthchecker.config;

import java.util.ArrayList;
import java.util.List;

/** Collects errors and warnings, each prefixed with its config path. */
final class Problems {
    final List<String> errors = new ArrayList<>();
    final List<String> warnings = new ArrayList<>();

    void error(String path, String message) {
        errors.add(path.isEmpty() ? message : path + ": " + message);
    }

    void warn(String path, String message) {
        warnings.add(path.isEmpty() ? message : path + ": " + message);
    }
}
