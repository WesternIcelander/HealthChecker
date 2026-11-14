package io.siggi.healthchecker.notify;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.siggi.healthchecker.config.EndpointDefinition;
import io.siggi.healthchecker.history.AtomicFiles;
import io.siggi.healthchecker.status.State;
import io.siggi.healthchecker.status.StateChange;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * "Still down" reminders while an outage lasts.
 *
 * <p>An endpoint's {@code reminders} are minutes after the outage started; after the last of
 * them, {@code reminderRepeatMinutes} (if more than 0) keeps repeating at that interval. An
 * empty {@code reminders} list disables reminders, repeat included. The endpoint's current
 * definition is used every time the next reminder is planned.
 *
 * <p>Pending reminders (endpoint, outage start, how many were sent) are stored in
 * {@code data/notifications/state.json}, rewritten atomically on every change. After a
 * restart ({@link #load}), reminders that became due while the app was down are sent as one
 * reminder, not one per missed slot.
 *
 * <p>Thread-safe.
 */
public final class ReminderScheduler implements AutoCloseable {
    /** What a reminder needs to know about an endpoint right now. */
    public record Target(EndpointDefinition endpoint, String failureMessage, String link) {
        public Target {
            Objects.requireNonNull(endpoint, "endpoint");
        }
    }

    /** Looks up an endpoint; empty if it no longer exists (its reminders are dropped then). */
    @FunctionalInterface
    public interface Source {
        Optional<Target> lookup(String endpointId);
    }

    /** Where reminders go, normally {@link NotificationDispatcher#notify}. */
    @FunctionalInterface
    public interface Sink {
        void remind(EndpointDefinition endpoint, Notification notification);
    }

    private static final class Entry {
        final String endpointId;
        final Instant outageStart;
        /** Reminder slots already used (sent or skipped). */
        int sent;
        /** Loaded from disk and not yet confirmed by a DOWN since the restart. */
        boolean restored;

        Entry(String endpointId, Instant outageStart, int sent, boolean restored) {
            this.endpointId = endpointId;
            this.outageStart = outageStart;
            this.sent = sent;
            this.restored = restored;
        }
    }

    private final Path stateFile;
    private final Source source;
    private final Sink sink;
    private final TaskScheduler scheduler;
    private final Clock clock;
    private final Logger logger;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private boolean closed;

    /**
     * @param stateFile where pending reminders are kept ({@link #stateFile(Path)})
     * @param scheduler runs the reminder timers; closed with this
     */
    public ReminderScheduler(Path stateFile, Source source, Sink sink, TaskScheduler scheduler, Clock clock,
                             Logger logger) {
        this.stateFile = Objects.requireNonNull(stateFile, "stateFile");
        this.source = Objects.requireNonNull(source, "source");
        this.sink = Objects.requireNonNull(sink, "sink");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /** {@code {dataDir}/notifications/state.json}. */
    public static Path stateFile(Path dataDir) {
        return dataDir.resolve("notifications").resolve("state.json");
    }

    /**
     * When reminder {@code index} (0-based) is due, as time after the outage start; empty if
     * there is no such reminder.
     */
    public static Optional<Duration> offset(List<Integer> reminders, int repeatMinutes, int index) {
        if (index < 0 || reminders.isEmpty()) {
            return Optional.empty();
        }
        if (index < reminders.size()) {
            return Optional.of(Duration.ofMinutes(reminders.get(index)));
        }
        if (repeatMinutes <= 0) {
            return Optional.empty();
        }
        long last = reminders.get(reminders.size() - 1);
        long extra = (long) (index - reminders.size() + 1) * repeatMinutes;
        return Optional.of(Duration.ofMinutes(last + extra));
    }

    /** Number of reminder slots due at or before {@code elapsed} into the outage. */
    static int dueCount(List<Integer> reminders, int repeatMinutes, Duration elapsed) {
        if (reminders.isEmpty() || elapsed.isNegative()) {
            return 0;
        }
        int count = 0;
        for (int m : reminders) {
            if (Duration.ofMinutes(m).compareTo(elapsed) > 0) {
                return count;
            }
            count++;
        }
        if (repeatMinutes <= 0) {
            return count;
        }
        // every listed reminder is due; repeats come at last + k × repeat, k ≥ 1
        Duration sinceLast = elapsed.minus(Duration.ofMinutes(reminders.get(reminders.size() - 1)));
        long repeats = sinceLast.dividedBy(Duration.ofMinutes(repeatMinutes));
        return (int) Math.min(Integer.MAX_VALUE, count + repeats);
    }

    /**
     * Reads the stored state and plans each pending outage's next reminder. Reminders already
     * overdue are sent now, once per outage. Call once, before state changes arrive.
     */
    public void load() {
        List<Entry> loaded = new ArrayList<>();
        try {
            String json = Files.readString(stateFile, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonArray array = root.has("reminders") ? root.getAsJsonArray("reminders") : new JsonArray();
            for (JsonElement e : array) {
                JsonObject o = e.getAsJsonObject();
                loaded.add(new Entry(o.get("endpointId").getAsString(),
                        Instant.parse(o.get("outageStart").getAsString()), o.get("sent").getAsInt(), true));
            }
        } catch (NoSuchFileException e) {
            return;
        } catch (IOException | RuntimeException e) {
            logger.log(Level.ERROR, "Could not read " + stateFile + ", starting without pending reminders: " + e);
            return;
        }
        synchronized (this) {
            for (Entry entry : loaded) {
                entries.put(entry.endpointId, entry);
            }
            for (Entry entry : loaded) {
                plan(entry);
            }
            save();
        }
    }

    /**
     * Feeds a state change: a change to DOWN starts reminders for that outage, a change to UP
     * cancels them.
     */
    public void onStateChange(StateChange change) {
        if (change.to() == State.DOWN) {
            start(change.endpointId(), change.streakStart() != null ? change.streakStart() : change.time());
        } else if (change.to() == State.UP) {
            cancel(change.endpointId());
        }
    }

    /**
     * Starts reminders for an outage that began at {@code outageStart}. Slots already due by
     * now are skipped (the down notification covers them). If reminders restored after a
     * restart are pending for the endpoint, they continue instead (same outage start, nothing
     * re-sent).
     */
    public synchronized void start(String endpointId, Instant outageStart) {
        Objects.requireNonNull(endpointId, "endpointId");
        Objects.requireNonNull(outageStart, "outageStart");
        if (closed) {
            return;
        }
        Entry existing = entries.get(endpointId);
        if (existing != null && existing.restored) {
            existing.restored = false;
            return;
        }
        Optional<Target> target = source.lookup(endpointId);
        if (target.isEmpty()) {
            return;
        }
        EndpointDefinition def = target.get().endpoint();
        int skipped = dueCount(def.reminders(), def.reminderRepeatMinutes(),
                Duration.between(outageStart, clock.instant()));
        Entry entry = new Entry(endpointId, outageStart, skipped, false);
        entries.put(endpointId, entry);
        plan(entry);
        save();
    }

    /** Cancels pending reminders for the endpoint (recovery, removal, replacement). */
    public synchronized void cancel(String endpointId) {
        if (entries.remove(endpointId) != null) {
            save();
        }
    }

    /**
     * Re-plans the endpoint's next reminder from its current definition (after its
     * {@code reminders} were edited).
     */
    public synchronized void refresh(String endpointId) {
        Entry entry = entries.get(endpointId);
        if (entry != null) {
            plan(entry);
            save();
        }
    }

    /** When the endpoint's next reminder is due, if one is planned. */
    public synchronized Optional<Instant> nextReminder(String endpointId) {
        Entry entry = entries.get(endpointId);
        if (entry == null) {
            return Optional.empty();
        }
        return source.lookup(endpointId).flatMap(t -> offset(t.endpoint().reminders(),
                t.endpoint().reminderRepeatMinutes(), entry.sent)).map(entry.outageStart::plus);
    }

    /** Endpoints with an outage being tracked for reminders. */
    public synchronized List<String> pendingEndpoints() {
        return List.copyOf(entries.keySet());
    }

    @Override
    public synchronized void close() {
        closed = true;
        scheduler.close();
    }

    // ---- internals -----------------------------------------------------------------------

    /**
     * Sends a reminder if any are due (one, however many slots are), then sets a timer for
     * the next one. Holds the lock; the caller saves.
     */
    private void plan(Entry entry) {
        if (closed || entries.get(entry.endpointId) != entry) {
            return;
        }
        Optional<Target> found = source.lookup(entry.endpointId);
        if (found.isEmpty()) {
            entries.remove(entry.endpointId);
            return;
        }
        Target target = found.get();
        EndpointDefinition def = target.endpoint();
        Instant now = clock.instant();
        int due = dueCount(def.reminders(), def.reminderRepeatMinutes(), Duration.between(entry.outageStart, now));
        if (due > entry.sent) {
            entry.sent = due;
            Notification reminder = new Notification(Notification.Kind.REMINDER, def.id(), def.name(), now,
                    entry.outageStart, target.failureMessage(), null, target.link());
            try {
                sink.remind(def, reminder);
            } catch (RuntimeException e) {
                logger.log(Level.ERROR, "Could not queue reminder for " + entry.endpointId + ": " + e);
            }
        }
        Optional<Duration> next = offset(def.reminders(), def.reminderRepeatMinutes(), entry.sent);
        if (next.isEmpty()) {
            return;
        }
        Duration delay = Duration.between(now, entry.outageStart.plus(next.get()));
        scheduler.schedule(() -> fire(entry), delay.isNegative() ? Duration.ZERO : delay);
    }

    private synchronized void fire(Entry entry) {
        if (closed || entries.get(entry.endpointId) != entry) {
            return;
        }
        int before = entry.sent;
        plan(entry);
        if (entries.get(entry.endpointId) != entry || entry.sent != before) {
            save();
        }
    }

    private void save() {
        JsonArray array = new JsonArray();
        for (Entry e : entries.values()) {
            JsonObject o = new JsonObject();
            o.addProperty("endpointId", e.endpointId);
            o.addProperty("outageStart", e.outageStart.toString());
            o.addProperty("sent", e.sent);
            array.add(o);
        }
        JsonObject root = new JsonObject();
        root.add("reminders", array);
        try {
            AtomicFiles.write(stateFile, new Gson().toJson(root).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            logger.log(Level.ERROR, "Could not write " + stateFile + ": " + e);
        }
    }
}
