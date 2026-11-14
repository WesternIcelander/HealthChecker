package io.siggi.healthchecker.config;

import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Collectors;

/** How much of an endpoint anonymous visitors can see. */
public enum Visibility {
    PUBLIC("public"),
    PUBLIC_STATUS("public-status"),
    PRIVATE("private");

    private final String configName;

    Visibility(String configName) {
        this.configName = configName;
    }

    public String configName() {
        return configName;
    }

    public static Optional<Visibility> fromConfigName(String name) {
        for (Visibility v : values()) {
            if (v.configName.equals(name)) return Optional.of(v);
        }
        return Optional.empty();
    }

    static String allNames() {
        return Arrays.stream(values()).map(v -> "\"" + v.configName + "\"").collect(Collectors.joining(", "));
    }
}
