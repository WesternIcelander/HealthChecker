package io.siggi.healthchecker.maintenance;

import io.siggi.healthchecker.history.HistoryStore;
import io.siggi.healthchecker.status.StatusRegistry;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Starts and ends maintenance windows: puts the affected endpoints into maintenance in the
 * {@link StatusRegistry} (shown as {@code MAINTENANCE}) and the {@link HistoryStore} (downtime
 * recorded as {@code maintenance} events, split at the window's edges), and takes them out again.
 *
 * <p>{@link #tick()} brings everything up to date with the clock: it stores occurrences of series
 * that have started ({@link MaintenanceStore#materialise()}), finds the windows in progress and, per
 * endpoint, the one it's in (by its category at that moment; with overlapping windows the one that
 * started first). A change takes effect at the window's own start or end time when that has passed
 * (so a late tick still splits events at the right moment), else now. Once {@link #start() started},
 * ticks run at every window start and end (checked at least every {@link #MAX_TICK_INTERVAL}) and
 * after every change of the store. "End now" and "Extend" ({@link #endNow}, {@link #extend}) apply
 * at once.
 *
 * <p>Thread-safe.
 */
public final class MaintenanceScheduler implements AutoCloseable {
    /**
     * Told what a tick changed (notifications). Called on the ticking thread under the scheduler's lock,
     * after the {@link StatusRegistry} and {@link HistoryStore} were updated. Exceptions are logged.
     */
    public interface Listener {
        /**
         * An endpoint moved from window {@code fromWindowId} to {@code toWindowId} (either may be
         * {@code null}: it entered or left maintenance) at {@code at}.
         */
        default void endpointChanged(String endpointId, String fromWindowId, String toWindowId, Instant at) {
        }

        /** A tick at {@code now} has been applied. */
        default void ticked(Instant now) {
        }
    }

    /** Longest time between two ticks. */
    public static final Duration MAX_TICK_INTERVAL = Duration.ofSeconds(30);

    private final MaintenanceStore store;
    private final StatusRegistry status;
    private final HistoryStore history;
    private final Supplier<Map<String, String>> endpointCategories;
    private final Clock clock;
    private final Logger logger;
    private final Runnable storeListener = this::tickSoon;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    /** Endpoint id → window it's in. */
    private final Map<String, MaintenanceWindow> active = new HashMap<>();
    private final Set<String> running = new HashSet<>();
    private volatile ScheduledExecutorService executor;
    private ScheduledFuture<?> next;

    /**
     * @param endpointCategories every current endpoint id with the id of the category it's in at that
     *                           moment ({@code null} for none)
     */
    public MaintenanceScheduler(MaintenanceStore store, StatusRegistry status, HistoryStore history,
                                Supplier<Map<String, String>> endpointCategories, Clock clock, Logger logger) {
        this.store = Objects.requireNonNull(store, "store");
        this.status = Objects.requireNonNull(status, "status");
        this.history = Objects.requireNonNull(history, "history");
        this.endpointCategories = Objects.requireNonNull(endpointCategories, "endpointCategories");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public MaintenanceScheduler(MaintenanceStore store, StatusRegistry status, HistoryStore history,
                                Supplier<Map<String, String>> endpointCategories, Clock clock) {
        this(store, status, history, endpointCategories, clock,
                System.getLogger(MaintenanceScheduler.class.getName()));
    }

    /** Applies every start and end up to now. */
    public synchronized void tick() {
        try {
            store.materialise();
        } catch (IOException | RuntimeException e) {
            logger.log(Level.ERROR, "Couldn't store started maintenance occurrences; retrying", e);
        }
        Instant now = clock.instant();
        List<MaintenanceWindow> inProgress = store.occurrences(now, now.plusNanos(1)).stream()
                .filter(w -> w.status(now) == MaintenanceWindow.Status.IN_PROGRESS)
                .sorted(Comparator.comparing(MaintenanceWindow::start).thenComparing(MaintenanceWindow::id))
                .toList();

        Set<String> wasRunning = Set.copyOf(running);
        Set<String> nowRunning = new HashSet<>();
        inProgress.forEach(w -> nowRunning.add(w.id()));
        for (MaintenanceWindow w : inProgress) {
            if (!running.contains(w.id())) {
                logger.log(Level.INFO, "Maintenance \"" + w.title() + "\" (" + w.id() + ") started");
            }
        }
        for (String id : running) {
            if (!nowRunning.contains(id)) {
                logger.log(Level.INFO, "Maintenance " + id + " ended");
            }
        }
        running.clear();
        running.addAll(nowRunning);

        Map<String, MaintenanceWindow> desired = new HashMap<>();
        for (Map.Entry<String, String> e : endpointCategories.get().entrySet()) {
            for (MaintenanceWindow w : inProgress) {
                if (w.affects(e.getKey(), e.getValue())) {
                    desired.put(e.getKey(), w);
                    break;
                }
            }
        }
        Set<String> endpoints = new HashSet<>(active.keySet());
        endpoints.addAll(desired.keySet());
        for (String endpointId : endpoints) {
            MaintenanceWindow old = active.get(endpointId);
            MaintenanceWindow neu = desired.get(endpointId);
            String oldId = old == null ? null : old.id();
            String newId = neu == null ? null : neu.id();
            if (Objects.equals(oldId, newId)) {
                active.put(endpointId, neu); // keep the latest version (times may have changed)
                continue;
            }
            Instant at = transitionTime(old, neu, neu != null && wasRunning.contains(neu.id()), now);
            history.setMaintenance(endpointId, newId, at);
            status.setMaintenance(endpointId, newId, at);
            if (neu == null) {
                active.remove(endpointId);
            } else {
                active.put(endpointId, neu);
            }
            for (Listener l : listeners) {
                try {
                    l.endpointChanged(endpointId, oldId, newId, at);
                } catch (RuntimeException e) {
                    logger.log(Level.ERROR, "Maintenance listener failed for " + endpointId, e);
                }
            }
        }
        for (Listener l : listeners) {
            try {
                l.ticked(now);
            } catch (RuntimeException e) {
                logger.log(Level.ERROR, "Maintenance listener failed", e);
            }
        }
    }

    /** Adds a listener for the changes ticks make. Add listeners before {@link #start()}. */
    public void addListener(Listener listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /**
     * When an endpoint moves from window {@code old} to {@code neu} (either may be {@code null}): the
     * old window's end if it has ended (looked up again, as "End now" or "Extend" may have moved it),
     * else now (it was left by an edit); the new window's start if that's later, or now if the new
     * window was already running at the last tick (the endpoint joined it later, e.g. by moving into
     * one of its categories); never after now.
     */
    private Instant transitionTime(MaintenanceWindow old, MaintenanceWindow neu, boolean joinedLater, Instant now) {
        Instant at;
        if (old == null) {
            at = joinedLater ? now : neu.start();
        } else {
            Instant end = store.occurrence(old.id()).filter(w -> w.cancelledAt() == null)
                    .map(MaintenanceWindow::end).orElse(now);
            at = end.isAfter(now) ? now : end;
            if (neu != null && neu.start().isAfter(at)) {
                at = neu.start();
            }
        }
        return at.isAfter(now) ? now : at;
    }

    /** The window the endpoint is in, as of the last tick. */
    public synchronized Optional<MaintenanceWindow> windowOf(String endpointId) {
        return Optional.ofNullable(active.get(endpointId));
    }

    /** "End now" ({@link MaintenanceStore#endNow}), applied at once. */
    public Optional<MaintenanceWindow> endNow(String id, int expectedVersion, MaintenanceStore.Actor actor)
            throws MaintenanceStore.InvalidWindowException, MaintenanceStore.VersionConflictException, IOException {
        Optional<MaintenanceWindow> result = store.endNow(id, expectedVersion, actor);
        tick();
        return result;
    }

    /** "Extend" ({@link MaintenanceStore#extend}), applied at once. */
    public Optional<MaintenanceWindow> extend(String id, int expectedVersion, Instant end,
                                              MaintenanceStore.Actor actor)
            throws MaintenanceStore.InvalidWindowException, MaintenanceStore.VersionConflictException, IOException {
        Optional<MaintenanceWindow> result = store.extend(id, expectedVersion, end, actor);
        tick();
        return result;
    }

    /** The next start or end of a window after now, if any is planned within {@link #MAX_TICK_INTERVAL}. */
    private Optional<Instant> nextTransition(Instant now) {
        Instant limit = now.plus(MAX_TICK_INTERVAL);
        Instant best = null;
        for (MaintenanceWindow w : store.occurrences(now, limit)) {
            if (w.cancelledAt() != null) continue;
            for (Instant t : new Instant[]{w.start(), w.end()}) {
                if (t.isAfter(now) && t.isBefore(limit) && (best == null || t.isBefore(best))) best = t;
            }
        }
        return Optional.ofNullable(best);
    }

    /** Ticks now, then at every window start and end, and after every change of the store. */
    public synchronized void start() {
        if (executor != null) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon().name("maintenance-scheduler").factory());
        store.addChangeListener(storeListener);
        tick();
        scheduleNext();
    }

    /** Runs a tick on the scheduler's thread. Called under the store's lock, so it doesn't take this one. */
    private void tickSoon() {
        ScheduledExecutorService e = executor;
        if (e == null) return;
        try {
            e.execute(this::runScheduled);
        } catch (RejectedExecutionException ex) {
            // closed meanwhile
        }
    }

    private void runScheduled() {
        try {
            tick();
        } catch (RuntimeException e) {
            logger.log(Level.ERROR, "Maintenance tick failed", e);
        }
        synchronized (this) {
            if (executor != null) scheduleNext();
        }
    }

    private void scheduleNext() {
        if (next != null) next.cancel(false);
        if (executor.isShutdown()) return;
        Instant now = clock.instant();
        Duration delay = nextTransition(now).map(t -> Duration.between(now, t)).orElse(MAX_TICK_INTERVAL);
        // A tick a little after the transition, so the window's status has changed by then.
        next = executor.schedule(this::runScheduled, delay.toMillis() + 5, TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() {
        ScheduledExecutorService e;
        synchronized (this) {
            e = executor;
            executor = null;
            next = null;
        }
        if (e != null) {
            store.removeChangeListener(storeListener);
            e.shutdownNow();
        }
    }
}
