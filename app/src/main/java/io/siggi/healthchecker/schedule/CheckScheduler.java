package io.siggi.healthchecker.schedule;

import io.siggi.healthchecker.api.CheckResult;
import io.siggi.healthchecker.status.CheckRecord;
import io.siggi.healthchecker.status.StateChange;
import io.siggi.healthchecker.status.StatusRegistry;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs every endpoint of an {@link EndpointRegistry} on its own schedule and records the
 * results in a {@link StatusRegistry}. Follows the registry live: added endpoints start
 * being checked, in-place updates keep the schedule, replaced endpoints restart with the
 * new checker (and state UNKNOWN), removed ones stop.
 *
 * <ul>
 *   <li>Each endpoint runs every {@code intervalSeconds}, first after a random delay of up
 *       to {@code min(interval, maxStartJitter)}, so endpoints don't all run at once.</li>
 *   <li>Each {@code check()} runs on a new virtual thread. After {@code timeout} plus a
 *       grace period it's interrupted and recorded as a failure
 *       ("timeout after N ms"); a later result from it is discarded.</li>
 *   <li>A run is skipped while the previous check is still running. If that check has
 *       already timed out (it's stuck), the skip is recorded as a failure; if it's still
 *       within its timeout, the run is skipped silently.</li>
 *   <li>A thrown {@code Throwable} or a {@code null} result is a failure. The message
 *       names only the exception class, since exception texts can contain the target.</li>
 *   <li>When an endpoint is replaced or removed, its in-flight check is interrupted and its
 *       result discarded, no further check starts, and its checker is closed once that
 *       check returns, or after the grace period at the latest.</li>
 * </ul>
 */
public final class CheckScheduler implements AutoCloseable {
    /** Extra time a check gets beyond its timeout before it's interrupted. */
    public static final Duration DEFAULT_GRACE = Duration.ofSeconds(1);
    /** Upper bound for the random delay before an endpoint's first check. */
    public static final Duration DEFAULT_MAX_START_JITTER = Duration.ofSeconds(10);

    private final EndpointRegistry registry;
    private final StatusRegistry status;
    private final Clock clock;
    private final Duration grace;
    private final Duration maxStartJitter;
    private final Duration secondLength;
    private final Logger logger;
    private final ScheduledExecutorService timer;
    private final Map<String, Task> tasks = new HashMap<>();
    private final Listener listener = new Listener();
    private final List<CheckListener> checkListeners = new CopyOnWriteArrayList<>();
    private boolean closed;

    /** Why an endpoint stopped being checked with its checker. */
    public enum StopReason {
        /** Its check changed: a new checker replaces the old one. */
        REPLACED,
        /** It was removed. */
        REMOVED
    }

    /** Gets every recorded check result, e.g. to write history. */
    public interface CheckListener {
        /**
         * Called after a result was recorded in the {@link StatusRegistry}, in order per
         * endpoint, on the thread that recorded it.
         *
         * @param change the state change the result caused, or {@code null}
         */
        void checked(String endpointId, CheckRecord check, StateChange change);

        /**
         * Called when an endpoint stops being checked with its current checker, before a
         * replacement (if any) starts.
         */
        default void stopped(String endpointId, StopReason reason) {
        }
    }

    /** Adds a listener for recorded results; add it before {@link #start()} to miss nothing. */
    public void addCheckListener(CheckListener listener) {
        checkListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    private void record(String endpointId, CheckRecord check) {
        StateChange change = status.record(endpointId, check).orElse(null);
        for (CheckListener l : checkListeners) {
            try {
                l.checked(endpointId, check, change);
            } catch (RuntimeException e) {
                logger.log(Level.ERROR, "Check listener failed for " + endpointId, e);
            }
        }
    }

    private void fireStopped(String endpointId, StopReason reason) {
        for (CheckListener l : checkListeners) {
            try {
                l.stopped(endpointId, reason);
            } catch (RuntimeException e) {
                logger.log(Level.ERROR, "Check listener failed for " + endpointId, e);
            }
        }
    }

    public CheckScheduler(EndpointRegistry registry, StatusRegistry status) {
        this(registry, status, Clock.systemUTC(), DEFAULT_GRACE, DEFAULT_MAX_START_JITTER, Duration.ofSeconds(1),
                System.getLogger(CheckScheduler.class.getName()));
    }

    /**
     * @param secondLength the length of one "second" of {@code intervalSeconds}; tests
     *                     shorten it to keep intervals small
     */
    CheckScheduler(EndpointRegistry registry, StatusRegistry status, Clock clock, Duration grace,
                   Duration maxStartJitter, Duration secondLength, Logger logger) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.status = Objects.requireNonNull(status, "status");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.grace = Objects.requireNonNull(grace, "grace");
        this.maxStartJitter = Objects.requireNonNull(maxStartJitter, "maxStartJitter");
        this.secondLength = Objects.requireNonNull(secondLength, "secondLength");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "check-scheduler");
            t.setDaemon(true);
            return t;
        });
    }

    /** Starts checking every endpoint in the registry, and follows its changes from now on. */
    public void start() {
        // Not synchronized on this: subscribe() takes the registry lock, then the listener takes ours.
        registry.subscribe(listener);
    }

    /**
     * Stops all checks, waits up to the grace period for in-flight ones, and closes every
     * checker. The registry keeps its endpoints.
     */
    @Override
    public void close() {
        registry.unsubscribe(listener);
        List<Task> stopped;
        synchronized (this) {
            if (closed) return;
            closed = true;
            stopped = new ArrayList<>(tasks.values());
            tasks.clear();
        }
        for (Task t : stopped) {
            t.stop(false);
        }
        long deadline = System.nanoTime() + grace.toNanos();
        for (Task t : stopped) {
            Thread thread = t.runningThread();
            if (thread != null) {
                long left = deadline - System.nanoTime();
                if (left > 0) {
                    try {
                        thread.join(Duration.ofNanos(left));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        for (Task t : stopped) {
            t.closeChecker();
        }
        timer.shutdownNow();
    }

    private final class Listener implements EndpointRegistry.Listener {
        @Override
        public void added(Endpoint endpoint) {
            synchronized (CheckScheduler.this) {
                if (closed) return;
                status.register(endpoint.id(), endpoint.failureThreshold());
                startTask(endpoint);
            }
        }

        @Override
        public void updated(Endpoint old, Endpoint now) {
            synchronized (CheckScheduler.this) {
                Task t = tasks.get(now.id());
                if (t != null) t.endpoint = now;
            }
        }

        @Override
        public void replaced(Endpoint old, Endpoint now) {
            synchronized (CheckScheduler.this) {
                Task t = tasks.remove(old.id());
                if (t != null) t.stop(true);
                if (closed) {
                    EndpointFactory.closeQuietly(now.checker());
                    return;
                }
                status.remove(old.id());
                fireStopped(old.id(), StopReason.REPLACED);
                status.register(now.id(), now.failureThreshold());
                startTask(now);
            }
        }

        @Override
        public void removed(Endpoint old) {
            synchronized (CheckScheduler.this) {
                Task t = tasks.remove(old.id());
                if (t != null) t.stop(true);
                status.remove(old.id());
                fireStopped(old.id(), StopReason.REMOVED);
            }
        }
    }

    // Called with this locked.
    private void startTask(Endpoint endpoint) {
        Task task = new Task(endpoint);
        tasks.put(endpoint.id(), task);
        long interval = secondLength.toNanos() * endpoint.intervalSeconds();
        long maxJitter = Math.min(interval, maxStartJitter.toNanos());
        long delay = maxJitter <= 0 ? 0 : ThreadLocalRandom.current().nextLong(maxJitter);
        task.future = timer.scheduleAtFixedRate(task::tick, delay, interval, TimeUnit.NANOSECONDS);
    }

    /** One run of {@code check()}. */
    private static final class Run {
        final Endpoint endpoint;
        final Start start;
        Thread thread;
        ScheduledFuture<?> watchdog;
        boolean timedOut;

        Run(Endpoint endpoint, Start start) {
            this.endpoint = endpoint;
            this.start = start;
        }
    }

    private record Start(Instant time, long nanos) {
        Duration elapsed() {
            return Duration.ofNanos(System.nanoTime() - nanos);
        }
    }

    /** The schedule of one endpoint (one checker). */
    private final class Task {
        volatile Endpoint endpoint;
        ScheduledFuture<?> future;
        // Guarded by this task.
        private Run running;
        private boolean stopped;
        private final AtomicBoolean checkerClosed = new AtomicBoolean();

        Task(Endpoint endpoint) {
            this.endpoint = endpoint;
        }

        void tick() {
            try {
                tick0();
            } catch (Throwable t) {
                // Never let an exception cancel the periodic schedule.
                logger.log(Level.ERROR, "Scheduling a check for " + endpoint.id() + " failed", t);
            }
        }

        private void tick0() {
            synchronized (this) {
                if (stopped) return;
                Endpoint ep = endpoint;
                if (running != null) {
                    if (running.timedOut) {
                        record(ep.id(), new CheckRecord(clock.instant(), Duration.ZERO,
                                CheckResult.fail("skipped, previous check still stuck")));
                    }
                    return;
                }
                Run run = new Run(ep, new Start(clock.instant(), System.nanoTime()));
                run.thread = Thread.ofVirtual().name("check-" + ep.id()).unstarted(() -> execute(run));
                running = run;
                long limit = ep.timeout().plus(grace).toNanos();
                run.watchdog = timer.schedule(() -> timeOut(run), limit, TimeUnit.NANOSECONDS);
                run.thread.start();
            }
        }

        private void execute(Run run) {
            Endpoint ep = run.endpoint;
            CheckResult result;
            try {
                result = ep.checker().check(ep.timeout());
                if (result == null) {
                    result = CheckResult.fail("check returned no result");
                }
            } catch (Throwable t) {
                if (!(t instanceof InterruptedException)) {
                    logger.log(Level.WARNING, "Check for " + ep.id() + " threw", t);
                }
                result = CheckResult.fail("check failed (" + t.getClass().getSimpleName() + ")");
            }
            CheckRecord record = new CheckRecord(run.start.time(), run.start.elapsed(), result);
            boolean close;
            synchronized (this) {
                if (running == run) running = null;
                if (run.watchdog != null) run.watchdog.cancel(false);
                if (!run.timedOut && !stopped) {
                    record(ep.id(), record);
                }
                close = stopped;
            }
            if (close) closeChecker();
        }

        private void timeOut(Run run) {
            synchronized (this) {
                if (running != run) return;
                run.timedOut = true;
                if (!stopped) {
                    record(run.endpoint.id(), new CheckRecord(run.start.time(), run.start.elapsed(),
                            CheckResult.fail("timeout after " + run.endpoint.timeout().toMillis() + " ms")));
                }
                run.thread.interrupt();
            }
        }

        /**
         * Stops the schedule. No check starts after this and no result is recorded. The
         * in-flight check (if any) is interrupted; the checker is closed when it returns,
         * or, if {@code forceCloseLater}, after the grace period at the latest.
         */
        void stop(boolean forceCloseLater) {
            boolean closeNow;
            synchronized (this) {
                if (stopped) return;
                stopped = true;
                if (future != null) future.cancel(false);
                Run run = running;
                closeNow = run == null;
                if (run != null) {
                    if (run.watchdog != null) run.watchdog.cancel(false);
                    run.thread.interrupt();
                    if (forceCloseLater) {
                        timer.schedule(this::forceClose, grace.toNanos(), TimeUnit.NANOSECONDS);
                    }
                }
            }
            if (closeNow) closeChecker();
        }

        private void forceClose() {
            synchronized (this) {
                if (running == null) return;
            }
            logger.log(Level.WARNING, "Check for " + endpoint.id()
                    + " is still running after it was stopped; closing its checker anyway");
            closeChecker();
        }

        synchronized Thread runningThread() {
            return running == null ? null : running.thread;
        }

        void closeChecker() {
            if (checkerClosed.compareAndSet(false, true)) {
                EndpointFactory.closeQuietly(endpoint.checker());
            }
        }
    }
}
