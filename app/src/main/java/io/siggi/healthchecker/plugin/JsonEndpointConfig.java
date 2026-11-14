package io.siggi.healthchecker.plugin;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import io.siggi.healthchecker.api.ConfigException;
import io.siggi.healthchecker.api.EndpointConfig;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@link EndpointConfig} over an endpoint's type-specific settings as a {@link JsonObject}.
 *
 * <p>Every getter call marks its key as read, whether or not the key is present.
 * {@link #keys()} doesn't mark anything. After the plugin's {@code create()} returns,
 * {@link #unreadKeys()} lists the settings nobody asked for (usually typos), and
 * {@link #checkNoUnreadKeys()} turns them into a {@link ConfigException}.
 *
 * <p>JSON {@code null} counts as absent. Types are strict: a string is never converted to
 * a number or boolean, and integers must be whole numbers within {@code int} range.
 */
public final class JsonEndpointConfig implements EndpointConfig {
    /** A value declared by the plugin as identifying the target. */
    public record Target(String label, String value) {
        public Target {
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(value, "value");
        }
    }

    private final String id;
    private final String name;
    private final JsonObject settings;
    private final Set<String> readKeys = new LinkedHashSet<>();
    private final List<Target> targets = new ArrayList<>();

    public JsonEndpointConfig(String id, String name, JsonObject settings) {
        this.id = Objects.requireNonNull(id, "id");
        this.name = Objects.requireNonNull(name, "name");
        this.settings = settings == null ? new JsonObject() : settings.deepCopy();
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String getString(String key) throws ConfigException {
        return optString(key).orElseThrow(() -> missing(key));
    }

    @Override
    public Optional<String> optString(String key) throws ConfigException {
        JsonElement e = read(key);
        if (e == null) return Optional.empty();
        if (e instanceof JsonPrimitive p && p.isString()) return Optional.of(p.getAsString());
        throw wrongType(key, "a string");
    }

    @Override
    public int getInt(String key) throws ConfigException {
        OptionalInt v = optInt(key);
        if (v.isEmpty()) throw missing(key);
        return v.getAsInt();
    }

    @Override
    public OptionalInt optInt(String key) throws ConfigException {
        JsonElement e = read(key);
        if (e == null) return OptionalInt.empty();
        if (e instanceof JsonPrimitive p && p.isNumber()) {
            BigDecimal d;
            try {
                d = p.getAsBigDecimal();
            } catch (NumberFormatException ex) {
                throw wrongType(key, "an integer");
            }
            try {
                return OptionalInt.of(d.intValueExact());
            } catch (ArithmeticException ex) {
                if (d.stripTrailingZeros().scale() <= 0) {
                    throw new ConfigException(key, "setting \"" + key + "\" is out of range");
                }
                throw wrongType(key, "an integer");
            }
        }
        throw wrongType(key, "an integer");
    }

    @Override
    public boolean optBoolean(String key, boolean def) throws ConfigException {
        JsonElement e = read(key);
        if (e == null) return def;
        if (e instanceof JsonPrimitive p && p.isBoolean()) return p.getAsBoolean();
        throw wrongType(key, "a boolean");
    }

    @Override
    public List<String> optStringList(String key) throws ConfigException {
        JsonElement e = read(key);
        if (e == null) return List.of();
        if (!(e instanceof JsonArray a)) throw wrongType(key, "a list of strings");
        List<String> out = new ArrayList<>(a.size());
        for (JsonElement item : a) {
            if (!(item instanceof JsonPrimitive p && p.isString())) {
                throw wrongType(key, "a list of strings");
            }
            out.add(p.getAsString());
        }
        return List.copyOf(out);
    }

    @Override
    public Set<String> keys() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(settings.keySet()));
    }

    @Override
    public synchronized void declareTarget(String label, String value) {
        Target t = new Target(label, value);
        if (!targets.contains(t)) targets.add(t);
    }

    /** Targets declared so far, in declaration order, without duplicates. */
    public synchronized List<Target> targets() {
        return List.copyOf(targets);
    }

    /** Keys present in the settings that no getter has asked for, in config order. */
    public synchronized Set<String> unreadKeys() {
        Set<String> unread = new LinkedHashSet<>(settings.keySet());
        unread.removeAll(readKeys);
        return Collections.unmodifiableSet(unread);
    }

    /**
     * Throws if any key was never read. The exception names every unread key and carries
     * the first one as its setting key.
     */
    public void checkNoUnreadKeys() throws ConfigException {
        Set<String> unread = unreadKeys();
        if (unread.isEmpty()) return;
        String list = unread.stream().map(k -> "\"" + k + "\"").collect(Collectors.joining(", "));
        throw new ConfigException(unread.iterator().next(),
                (unread.size() == 1 ? "unknown setting " : "unknown settings ") + list);
    }

    private synchronized JsonElement read(String key) {
        Objects.requireNonNull(key, "key");
        readKeys.add(key);
        JsonElement e = settings.get(key);
        return e == null || e.isJsonNull() ? null : e;
    }

    private static ConfigException missing(String key) {
        return new ConfigException(key, "missing required setting \"" + key + "\"");
    }

    private static ConfigException wrongType(String key, String expected) {
        return new ConfigException(key, "setting \"" + key + "\" must be " + expected);
    }
}
