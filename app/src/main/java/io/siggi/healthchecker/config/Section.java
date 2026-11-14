package io.siggi.healthchecker.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Typed, path-aware reads from one JSON object. Problems go to {@link Problems};
 * on an invalid value the getters return the default (or {@code null}) so loading
 * continues and every error is reported.
 */
final class Section {
    final JsonObject obj;
    final String path;
    final Problems problems;
    private final Set<String> read = new HashSet<>();

    Section(JsonObject obj, String path, Problems problems) {
        this.obj = obj;
        this.path = path;
        this.problems = problems;
    }

    String path(String key) {
        return path.isEmpty() ? key : path + "." + key;
    }

    /** The value for {@code key}, or {@code null} if absent or JSON null. Marks the key as read. */
    JsonElement raw(String key) {
        read.add(key);
        JsonElement e = obj.get(key);
        return e == null || e.isJsonNull() ? null : e;
    }

    boolean has(String key) {
        JsonElement e = obj.get(key);
        return e != null && !e.isJsonNull();
    }

    void error(String key, String message) {
        problems.error(path(key), message);
    }

    String string(String key, String def) {
        JsonElement e = raw(key);
        if (e == null) return def;
        if (!isString(e)) {
            error(key, "must be a string");
            return def;
        }
        return e.getAsString();
    }

    String requiredString(String key) {
        JsonElement e = raw(key);
        if (e == null) {
            error(key, "is required");
            return null;
        }
        if (!isString(e)) {
            error(key, "must be a string");
            return null;
        }
        String s = e.getAsString();
        if (s.isBlank()) {
            error(key, "must not be empty");
            return null;
        }
        return s;
    }

    int integer(String key, int def, int min, int max) {
        JsonElement e = raw(key);
        if (e == null) return def;
        Integer v = asInteger(e);
        if (v == null) {
            error(key, "must be an integer");
            return def;
        }
        if (v < min || v > max) {
            error(key, "must be between " + min + " and " + max + " (got " + v + ")");
            return def;
        }
        return v;
    }

    double number(String key, double def, double min, double max) {
        JsonElement e = raw(key);
        if (e == null) return def;
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            error(key, "must be a number");
            return def;
        }
        double v = e.getAsDouble();
        if (!(v >= min && v <= max)) {
            error(key, "must be between " + fmt(min) + " and " + fmt(max) + " (got " + fmt(v) + ")");
            return def;
        }
        return v;
    }

    boolean bool(String key, boolean def) {
        JsonElement e = raw(key);
        if (e == null) return def;
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isBoolean()) {
            error(key, "must be true or false");
            return def;
        }
        return e.getAsBoolean();
    }

    /** A list of non-empty strings, or {@code null} if absent or invalid. */
    List<String> stringList(String key) {
        JsonElement e = raw(key);
        if (e == null) return null;
        if (!e.isJsonArray()) {
            error(key, "must be an array of strings");
            return null;
        }
        List<String> out = new ArrayList<>();
        JsonArray arr = e.getAsJsonArray();
        boolean ok = true;
        for (int i = 0; i < arr.size(); i++) {
            JsonElement item = arr.get(i);
            if (!isString(item) || item.getAsString().isBlank()) {
                problems.error(path(key) + "[" + i + "]", "must be a non-empty string");
                ok = false;
            } else {
                out.add(item.getAsString());
            }
        }
        return ok ? out : null;
    }

    /** A nested object, or {@code null} if absent or invalid. */
    Section object(String key) {
        JsonElement e = raw(key);
        if (e == null) return null;
        if (!e.isJsonObject()) {
            error(key, "must be an object");
            return null;
        }
        return new Section(e.getAsJsonObject(), path(key), problems);
    }

    /** An array, or {@code null} if absent or invalid. */
    JsonArray array(String key) {
        JsonElement e = raw(key);
        if (e == null) return null;
        if (!e.isJsonArray()) {
            error(key, "must be an array");
            return null;
        }
        return e.getAsJsonArray();
    }

    /** Reports every key that no getter asked for. */
    void rejectUnknownKeys() {
        for (String key : obj.keySet()) {
            if (!read.contains(key)) {
                error(key, "unknown setting");
            }
        }
    }

    static boolean isString(JsonElement e) {
        return e.isJsonPrimitive() && e.getAsJsonPrimitive().isString();
    }

    /** The integer value of {@code e}, or {@code null} if it isn't an integral number in int range. */
    static Integer asInteger(JsonElement e) {
        if (!e.isJsonPrimitive()) return null;
        JsonPrimitive p = e.getAsJsonPrimitive();
        if (!p.isNumber()) return null;
        try {
            BigDecimal d = p.getAsBigDecimal();
            return d.intValueExact();
        } catch (ArithmeticException | NumberFormatException ex) {
            return null;
        }
    }

    private static String fmt(double d) {
        return d == Math.rint(d) && !Double.isInfinite(d) ? Long.toString((long) d) : Double.toString(d);
    }
}
