package io.siggi.healthchecker.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The outcome of one check.
 *
 * <p>{@code message} and {@code details} may be shown publicly, so they must never
 * contain the endpoint's target (URL, host, port, …). Detail values are JSON-friendly:
 * strings, numbers or booleans. Details keep their insertion order.
 *
 * @param success whether the check passed
 * @param message short human-readable description; {@code null} becomes {@code ""}
 * @param details extra values, e.g. {@code statusCode}; immutable
 */
public record CheckResult(boolean success, String message, Map<String, Object> details) {
    public CheckResult {
        message = message == null ? "" : message;
        Map<String, Object> copy = new LinkedHashMap<>();
        if (details != null) {
            for (Map.Entry<String, Object> e : details.entrySet()) {
                copy.put(checkKey(e.getKey()), checkValue(e.getKey(), e.getValue()));
            }
        }
        details = Collections.unmodifiableMap(copy);
    }

    /** A passing result with no details. */
    public static CheckResult ok(String message) {
        return new CheckResult(true, message, Map.of());
    }

    /** A failing result with no details. */
    public static CheckResult fail(String message) {
        return new CheckResult(false, message, Map.of());
    }

    /**
     * Returns a copy with one detail added (or replaced, keeping its position).
     *
     * @param value a {@link String}, {@link Number} or {@link Boolean}
     */
    public CheckResult with(String key, Object value) {
        Map<String, Object> copy = new LinkedHashMap<>(details);
        copy.put(checkKey(key), checkValue(key, value));
        return new CheckResult(success, message, copy);
    }

    private static String checkKey(String key) {
        Objects.requireNonNull(key, "detail key");
        if (key.isBlank()) {
            throw new IllegalArgumentException("detail key must not be blank");
        }
        return key;
    }

    private static Object checkValue(String key, Object value) {
        if (value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        throw new IllegalArgumentException("detail '" + key + "' must be a String, Number or Boolean, got "
                + (value == null ? "null" : value.getClass().getName()));
    }
}
