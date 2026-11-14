package io.siggi.healthchecker.history;

import io.siggi.healthchecker.status.CheckRecord;
import io.siggi.healthchecker.status.State;
import io.siggi.healthchecker.status.StateChange;

import java.io.BufferedReader;
import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Coverage periods and downtime events of every endpoint, kept in memory and stored under
 * {@code {dataDir}/endpoints/{id}/}:
 *
 * <ul>
 *   <li>{@code coverage.json}: the list of {@code {start, end}} periods during which the
 *       endpoint was monitored.</li>
 *   <li>{@code events/{yyyy}.jsonl}: one event per line, by the UTC year of its start; the
 *       whole file is rewritten (atomically) when one of its events changes.</li>
 * </ul>
 *
 * <p>A coverage period starts with the first check recorded for an endpoint and is
 * extended by every later check and by the {@linkplain #heartbeat() heartbeat}, which runs
 * every minute and writes the current state to disk. It ends when monitoring stops
 * ({@link #stopMonitoring}, {@link #close}) or the app dies; the time until the next
 * check after a restart is a gap ("no data").
 *
 * <p>An event opens when the endpoint goes DOWN, starting at the first failed check of the
 * streak, and closes at the check that brings it back UP. Opening and closing are written
 * at once; the last failure (time and message) of an ongoing event is written with the
 * heartbeat. Events still open when the store is loaded (the app stopped while the
 * endpoint was down) are closed at their last recorded failure and marked
 * {@code endedUnobserved}.
 *
 * <p>Thread-safe. Write errors are logged and retried on the next heartbeat.
 */
public final class HistoryStore implements AutoCloseable {
    public static final Duration HEARTBEAT_INTERVAL = Duration.ofMinutes(1);

    private static final Pattern ID = Pattern.compile("[a-z0-9-]+");
    private static final Pattern YEAR_FILE = Pattern.compile("(\\d{4})\\.jsonl");
    private static final String COVERAGE_FILE = "coverage.json";
    private static final String TARGETS_FILE = "targets.json";
    private static final String EVENTS_DIR = "events";

    /** Replaces a file's content; {@link AtomicFiles#write(Path, byte[])} outside tests. */
    @FunctionalInterface
    interface FileWriter {
        void write(Path target, byte[] data) throws IOException;
    }

    private final Path endpointsDir;
    private final Clock clock;
    private final Logger logger;
    private final FileWriter writer;
    private final Map<String, EndpointHistory> histories = new TreeMap<>();
    private final List<Consumer<HistoryEvent>> listeners = new CopyOnWriteArrayList<>();
    private ScheduledExecutorService heartbeatExecutor;

    private HistoryStore(Path dataDir, Clock clock, Logger logger, FileWriter writer) {
        this.endpointsDir = dataDir.resolve("endpoints");
        this.clock = clock;
        this.logger = logger;
        this.writer = writer;
    }

    /** Loads the history under {@code dataDir} (see {@link #open(Path, Clock, Logger)}). */
    public static HistoryStore open(Path dataDir) throws IOException {
        return open(dataDir, Clock.systemUTC(), System.getLogger(HistoryStore.class.getName()));
    }

    /**
     * Loads every endpoint's history under {@code dataDir} and closes events left open by
     * the previous run. A missing directory means no history.
     *
     * @throws IOException if a file can't be read or is malformed (the message names the
     *                     file), or a closed event can't be written
     */
    public static HistoryStore open(Path dataDir, Clock clock, Logger logger) throws IOException {
        return open(dataDir, clock, logger, AtomicFiles::write);
    }

    static HistoryStore open(Path dataDir, Clock clock, Logger logger, FileWriter writer) throws IOException {
        HistoryStore store = new HistoryStore(Objects.requireNonNull(dataDir, "dataDir"),
                Objects.requireNonNull(clock, "clock"), Objects.requireNonNull(logger, "logger"),
                Objects.requireNonNull(writer, "writer"));
        store.load();
        return store;
    }

    // ---- loading ----

    private void load() throws IOException {
        if (!Files.isDirectory(endpointsDir)) {
            return;
        }
        List<Path> dirs = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(endpointsDir)) {
            for (Path dir : stream) {
                if (Files.isDirectory(dir)) {
                    dirs.add(dir);
                }
            }
        }
        dirs.sort(Comparator.naturalOrder());
        for (Path dir : dirs) {
            String id = dir.getFileName().toString();
            if (!ID.matcher(id).matches()) {
                logger.log(Level.WARNING, "Ignoring " + dir + ": not an endpoint id");
                continue;
            }
            loadEndpoint(id, dir);
        }
    }

    private void loadEndpoint(String id, Path dir) throws IOException {
        EndpointHistory h = new EndpointHistory(id);
        AtomicFiles.deleteTempFiles(dir);
        Path coverageFile = dir.resolve(COVERAGE_FILE);
        if (Files.isRegularFile(coverageFile)) {
            try {
                h.periods.addAll(HistoryCodec.decodeCoverage(Files.readString(coverageFile, StandardCharsets.UTF_8)));
            } catch (HistoryCodec.FormatException e) {
                throw new IOException(coverageFile + ": " + e.getMessage());
            }
            h.periods.sort(Comparator.comparing(CoveragePeriod::start));
        }
        Path targetsFile = dir.resolve(TARGETS_FILE);
        if (Files.isRegularFile(targetsFile)) {
            try {
                h.targets.addAll(HistoryCodec.decodeTargets(Files.readString(targetsFile, StandardCharsets.UTF_8)));
            } catch (HistoryCodec.FormatException e) {
                throw new IOException(targetsFile + ": " + e.getMessage());
            }
        }
        Path eventsDir = dir.resolve(EVENTS_DIR);
        AtomicFiles.deleteTempFiles(eventsDir);
        Map<String, Path> seen = new HashMap<>();
        if (Files.isDirectory(eventsDir)) {
            List<Path> files = new ArrayList<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(eventsDir)) {
                for (Path file : stream) {
                    files.add(file);
                }
            }
            files.sort(Comparator.naturalOrder());
            for (Path file : files) {
                Matcher m = YEAR_FILE.matcher(file.getFileName().toString());
                if (!m.matches() || !Files.isRegularFile(file)) {
                    logger.log(Level.WARNING, "Ignoring " + file + ": not an events file");
                    continue;
                }
                loadEvents(h, file, Integer.parseInt(m.group(1)), seen);
            }
        }
        h.sortEvents();
        histories.put(id, h);

        // Restart handling: an event still open means the app stopped while the endpoint was down.
        for (HistoryEvent e : List.copyOf(h.events)) {
            if (e.ongoing()) {
                HistoryEvent closed = e.closed(e.lastFailureAt(), true);
                h.replace(closed);
                logger.log(Level.INFO, id + ": closed outage " + e.id() + " that was open when the app stopped"
                        + " (last failure " + e.lastFailureAt() + ")");
            }
        }
        if (!h.dirtyYears.isEmpty()) {
            for (int year : Set.copyOf(h.dirtyYears)) {
                writeYear(h, year);
                h.dirtyYears.remove(year);
            }
        }
    }

    private static void loadEvents(EndpointHistory h, Path file, int year, Map<String, Path> seen) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) {
                    continue;
                }
                String where = file + ":" + lineNumber + ": ";
                HistoryEvent e;
                try {
                    e = HistoryCodec.decodeEvent(line);
                } catch (HistoryCodec.FormatException ex) {
                    throw new IOException(where + ex.getMessage());
                }
                if (!e.endpointId().equals(h.id)) {
                    throw new IOException(where + "event " + e.id() + " belongs to endpoint \"" + e.endpointId() + "\"");
                }
                if (yearOf(e.start()) != year) {
                    throw new IOException(where + "event " + e.id() + " starts in " + yearOf(e.start()));
                }
                Path previous = seen.putIfAbsent(e.id(), file);
                if (previous != null) {
                    throw new IOException(where + "duplicate event id " + e.id() + " (also in " + previous + ")");
                }
                h.events.add(e);
            }
        }
    }

    // ---- recording ----

    /** Adds a listener called (under the store's lock) whenever an event is opened or closed. */
    public void addListener(Consumer<HistoryEvent> listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    public void removeListener(Consumer<HistoryEvent> listener) {
        listeners.remove(listener);
    }

    /**
     * Records a completed check and the state change it caused, if any (the result of
     * {@code StatusRegistry.record}). Starts or extends the endpoint's coverage period,
     * opens an event on a change to DOWN and closes it on a change from DOWN.
     */
    public synchronized void record(String endpointId, CheckRecord check, StateChange change) {
        Objects.requireNonNull(endpointId, "endpointId");
        Objects.requireNonNull(check, "check");
        if (change != null && !change.endpointId().equals(endpointId)) {
            throw new IllegalArgumentException("state change is for " + change.endpointId() + ", not " + endpointId);
        }
        EndpointHistory h = histories.computeIfAbsent(endpointId, EndpointHistory::new);
        extendCoverage(h, check.time(), check.time().plus(check.latency()));

        if (change != null && change.to() == State.DOWN) {
            HistoryEvent stale = h.openEvent();
            if (stale != null) {
                closeEvent(h, stale.closed(stale.lastFailureAt(), true));
            }
            Instant start = change.streakStart() != null ? change.streakStart() : check.time();
            HistoryEvent e = new HistoryEvent(UUID.randomUUID().toString(), endpointId, HistoryEvent.Kind.OUTAGE,
                    start, null, null, change.firstFailureMessage() != null ? change.firstFailureMessage() : check.message(),
                    check.message(), check.time(), List.of(), false, false, null);
            h.events.add(e);
            h.sortEvents();
            h.dirtyYears.add(yearOf(start));
            flushEvents(h);
            fire(e);
        } else if (change != null && change.from() == State.DOWN) {
            HistoryEvent open = h.openEvent();
            if (open != null) {
                closeEvent(h, open.closed(check.time(), false));
            }
        } else if (!check.success()) {
            HistoryEvent open = h.openEvent();
            if (open != null && !check.time().isBefore(open.lastFailureAt())) {
                h.replace(open.withLastFailure(check.time(), check.message()));
            }
        }
    }

    /**
     * Stops monitoring an endpoint (removed): its coverage period ends now, and an ongoing
     * event is closed at its last failure and marked {@code endedUnobserved}. Recording a
     * check for it later starts a new period.
     */
    public void stopMonitoring(String endpointId) {
        stopMonitoring(endpointId, false);
    }

    /**
     * Stops monitoring an endpoint: its coverage period ends now, and an ongoing event is
     * closed at its last failure, marked {@code endedByConfigChange} if {@code configChange}
     * (its check was replaced) or else {@code endedUnobserved}. Recording a check for it
     * later starts a new period.
     */
    public synchronized void stopMonitoring(String endpointId, boolean configChange) {
        EndpointHistory h = histories.get(endpointId);
        if (h == null) {
            return;
        }
        if (h.active) {
            extendCoverage(h, null, clock.instant());
            h.active = false;
        }
        HistoryEvent open = h.openEvent();
        if (open != null) {
            closeEvent(h, configChange
                    ? open.closedByConfigChange(open.lastFailureAt())
                    : open.closed(open.lastFailureAt(), true));
        }
        flush(h);
    }

    /**
     * Remembers target values (hosts, URLs, …) the endpoint checks, so they stay hidden from the
     * public in its history after its target changes: event messages recorded with an old
     * target are leak-filtered against every target the endpoint ever had. New values are
     * written to {@code targets.json} at once (on a write error, with the next heartbeat).
     * Values are kept until the endpoint's history is deleted.
     */
    public synchronized void rememberTargets(String endpointId, Collection<String> values) {
        EndpointHistory h = histories.computeIfAbsent(Objects.requireNonNull(endpointId, "endpointId"),
                EndpointHistory::new);
        for (String value : values) {
            if (value != null && !value.isEmpty() && h.targets.add(value)) {
                h.targetsDirty = true;
            }
        }
        flush(h);
    }

    /** Every target value {@linkplain #rememberTargets remembered} for the endpoint. */
    public synchronized Set<String> knownTargets(String endpointId) {
        EndpointHistory h = histories.get(endpointId);
        return h == null ? Set.of() : Set.copyOf(h.targets);
    }

    /**
     * Extends every active coverage period to now and writes everything not yet written.
     * Runs every {@link #HEARTBEAT_INTERVAL} once {@linkplain #startHeartbeat() started}.
     */
    public synchronized void heartbeat() {
        Instant now = clock.instant();
        for (EndpointHistory h : histories.values()) {
            if (h.active) {
                extendCoverage(h, null, now);
            }
            flush(h);
        }
    }

    /** Starts running {@link #heartbeat()} every minute on a background thread. */
    public synchronized void startHeartbeat() {
        if (heartbeatExecutor != null) {
            return;
        }
        heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon().name("history-heartbeat").factory());
        long millis = HEARTBEAT_INTERVAL.toMillis();
        heartbeatExecutor.scheduleAtFixedRate(() -> {
            try {
                heartbeat();
            } catch (RuntimeException e) {
                logger.log(Level.ERROR, "History heartbeat failed", e);
            }
        }, millis, millis, TimeUnit.MILLISECONDS);
    }

    /** Stops the heartbeat and writes a final one, so coverage runs up to now. */
    @Override
    public void close() {
        ScheduledExecutorService executor;
        synchronized (this) {
            executor = heartbeatExecutor;
            heartbeatExecutor = null;
        }
        if (executor != null) {
            executor.shutdownNow();
        }
        heartbeat();
    }

    // ---- editing ----

    /**
     * Sets an event's custom title ({@code null} resets it to the default) and writes its events
     * file before returning. On a write error the change is undone and the error thrown.
     *
     * @return the updated event, or empty when the endpoint has no event with that id
     * @throws IOException if the events file couldn't be written
     */
    public synchronized Optional<HistoryEvent> rename(String endpointId, String eventId, String title) throws IOException {
        return update(endpointId, eventId, e -> e.withTitle(title));
    }

    /**
     * Adds a note to an event and writes its events file before returning (see {@link #rename}).
     * The note gets a new id and {@code createdAt} = now.
     *
     * @return the new note, or empty when the endpoint has no event with that id
     * @throws IOException if the events file couldn't be written
     */
    public synchronized Optional<EventNote> addNote(String endpointId, String eventId, String author, String text,
                                                    EventNote.Visibility visibility) throws IOException {
        EventNote note = new EventNote(UUID.randomUUID().toString(), author, clock.instant(), null, text, visibility);
        return update(endpointId, eventId, e -> {
            List<EventNote> notes = new ArrayList<>(e.notes());
            notes.add(note);
            return e.withNotes(notes);
        }).map(e -> note);
    }

    /**
     * Changes a note's text and/or visibility ({@code null} keeps it) and writes the events file
     * before returning (see {@link #rename}). {@code editedAt} is set to now when the text changes.
     *
     * @return the updated note, or empty when there's no such event or note
     * @throws IOException if the events file couldn't be written
     */
    public synchronized Optional<EventNote> editNote(String endpointId, String eventId, String noteId, String text,
                                                     EventNote.Visibility visibility) throws IOException {
        EventNote[] result = new EventNote[1];
        Instant now = clock.instant();
        update(endpointId, eventId, e -> {
            List<EventNote> notes = new ArrayList<>(e.notes());
            for (int i = 0; i < notes.size(); i++) {
                EventNote n = notes.get(i);
                if (!n.id().equals(noteId)) continue;
                boolean textChanged = text != null && !text.equals(n.text());
                EventNote updated = new EventNote(n.id(), n.author(), n.createdAt(), textChanged ? now : n.editedAt(),
                        textChanged ? text : n.text(), visibility != null ? visibility : n.visibility());
                notes.set(i, updated);
                result[0] = updated;
                return e.withNotes(notes);
            }
            return null;
        });
        return Optional.ofNullable(result[0]);
    }

    /**
     * Deletes a note and writes the events file before returning (see {@link #rename}).
     *
     * @return whether there was such a note
     * @throws IOException if the events file couldn't be written
     */
    public synchronized boolean deleteNote(String endpointId, String eventId, String noteId) throws IOException {
        return update(endpointId, eventId, e -> {
            List<EventNote> notes = new ArrayList<>(e.notes());
            return notes.removeIf(n -> n.id().equals(noteId)) ? e.withNotes(notes) : null;
        }).isPresent();
    }

    /**
     * Applies {@code change} to an event (a {@code null} result means "nothing to change") and
     * writes the event's year file before returning. On a write error the change is undone and the
     * error thrown.
     */
    private Optional<HistoryEvent> update(String endpointId, String eventId, UnaryOperator<HistoryEvent> change)
            throws IOException {
        EndpointHistory h = histories.get(endpointId);
        HistoryEvent old = h == null ? null : h.find(eventId);
        if (old == null) {
            return Optional.empty();
        }
        HistoryEvent updated = change.apply(old);
        if (updated == null) {
            return Optional.empty();
        }
        h.replace(updated);
        int year = yearOf(updated.start());
        try {
            writeYear(h, year);
        } catch (IOException | RuntimeException e) {
            h.replace(old); // stays marked dirty, so the heartbeat writes the old state again
            throw e;
        }
        h.dirtyYears.remove(year);
        return Optional.of(updated);
    }

    // ---- reading ----

    /** Ids of every endpoint with stored or recorded history. */
    public synchronized Set<String> endpointIds() {
        return Set.copyOf(histories.keySet());
    }

    /** Coverage periods, oldest first; the last one may still be growing. */
    public synchronized List<CoveragePeriod> coverage(String endpointId) {
        EndpointHistory h = histories.get(endpointId);
        return h == null ? List.of() : List.copyOf(h.periods);
    }

    /** Whether the endpoint currently has an active coverage period. */
    public synchronized boolean monitored(String endpointId) {
        EndpointHistory h = histories.get(endpointId);
        return h != null && h.active;
    }

    /** Events, oldest first. */
    public synchronized List<HistoryEvent> events(String endpointId) {
        EndpointHistory h = histories.get(endpointId);
        return h == null ? List.of() : List.copyOf(h.events);
    }

    public synchronized Optional<HistoryEvent> event(String endpointId, String eventId) {
        EndpointHistory h = histories.get(endpointId);
        return h == null ? Optional.empty() : Optional.ofNullable(h.find(eventId));
    }

    /** The ongoing event, if the endpoint is down. */
    public synchronized Optional<HistoryEvent> openEvent(String endpointId) {
        EndpointHistory h = histories.get(endpointId);
        return h == null ? Optional.empty() : Optional.ofNullable(h.openEvent());
    }

    // ---- internals ----

    /**
     * Starts a period at {@code start} if none is active (never overlapping the previous
     * one), and extends the active period to {@code until}.
     */
    private void extendCoverage(EndpointHistory h, Instant start, Instant until) {
        if (!h.active) {
            if (start == null) {
                return;
            }
            CoveragePeriod last = h.periods.isEmpty() ? null : h.periods.getLast();
            Instant periodStart = last != null && start.isBefore(last.end()) ? last.end() : start;
            Instant periodEnd = until.isBefore(periodStart) ? periodStart : until;
            h.periods.add(new CoveragePeriod(periodStart, periodEnd));
            h.active = true;
            h.coverageDirty = true;
            return;
        }
        CoveragePeriod current = h.periods.getLast();
        if (until.isAfter(current.end())) {
            h.periods.set(h.periods.size() - 1, new CoveragePeriod(current.start(), until));
            h.coverageDirty = true;
        }
    }

    private void closeEvent(EndpointHistory h, HistoryEvent closed) {
        h.replace(closed);
        flushEvents(h);
        fire(closed);
    }

    private void fire(HistoryEvent e) {
        for (Consumer<HistoryEvent> listener : listeners) {
            try {
                listener.accept(e);
            } catch (RuntimeException ex) {
                logger.log(Level.ERROR, "History listener failed for " + e.endpointId(), ex);
            }
        }
    }

    private void flush(EndpointHistory h) {
        flushEvents(h);
        if (h.targetsDirty) {
            try {
                writer.write(h.dir().resolve(TARGETS_FILE),
                        HistoryCodec.encodeTargets(h.targets).getBytes(StandardCharsets.UTF_8));
                h.targetsDirty = false;
            } catch (IOException | RuntimeException e) {
                logger.log(Level.ERROR, "Couldn't write targets of " + h.id + "; retrying with the next heartbeat", e);
            }
        }
        if (h.coverageDirty) {
            try {
                writer.write(h.dir().resolve(COVERAGE_FILE),
                        HistoryCodec.encodeCoverage(h.periods).getBytes(StandardCharsets.UTF_8));
                h.coverageDirty = false;
            } catch (IOException | RuntimeException e) {
                logger.log(Level.ERROR, "Couldn't write coverage of " + h.id + "; retrying with the next heartbeat", e);
            }
        }
    }

    private void flushEvents(EndpointHistory h) {
        for (int year : Set.copyOf(h.dirtyYears)) {
            try {
                writeYear(h, year);
                h.dirtyYears.remove(year);
            } catch (IOException | RuntimeException e) {
                logger.log(Level.ERROR, "Couldn't write " + year + " events of " + h.id
                        + "; retrying with the next heartbeat", e);
            }
        }
    }

    private void writeYear(EndpointHistory h, int year) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (HistoryEvent e : h.events) {
            if (yearOf(e.start()) == year) {
                sb.append(HistoryCodec.encodeEvent(e)).append('\n');
            }
        }
        writer.write(h.dir().resolve(EVENTS_DIR).resolve(year + ".jsonl"), sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static int yearOf(Instant instant) {
        return instant.atZone(ZoneOffset.UTC).getYear();
    }

    private final class EndpointHistory {
        final String id;
        final List<CoveragePeriod> periods = new ArrayList<>();
        final List<HistoryEvent> events = new ArrayList<>();
        final Set<Integer> dirtyYears = new TreeSet<>();
        final Set<String> targets = new LinkedHashSet<>();
        boolean active;
        boolean coverageDirty;
        boolean targetsDirty;

        EndpointHistory(String id) {
            this.id = id;
        }

        Path dir() {
            return endpointsDir.resolve(id);
        }

        void sortEvents() {
            events.sort(Comparator.comparing(HistoryEvent::start).thenComparing(HistoryEvent::id));
        }

        HistoryEvent openEvent() {
            for (int i = events.size() - 1; i >= 0; i--) {
                if (events.get(i).ongoing()) {
                    return events.get(i);
                }
            }
            return null;
        }

        HistoryEvent find(String eventId) {
            for (HistoryEvent e : events) {
                if (e.id().equals(eventId)) {
                    return e;
                }
            }
            return null;
        }

        /** Replaces the event with the same id and marks its year for writing. */
        void replace(HistoryEvent updated) {
            for (int i = 0; i < events.size(); i++) {
                if (events.get(i).id().equals(updated.id())) {
                    events.set(i, updated);
                    dirtyYears.add(yearOf(updated.start()));
                    return;
                }
            }
            throw new IllegalStateException("unknown event " + updated.id());
        }
    }
}
