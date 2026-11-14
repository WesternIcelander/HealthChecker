package io.siggi.healthchecker.notify;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Runs the dispatcher's timers and deliveries. Replaceable in tests by a manual clock. */
public interface TaskScheduler extends AutoCloseable {
    /** Runs {@code task} after {@code delay}, as {@link #execute} would. */
    void schedule(Runnable task, Duration delay);

    /** Runs {@code task} soon, off the caller's thread. */
    void execute(Runnable task);

    @Override
    void close();

    /** One platform timer thread; tasks run on virtual threads. */
    static TaskScheduler create() {
        ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "HealthChecker-notify-timer");
            t.setDaemon(true);
            return t;
        });
        return new TaskScheduler() {
            @Override
            public void schedule(Runnable task, Duration delay) {
                timer.schedule(() -> execute(task), Math.max(0, delay.toMillis()), TimeUnit.MILLISECONDS);
            }

            @Override
            public void execute(Runnable task) {
                Thread.ofVirtual().name("HealthChecker-notify").start(task);
            }

            @Override
            public void close() {
                timer.shutdownNow();
            }
        };
    }
}
