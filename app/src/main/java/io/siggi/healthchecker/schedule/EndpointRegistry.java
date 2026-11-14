package io.siggi.healthchecker.schedule;

import io.siggi.healthchecker.api.ConfigException;
import io.siggi.healthchecker.config.EndpointDefinition;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The live set of endpoints. Endpoints can be added, updated and removed at runtime;
 * listeners (the {@link CheckScheduler}) follow every change.
 *
 * <p>Ownership of checkers: a checker that leaves the registry (through
 * {@link Listener#replaced} or {@link Listener#removed}) is closed by the listener that
 * runs it, the {@link CheckScheduler}, once its in-flight check is over. Thread-safe;
 * listeners are called in order, while the registry is locked, and must be quick.
 */
public final class EndpointRegistry {
    /** What an {@link #update} did. */
    public enum UpdateKind {
        /** The definition was identical; nothing happened. */
        UNCHANGED,
        /** Only presentation or routing changed: the checker, state and open events are kept. */
        IN_PLACE,
        /** The check changed: a new checker replaces the old one, and the state starts over. */
        REPLACED
    }

    /** Follows registry changes. Called with the registry locked. */
    public interface Listener {
        void added(Endpoint endpoint);

        /** Same checker, new definition. */
        void updated(Endpoint old, Endpoint now);

        /** New checker; {@code old}'s checker must be closed by the listener that runs it. */
        void replaced(Endpoint old, Endpoint now);

        /** {@code old}'s checker must be closed by the listener that runs it. */
        void removed(Endpoint old);
    }

    private final EndpointFactory factory;
    private final Map<String, Endpoint> endpoints = new LinkedHashMap<>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    public EndpointRegistry(EndpointFactory factory) {
        this.factory = Objects.requireNonNull(factory, "factory");
    }

    /**
     * Adds a listener and calls its {@link Listener#added} for every endpoint already
     * present, atomically, so it misses nothing.
     */
    public synchronized void subscribe(Listener listener) {
        Objects.requireNonNull(listener, "listener");
        for (Endpoint e : endpoints.values()) {
            listener.added(e);
        }
        listeners.add(listener);
    }

    public synchronized void unsubscribe(Listener listener) {
        listeners.remove(listener);
    }

    /**
     * Builds and adds a new endpoint.
     *
     * @throws ConfigException if the id is taken (setting key {@code "id"}) or the
     *                         endpoint is invalid
     */
    public synchronized Endpoint add(EndpointDefinition definition) throws ConfigException {
        if (endpoints.containsKey(definition.id())) {
            throw new ConfigException("id", "an endpoint with id \"" + definition.id() + "\" already exists");
        }
        Endpoint endpoint = factory.create(definition);
        endpoints.put(endpoint.id(), endpoint);
        for (Listener l : listeners) {
            l.added(endpoint);
        }
        return endpoint;
    }

    /**
     * Updates an existing endpoint (matched by id). A new checker is built first, only if
     * the check itself changed; if that fails, nothing changes.
     *
     * @throws IllegalArgumentException if no endpoint has this id
     * @throws ConfigException          if the new definition is invalid
     */
    public synchronized UpdateKind update(EndpointDefinition definition) throws ConfigException {
        Endpoint old = endpoints.get(definition.id());
        if (old == null) {
            throw new IllegalArgumentException("unknown endpoint: " + definition.id());
        }
        if (old.definition().equals(definition)) {
            return UpdateKind.UNCHANGED;
        }
        if (!Endpoint.needsNewChecker(old.definition(), definition)) {
            Endpoint now = old.withDefinition(definition);
            endpoints.put(now.id(), now);
            for (Listener l : listeners) {
                l.updated(old, now);
            }
            return UpdateKind.IN_PLACE;
        }
        Endpoint now = factory.create(definition);
        endpoints.put(now.id(), now);
        for (Listener l : listeners) {
            l.replaced(old, now);
        }
        return UpdateKind.REPLACED;
    }

    /** Removes an endpoint. Returns whether it existed. */
    public synchronized boolean remove(String id) {
        Endpoint old = endpoints.remove(id);
        if (old == null) {
            return false;
        }
        for (Listener l : listeners) {
            l.removed(old);
        }
        return true;
    }

    public synchronized Optional<Endpoint> get(String id) {
        return Optional.ofNullable(endpoints.get(id));
    }

    /** Every endpoint, in the order they were added. */
    public synchronized List<Endpoint> all() {
        return new ArrayList<>(endpoints.values());
    }
}
