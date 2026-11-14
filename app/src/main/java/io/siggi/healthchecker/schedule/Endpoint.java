package io.siggi.healthchecker.schedule;

import io.siggi.healthchecker.api.Checker;
import io.siggi.healthchecker.config.EndpointDefinition;
import io.siggi.healthchecker.config.Visibility;
import io.siggi.healthchecker.plugin.JsonEndpointConfig.Target;

import java.time.Duration;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * One live endpoint: its definition, the targets its plugin declared, and its checker.
 * Immutable; an in-place update produces a new {@code Endpoint} sharing the same checker.
 *
 * @param pastTargets target values the endpoint had before (e.g. before its host was edited);
 *                    never shown, but leak-filtered like the current targets, since older
 *                    history can contain them
 */
public record Endpoint(EndpointDefinition definition, List<Target> targets, Checker checker,
                       Set<String> pastTargets) {
    public Endpoint {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(checker, "checker");
        targets = List.copyOf(targets);
        pastTargets = Set.copyOf(pastTargets);
    }

    public Endpoint(EndpointDefinition definition, List<Target> targets, Checker checker) {
        this(definition, targets, checker, Set.of());
    }

    /** The same endpoint, also leak-filtering {@code values} (added to {@link #pastTargets()}). */
    public Endpoint withPastTargets(Collection<String> values) {
        if (pastTargets.containsAll(values)) {
            return this;
        }
        Set<String> all = new HashSet<>(pastTargets);
        all.addAll(values);
        return new Endpoint(definition, targets, checker, all);
    }

    /** Every value the leak filter hides: the current targets' values and the past ones. */
    public Set<String> leakValues() {
        Set<String> all = new HashSet<>(pastTargets);
        for (Target t : targets) {
            all.add(t.value());
        }
        return all;
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
        return new Endpoint(newDefinition, targets, checker, pastTargets);
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
