package io.siggi.healthchecker.api;

import java.util.List;
import java.util.Objects;

/**
 * Describes one type-specific setting, for form rendering. Validation still happens in
 * {@link CheckPlugin#create(EndpointConfig)}.
 *
 * <p>Build with the factories and the copy-returning modifiers, e.g.
 * {@code SettingSpec.string("host", "Host").asRequired().help("Hostname or IP")}.
 *
 * @param defaultValue {@code null}, or a value matching {@code kind}: {@link String}
 *                     (STRING, and CHOICE where it must be one of {@code choices}),
 *                     {@link Integer}, {@link Boolean} or {@code List<String>}
 * @param help         {@code null} or a short help text
 * @param choices      the allowed values for CHOICE; empty for every other kind
 */
public record SettingSpec(String key, String label, Kind kind, boolean required,
                          Object defaultValue, String help, List<String> choices) {

    public enum Kind { STRING, INTEGER, BOOLEAN, STRING_LIST, CHOICE }

    public SettingSpec {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(kind, "kind");
        if (key.isBlank()) {
            throw new IllegalArgumentException("key must not be blank");
        }
        choices = choices == null ? List.of() : List.copyOf(choices);
        if (kind == Kind.CHOICE && choices.isEmpty()) {
            throw new IllegalArgumentException("setting '" + key + "': CHOICE needs at least one choice");
        }
        if (kind != Kind.CHOICE && !choices.isEmpty()) {
            throw new IllegalArgumentException("setting '" + key + "': only CHOICE settings have choices");
        }
        if (defaultValue != null) {
            defaultValue = checkDefault(key, kind, defaultValue, choices);
        }
    }

    public static SettingSpec string(String key, String label) {
        return new SettingSpec(key, label, Kind.STRING, false, null, null, List.of());
    }

    public static SettingSpec integer(String key, String label) {
        return new SettingSpec(key, label, Kind.INTEGER, false, null, null, List.of());
    }

    public static SettingSpec bool(String key, String label) {
        return new SettingSpec(key, label, Kind.BOOLEAN, false, null, null, List.of());
    }

    public static SettingSpec stringList(String key, String label) {
        return new SettingSpec(key, label, Kind.STRING_LIST, false, null, null, List.of());
    }

    public static SettingSpec choice(String key, String label, List<String> choices) {
        return new SettingSpec(key, label, Kind.CHOICE, false, null, null, choices);
    }

    public static SettingSpec choice(String key, String label, String... choices) {
        return choice(key, label, List.of(choices));
    }

    /**
     * A copy marked as required. (Not named {@code required()}: that's the record
     * component's accessor.)
     */
    public SettingSpec asRequired() {
        return new SettingSpec(key, label, kind, true, defaultValue, help, choices);
    }

    /** A copy with the given default value (shown pre-filled in the form). */
    public SettingSpec defaultValue(Object value) {
        return new SettingSpec(key, label, kind, required, value, help, choices);
    }

    /** A copy with the given help text. */
    public SettingSpec help(String text) {
        return new SettingSpec(key, label, kind, required, defaultValue, text, choices);
    }

    private static Object checkDefault(String key, Kind kind, Object value, List<String> choices) {
        boolean ok = switch (kind) {
            case STRING -> value instanceof String;
            case INTEGER -> value instanceof Integer;
            case BOOLEAN -> value instanceof Boolean;
            case CHOICE -> value instanceof String s && choices.contains(s);
            case STRING_LIST -> value instanceof List<?> l && l.stream().allMatch(String.class::isInstance);
        };
        if (!ok) {
            throw new IllegalArgumentException("setting '" + key + "': default " + value
                    + " doesn't fit kind " + kind + (kind == Kind.CHOICE ? " " + choices : ""));
        }
        return kind == Kind.STRING_LIST ? List.copyOf((List<?>) value) : value;
    }
}
