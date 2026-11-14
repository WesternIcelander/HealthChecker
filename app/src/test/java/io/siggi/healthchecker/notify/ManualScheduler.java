package io.siggi.healthchecker.notify;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** A {@link TaskScheduler} with a manual clock: tasks run only from {@link #advance}/{@link #runDue}. */
final class ManualScheduler extends Clock implements TaskScheduler {
    private record Task(Instant due, long seq, Runnable task) {
    }

    private Instant now;
    private long seq;
    private final List<Task> tasks = new ArrayList<>();
    boolean closed;

    ManualScheduler(Instant start) {
        this.now = start;
    }

    @Override
    public synchronized void schedule(Runnable task, Duration delay) {
        tasks.add(new Task(now.plus(delay), seq++, task));
    }

    @Override
    public void execute(Runnable task) {
        schedule(task, Duration.ZERO);
    }

    @Override
    public void close() {
        closed = true;
    }

    /** Runs every task due now, including ones they schedule for now. */
    void runDue() {
        while (true) {
            Task next;
            synchronized (this) {
                next = tasks.stream().filter(t -> !t.due().isAfter(now))
                        .min(Comparator.comparing(Task::due).thenComparingLong(Task::seq)).orElse(null);
                if (next == null) return;
                tasks.remove(next);
            }
            next.task().run();
        }
    }

    /** Moves time forward by {@code d}, stepping through every task due on the way. */
    void advance(Duration d) {
        Instant target = now.plus(d);
        runDue();
        while (true) {
            Task next;
            synchronized (this) {
                next = tasks.stream().filter(t -> !t.due().isAfter(target))
                        .min(Comparator.comparing(Task::due).thenComparingLong(Task::seq)).orElse(null);
                if (next == null) break;
                now = next.due();
            }
            runDue();
        }
        synchronized (this) {
            now = target;
        }
        runDue();
    }

    synchronized int pendingTasks() {
        return tasks.size();
    }

    @Override
    public synchronized Instant instant() {
        return now;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        throw new UnsupportedOperationException();
    }
}
