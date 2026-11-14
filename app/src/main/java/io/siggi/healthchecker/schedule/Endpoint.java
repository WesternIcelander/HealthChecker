package io.siggi.healthchecker.schedule;

import io.siggi.healthchecker.api.Checker;
import io.siggi.healthchecker.config.EndpointDefinition;
import io.siggi.healthchecker.config.Visibility;
import io.siggi.healthchecker.plugin.JsonEndpointConfig.Target;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * One live endpoint: its definition, the targets its plugin declared, and its checker.
 * Immutable; an in-place update produces a new {@code Endpoint} sharing the same checker.
 */
public record Endpoint(EndpointDefinition definition, List<Target> targets, Checker checker) {
    public Endpoint {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(checker, "checker");
        targets = List.copyOf(targets);
    }

    public String id() {
        return definition.id();
    }

    public String name() {
        return definition.name();
    }

    public String type() {
        return definition.type();
    }

    public Visibility visibility() {
        return definition.visibility();
    }

    public int intervalSeconds() {
        return definition.intervalSeconds();
    }

    public Duration timeout() {
        return Duration.ofMillis(definition.timeoutMillis());
    }

    public int failureThreshold() {
        return definition.failureThreshold();
    }

    /** The same checker and targets with a new definition (for in-place updates). */
    Endpoint withDefinition(EndpointDefinition newDefinition) {
        return new Endpoint(newDefinition, targets, checker);
    }

    /**
     * Whether changing from {@code a} to {@code b} needs a new checker: the type, its
     * settings, the interval, the timeout or the failure threshold changed. Everything
     * else (name, category, visibility, notify, reminders) is applied in place.
     */
    static boolean needsNewChecker(EndpointDefinition a, EndpointDefinition b) {
        return !a.type().equals(b.type())
                || !a.settings().equals(b.settings())
                || a.intervalSeconds() != b.intervalSeconds()
                || a.timeoutMillis() != b.timeoutMillis()
                || a.failureThreshold() != b.failureThreshold();
    }
}
