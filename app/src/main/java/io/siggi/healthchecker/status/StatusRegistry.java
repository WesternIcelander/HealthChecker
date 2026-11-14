package io.siggi.healthchecker.status;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Live state of every endpoint: the {@code failureThreshold} state machine, failure
 * streaks and the most recent checks.
 *
 * <ul>
 *   <li>The first result decides the state: UNKNOWN → UP on a success, UNKNOWN → DOWN on a
 *       failure (regardless of the threshold).</li>
 *   <li>UP → DOWN after {@code failureThreshold} consecutive failures.</li>
 *   <li>DOWN → UP on the first success.</li>
 *   <li>The streak start is the time of the first failed check of the streak, so with a
 *       threshold of 3 an outage starts at the first of the 3 failures.</li>
 * </ul>
 *
 * <p>Endpoints in a scheduled maintenance window ({@link #setMaintenance}) are shown as
 * {@link State#MAINTENANCE}; the state machine above keeps running underneath
 * ({@link EndpointStatus#checkState()}) and its transitions are still reported.
 *
 * <p>Every transition is logged and delivered to the listeners as a {@link StateChange},
 * in order per endpoint, on the thread that recorded the check. Thread-safe.
 */
public final class StatusRegistry {
    /** Number of recent checks kept per endpoint. */
    public static final int RECENT_CHECKS = 100;

    private final Logger logger;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final List<Consumer<StateChange>> listeners = new CopyOnWriteArrayList<>();
    private final Map<String, Maintenance> maintenance = new ConcurrentHashMap<>();

    private record Maintenance(String windowId, Instant since) {
    }

    public StatusRegistry() {
        this(System.getLogger(StatusRegistry.class.getName()));
    }

    public StatusRegistry(Logger logger) {
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /** Adds a listener for state changes. Exceptions it throws are logged and ignored. */
    public void addListener(Consumer<StateChange> listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    public void removeListener(Consumer<StateChange> listener) {
        listeners.remove(listener);
    }

    /**
     * Starts tracking an endpoint in state UNKNOWN. If it's already tracked, only its
     * threshold is updated and its state, streak and recent checks are kept.
     */
    public void register(String endpointId, int failureThreshold) {
        Objects.requireNonNull(endpointId, "endpointId");
        checkThreshold(failureThreshold);
        Entry entry = entries.computeIfAbsent(endpointId, Entry::new);
        synchronized (entry) {
            entry.failureThreshold = failureThreshold;
        }
    }

    /**
     * Changes an endpoint's threshold. The state doesn't change until the next check,
     * which is then evaluated against the new threshold.
     *
     * @throws IllegalArgumentException if the endpoint isn't registered
     */
    public void setFailureThreshold(String endpointId, int failureThreshold) {
        checkThreshold(failureThreshold);
        Entry entry = entries.get(endpointId);
        if (entry == null) {
            throw new IllegalArgumentException("unknown endpoint: " + endpointId);
        }
        synchronized (entry) {
            entry.failureThreshold = failureThreshold;
        }
    }

    /** Stops tracking an endpoint. Its status is gone; registering it again starts at UNKNOWN. */
    public void remove(String endpointId) {
        Entry entry = entries.remove(endpointId);
        if (entry != null) {
            synchronized (entry) {
                entry.removed = true;
            }
        }
    }

    /**
     * Records one completed check. Results for an endpoint that isn't (or is no longer)
     * registered are ignored.
     *
     * @return the resulting state change, if any
     */
    public Optional<StateChange> record(String endpointId, CheckRecord check) {
        Objects.requireNonNull(check, "check");
        Entry entry = entries.get(endpointId);
        if (entry == null) {
            return Optional.empty();
        }
        synchronized (entry) {
            if (entry.removed) {
                return Optional.empty();
            }
            StateChange change = entry.apply(check);
            if (change != null) {
                log(change);
                for (Consumer<StateChange> listener : listeners) {
                    try {
                        listener.accept(change);
                    } catch (RuntimeException e) {
                        logger.log(Level.ERROR, "State change listener failed for " + endpointId, e);
                    }
                }
            }
            return Optional.ofNullable(change);
        }
    }

    /**
     * Puts an endpoint into maintenance window {@code windowId} since {@code since}, or takes it out
     * ({@code windowId == null}). Kept independently of registration, so an endpoint added during a
     * window is shown in maintenance at once.
     */
    public void setMaintenance(String endpointId, String windowId, Instant since) {
        Objects.requireNonNull(endpointId, "endpointId");
        if (windowId == null) {
            maintenance.remove(endpointId);
        } else {
            maintenance.put(endpointId, new Maintenance(windowId, Objects.requireNonNull(since, "since")));
        }
    }

    /** The maintenance window the endpoint is in, if any. */
    public Optional<String> maintenanceWindow(String endpointId) {
        Maintenance m = maintenance.get(endpointId);
        return m == null ? Optional.empty() : Optional.of(m.windowId());
    }

    public Optional<EndpointStatus> status(String endpointId) {
        Entry entry = entries.get(endpointId);
        if (entry == null) {
            return Optional.empty();
        }
        synchronized (entry) {
            return Optional.of(withMaintenance(entry.snapshot()));
        }
    }

    /** Snapshots of every registered endpoint, in no particular order. */
    public List<EndpointStatus> all() {
        List<EndpointStatus> result = new ArrayList<>();
        for (Entry entry : entries.values()) {
            synchronized (entry) {
                if (!entry.removed) {
                    result.add(withMaintenance(entry.snapshot()));
                }
            }
        }
        return result;
    }

    private EndpointStatus withMaintenance(EndpointStatus s) {
        Maintenance m = maintenance.get(s.endpointId());
        if (m == null) {
            return s;
        }
        return new EndpointStatus(s.endpointId(), State.MAINTENANCE, m.since(), s.failureThreshold(),
                s.failureCount(), s.streakStart(), s.firstFailureMessage(), s.lastCheck(), s.recentChecks(),
                s.state(), m.windowId());
    }

    private void log(StateChange change) {
        StringBuilder sb = new StringBuilder();
        sb.append(change.endpointId()).append(": ").append(change.from()).append(" -> ").append(change.to());
        if (change.to() == State.DOWN) {
            sb.append(" after ").append(change.failureCount())
                    .append(change.failureCount() == 1 ? " failure" : " consecutive failures")
                    .append(" since ").append(change.streakStart());
        } else if (change.from() == State.DOWN) {
            sb.append(", down since ").append(change.streakStart());
        }
        sb.append(" (").append(change.check().message()).append(')');
        logger.log(change.to() == State.DOWN ? Level.WARNING : Level.INFO, sb.toString());
    }

    private static void checkThreshold(int failureThreshold) {
        if (failureThreshold < 1) {
            throw new IllegalArgumentException("failureThreshold must be at least 1: " + failureThreshold);
        }
    }

    private static final class Entry {
        final String endpointId;
        int failureThreshold = 1;
        boolean removed;
        State state = State.UNKNOWN;
        Instant since;
        int failureCount;
        Instant streakStart;
        String firstFailureMessage;
        final ArrayDeque<CheckRecord> recent = new ArrayDeque<>(RECENT_CHECKS);

        Entry(String endpointId) {
            this.endpointId = endpointId;
        }

        StateChange apply(CheckRecord check) {
            if (recent.size() == RECENT_CHECKS) {
                recent.removeFirst();
            }
            recent.addLast(check);

            State from = state;
            if (check.success()) {
                Instant oldStart = streakStart;
                String oldMessage = firstFailureMessage;
                int oldCount = failureCount;
                failureCount = 0;
                streakStart = null;
                firstFailureMessage = null;
                if (from == State.UP) {
                    return null;
                }
                state = State.UP;
                since = check.time();
                if (from == State.DOWN) {
                    return new StateChange(endpointId, from, State.UP, check, oldStart, oldMessage, oldCount);
                }
                return new StateChange(endpointId, from, State.UP, check, null, null, 0);
            }

            if (failureCount == 0) {
                streakStart = check.time();
                firstFailureMessage = check.message();
            }
            failureCount++;
            boolean goDown = from == State.UNKNOWN || (from == State.UP && failureCount >= failureThreshold);
            if (!goDown) {
                return null;
            }
            state = State.DOWN;
            since = check.time();
            return new StateChange(endpointId, from, State.DOWN, check, streakStart, firstFailureMessage, failureCount);
        }

        EndpointStatus snapshot() {
            return new EndpointStatus(endpointId, state, since, failureThreshold, failureCount, streakStart,
                    firstFailureMessage, recent.peekLast(), new ArrayList<>(recent));
        }
    }
}
