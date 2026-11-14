package io.siggi.healthchecker.maintenance;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import io.siggi.healthchecker.audit.AuditLog;
import io.siggi.healthchecker.config.MaintenanceSettings;
import io.siggi.healthchecker.history.AtomicFiles;
import io.siggi.healthchecker.history.EventNote;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Scheduled maintenance windows, in {@code {dataDir}/maintenance.json}.
 *
 * <p>File format:
 * <pre>{@code
 * { "version": 7,
 *   "windows": [ { "id": "…", "version": 2, "title": "Weekly reboot", "description": "…",
 *                  "start": "2026-11-20T02:00:00Z", "end": "2026-11-20T03:00:00Z",
 *                  "endpoints": ["website"], "categories": ["minecraft"], "notifyChannels": ["discord"],
 *                  "notes": [ … ], "createdBy": "siggi", "createdByName": "Siggi",
 *                  "createdAt": "…", "updatedAt": "…", "cancelledAt": "…",
 *                  "recurrence": { "frequency": "monthly", "week": 1, "weekday": "sunday", "until": "2027-06-30" },
 *                  "exceptions": [ { "date": "2026-12-06", "skip": true },
 *                                  { "date": "2027-01-03", "title": "…", "start": "…", "end": "…" } ] },
 *                { "id": "…_2026-11-01", "seriesId": "…", "occurrence": "2026-11-01", … } ] }
 * }</pre>
 * {@code recurrence} is {@code {"frequency": "daily"}}, {@code {"frequency": "weekly", "weekdays": ["monday",
 * …]}}, or monthly with {@code "dayOfMonth"} or with {@code "week"} (1–5, -1 = last) and {@code "weekday"}; each
 * with an optional {@code "until"} date. {@code recurrence} and {@code exceptions} appear on series only,
 * {@code seriesId} and {@code occurrence} on stored occurrences only.
 * The file's {@code version} counts writes; each window has its own {@code version}, which edits and
 * cancelling must name ({@link VersionConflictException}, for {@code 409}, otherwise), so an edit of one
 * window doesn't make forms for other windows stale. Every write is atomic ({@link AtomicFiles}) and
 * recorded in the audit log after it succeeded, under this store's lock.
 *
 * <p>Validation ({@link InvalidWindowException}, errors as {@code key: message}):
 * <ul>
 *   <li>{@code title}: trimmed, required, at most {@value #MAX_TITLE_LENGTH} characters, no control characters;
 *       {@code description}: optional, at most {@value #MAX_TEXT_LENGTH} characters.</li>
 *   <li>{@code end} after {@code start}, and at most {@code maintenance.maxLengthDays} days later.</li>
 *   <li>A new window must start in the future. On an edit, a {@code start} or {@code end} that has passed is
 *       fixed, and a changed one must be in the future.</li>
 *   <li>At least one endpoint or category; ids and {@code notifyChannels} must exist (ids the window already
 *       had may have been deleted since and are kept). Once a window has ended, its endpoints and categories
 *       are fixed too.</li>
 *   <li>Cancelled windows can't be edited; only windows that haven't started can be cancelled.</li>
 *   <li>A window in progress can be ended early ({@link #endNow}, its end becomes now) or extended
 *       ({@link #extend}, to a later end within the length limit); audited as {@code maintenance.end} and
 *       {@code maintenance.extend}.</li>
 * </ul>
 *
 * <p><b>Series.</b> A window with a {@link Recurrence} is a series. Its {@code start} and {@code end} are the
 * first possible occurrence; the rule picks the dates (in the configured timezone, from that first date on, up to
 * {@code until}), and every occurrence starts at the same local time of day and lasts the same local time
 * ({@link #regularTimes}), so a 02:00–03:00 reboot stays at 02:00–03:00 across DST changes (a local time that
 * doesn't exist that day moves forward by the gap). An occurrence is identified by the series id and its date
 * ({@code {seriesId}_{date}}); single occurrences can be skipped or given their own title, description and
 * times ({@link OccurrenceChange}) without changing the series. A series is never in progress itself and can
 * be cancelled at any time (which stops its future occurrences); its times can only be changed to a first
 * occurrence in the future, and it can't be turned into a one-off window or back.
 *
 * <p>When an occurrence starts, it's <i>materialised</i>: stored as a window of its own (id
 * {@code {seriesId}_{date}}, with {@code seriesId} and {@code occurrence}) with the series' fields at that moment,
 * so later edits of the series don't rewrite history. {@link #materialise()} does that (the scheduler calls it),
 * and so does every write, first. So when a series is written, everything of it that has started is stored, and
 * the series yields only occurrences that start after its {@code updatedAt}: a rule or time change never brings
 * back occurrences in the past. {@link #occurrences} lists stored windows and the not yet materialised
 * occurrences together.
 */
public final class MaintenanceStore {
    public static final String FILE_NAME = "maintenance.json";
    /** Longest title, in characters (code points). */
    public static final int MAX_TITLE_LENGTH = 200;
    /** Longest description or note, in characters (code points). */
    public static final int MAX_TEXT_LENGTH = 10_000;

    private static final Set<String> ROOT_KEYS = Set.of("version", "windows");
    private static final Set<String> WINDOW_KEYS = Set.of("id", "version", "title", "description", "start", "end",
            "endpoints", "categories", "notifyChannels", "notes", "createdBy", "createdByName", "createdAt",
            "updatedAt", "cancelledAt", "recurrence", "exceptions", "seriesId", "occurrence");
    private static final Set<String> RECURRENCE_KEYS = Set.of("frequency", "weekdays", "dayOfMonth", "week", "weekday",
            "until");
    private static final Set<String> EXCEPTION_KEYS = Set.of("date", "skip", "title", "description", "start", "end");
    private static final Set<String> NOTE_KEYS = Set.of("id", "author", "createdAt", "editedAt", "text", "visibility");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** Who makes a change: a user id and display name (used as note author and in the audit log). */
    public record Actor(String id, String name) {
        public Actor {
            Objects.requireNonNull(name, "name");
        }
    }

    /** What exists, to check the ids a window refers to. */
    public interface References {
        boolean endpointExists(String id);

        boolean categoryExists(String id);

        boolean channelExists(String name);
    }

    /**
     * The editable fields of a window, as entered.
     *
     * @param description optional, {@code null} or blank for none
     * @param start       may be {@code null} (reported as missing)
     * @param end         may be {@code null} (reported as missing)
     * @param recurrence  the rule for a series, {@code null} for a one-off window
     */
    public record Draft(String title, String description, Instant start, Instant end, List<String> endpoints,
                        List<String> categories, List<String> notifyChannels, Recurrence recurrence) {
        public Draft {
            endpoints = endpoints == null ? List.of() : List.copyOf(endpoints);
            categories = categories == null ? List.of() : List.copyOf(categories);
            notifyChannels = notifyChannels == null ? List.of() : List.copyOf(notifyChannels);
        }

        /** A one-off window. */
        public Draft(String title, String description, Instant start, Instant end, List<String> endpoints,
                     List<String> categories, List<String> notifyChannels) {
            this(title, description, start, end, endpoints, categories, notifyChannels, null);
        }

        /** The editable fields of an existing window. */
        public static Draft of(MaintenanceWindow w) {
            return new Draft(w.title(), w.description(), w.start(), w.end(), w.endpoints(), w.categories(),
                    w.notifyChannels(), w.recurrence());
        }
    }

    /**
     * The fields of one occurrence of a series that can be edited, as entered.
     *
     * @param description optional, {@code null} or blank for none
     * @param start       may be {@code null} (reported as missing)
     * @param end         may be {@code null} (reported as missing)
     */
    public record OccurrenceDraft(String title, String description, Instant start, Instant end) {
    }

    /** A write based on a window version other than the current one (HTTP 409). */
    public static final class VersionConflictException extends Exception {
        private final int currentVersion;

        VersionConflictException(int currentVersion) {
            super("the maintenance window was changed (now version " + currentVersion + ")");
            this.currentVersion = currentVersion;
        }

        public int currentVersion() {
            return currentVersion;
        }
    }

    /** A write rejected because of invalid input or the window's state; nothing was written. */
    public static final class InvalidWindowException extends Exception {
        private final List<String> errors;

        InvalidWindowException(List<String> errors) {
            super(String.join("; ", errors));
            this.errors = List.copyOf(errors);
        }

        /** Each error, prefixed with the field it's about where there is one ({@code "key: …"}). */
        public List<String> errors() {
            return errors;
        }
    }

    private record State(int version, List<MaintenanceWindow> windows) {
        State {
            windows = List.copyOf(windows);
        }
    }

    private final Path file;
    private final MaintenanceSettings settings;
    private final References references;
    private final AuditLog audit;
    private final Clock clock;
    private final ZoneId zone;
    private volatile State state = new State(0, List.of());
    private final List<Runnable> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    /**
     * @param zone the configured timezone, which series are expanded in
     */
    public MaintenanceStore(Path dataDir, MaintenanceSettings settings, References references, AuditLog audit,
                            Clock clock, ZoneId zone) {
        this.file = dataDir.resolve(FILE_NAME);
        this.settings = Objects.requireNonNull(settings, "settings");
        this.references = Objects.requireNonNull(references, "references");
        this.audit = audit == null ? AuditLog.disabled() : audit;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.zone = Objects.requireNonNull(zone, "zone");
    }

    /** With the clock's zone as the configured timezone. */
    public MaintenanceStore(Path dataDir, MaintenanceSettings settings, References references, AuditLog audit,
                            Clock clock) {
        this(dataDir, settings, references, audit, clock, clock.getZone());
    }

    public ZoneId zone() {
        return zone;
    }

    public Path file() {
        return file;
    }

    /**
     * Reads the file (missing = no windows, version 0), replacing what was loaded before. Leftover temp
     * files of interrupted writes are removed.
     *
     * @throws IOException if it can't be read or is malformed (the message names the file)
     */
    public synchronized void load() throws IOException {
        deleteLeftoverTempFiles();
        String json;
        try {
            json = Files.readString(file, StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            state = new State(0, List.of());
            return;
        }
        try {
            state = decode(json);
        } catch (FormatException e) {
            throw new IOException(file + ": " + e.getMessage());
        }
    }

    private void deleteLeftoverTempFiles() throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        if (!Files.isDirectory(dir)) return;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "." + FILE_NAME + ".*")) {
            for (Path temp : files) {
                if (AtomicFiles.isTempFile(temp) && Files.isRegularFile(temp)) Files.deleteIfExists(temp);
            }
        }
    }

    /** The file's version (number of writes), 0 without a file. */
    public int version() {
        return state.version();
    }

    /** Every stored window (one-offs, series and materialised occurrences), in creation order. */
    public List<MaintenanceWindow> windows() {
        return state.windows();
    }

    public Optional<MaintenanceWindow> window(String id) {
        return state.windows().stream().filter(w -> w.id().equals(id)).findFirst();
    }

    // ---- windows ----

    /** Creates a window (version 1). */
    public synchronized MaintenanceWindow create(Draft draft, Actor actor) throws InvalidWindowException, IOException {
        Instant now = clock.instant();
        List<String> errors = new ArrayList<>();
        Clean clean = clean(draft, null, now, errors);
        if (!errors.isEmpty()) throw new InvalidWindowException(errors);
        Set<String> used = new HashSet<>();
        state.windows().forEach(w -> used.add(w.id()));
        String id;
        do {
            id = UUID.randomUUID().toString();
        } while (used.contains(id));
        MaintenanceWindow created = new MaintenanceWindow(id, 1, clean.title, clean.description, clean.start,
                clean.end, clean.endpoints, clean.categories, clean.channels, List.of(), actor.id(), actor.name(),
                now, now, null, clean.recurrence, List.of(), null, null);
        List<MaintenanceWindow> windows = current(now);
        windows.add(created);
        write(windows);
        record(actor, "maintenance.create", id, null, auditValue(created));
        return created;
    }

    /**
     * Changes a window's fields.
     *
     * @return the changed window, or empty if there's no window {@code id}
     */
    public synchronized Optional<MaintenanceWindow> update(String id, int expectedVersion, Draft draft, Actor actor)
            throws InvalidWindowException, VersionConflictException, IOException {
        MaintenanceWindow old = window(id).orElse(null);
        if (old == null) return Optional.empty();
        if (old.version() != expectedVersion) throw new VersionConflictException(old.version());
        Instant now = clock.instant();
        if (old.cancelledAt() != null) throw new InvalidWindowException(List.of("the window was cancelled"));
        List<String> errors = new ArrayList<>();
        Clean clean = clean(draft, old, now, errors);
        if (!errors.isEmpty()) throw new InvalidWindowException(errors);
        MaintenanceWindow updated = new MaintenanceWindow(id, old.version() + 1, clean.title, clean.description,
                clean.start, clean.end, clean.endpoints, clean.categories, clean.channels, old.notes(),
                old.createdBy(), old.createdByName(), old.createdAt(), now, null, clean.recurrence,
                old.exceptions(), old.seriesId(), old.occurrence());
        replace(updated, now);
        record(actor, "maintenance.edit", id, auditValue(old), auditValue(updated));
        return Optional.of(updated);
    }

    /**
     * Cancels a window that hasn't started yet (one in progress is ended early instead), or a series (its
     * occurrences that have started stay).
     *
     * @return the cancelled window, or empty if there's no window {@code id}
     */
    public synchronized Optional<MaintenanceWindow> cancel(String id, int expectedVersion, Actor actor)
            throws InvalidWindowException, VersionConflictException, IOException {
        MaintenanceWindow old = window(id).orElse(null);
        if (old == null) return Optional.empty();
        if (old.version() != expectedVersion) throw new VersionConflictException(old.version());
        Instant now = clock.instant();
        switch (old.status(now)) {
            case CANCELLED -> throw new InvalidWindowException(List.of("the window was already cancelled"));
            case IN_PROGRESS -> throw new InvalidWindowException(List.of("the window has started; end it instead"));
            case COMPLETED -> throw new InvalidWindowException(List.of("the window has ended"));
            case SCHEDULED -> {
            }
        }
        MaintenanceWindow cancelled = changed(old, old.notes(), old.exceptions(), now, now);
        replace(cancelled, now);
        record(actor, "maintenance.cancel", id, auditValue(old), auditValue(cancelled));
        return Optional.of(cancelled);
    }

    /**
     * "End now": ends a window in progress at the current time.
     *
     * @return the ended window, or empty if there's no window {@code id}
     */
    public synchronized Optional<MaintenanceWindow> endNow(String id, int expectedVersion, Actor actor)
            throws InvalidWindowException, VersionConflictException, IOException {
        Instant now = clock.instant();
        List<MaintenanceWindow> windows = current(now);
        MaintenanceWindow old = find(windows, id);
        if (old == null) return Optional.empty();
        if (old.version() != expectedVersion) throw new VersionConflictException(old.version());
        checkInProgress(old, now);
        MaintenanceWindow ended = withEnd(old, now, now);
        windows.replaceAll(w -> w.id().equals(id) ? ended : w);
        write(windows);
        record(actor, "maintenance.end", id, auditValue(old), auditValue(ended));
        return Optional.of(ended);
    }

    /**
     * "Extend": moves the end of a window in progress later, to {@code end} (at most
     * {@code maintenance.maxLengthDays} after its start).
     *
     * @return the extended window, or empty if there's no window {@code id}
     */
    public synchronized Optional<MaintenanceWindow> extend(String id, int expectedVersion, Instant end, Actor actor)
            throws InvalidWindowException, VersionConflictException, IOException {
        Instant now = clock.instant();
        List<MaintenanceWindow> windows = current(now);
        MaintenanceWindow old = find(windows, id);
        if (old == null) return Optional.empty();
        if (old.version() != expectedVersion) throw new VersionConflictException(old.version());
        checkInProgress(old, now);
        if (end == null) {
            throw new InvalidWindowException(List.of("end: required"));
        }
        if (!end.isAfter(old.end())) {
            throw new InvalidWindowException(List.of("end: must be later than the current end"));
        }
        if (Duration.between(old.start(), end).compareTo(Duration.ofDays(settings.maxLengthDays())) > 0) {
            throw new InvalidWindowException(List.of("end: a window can be at most "
                    + days(settings.maxLengthDays()) + " long"));
        }
        MaintenanceWindow extended = withEnd(old, end, now);
        windows.replaceAll(w -> w.id().equals(id) ? extended : w);
        write(windows);
        record(actor, "maintenance.extend", id, auditValue(old), auditValue(extended));
        return Optional.of(extended);
    }

    private static MaintenanceWindow find(List<MaintenanceWindow> windows, String id) {
        for (MaintenanceWindow w : windows) {
            if (w.id().equals(id)) return w;
        }
        return null;
    }

    private static void checkInProgress(MaintenanceWindow w, Instant now) throws InvalidWindowException {
        if (w.isSeries()) throw new InvalidWindowException(List.of("a series isn't in progress itself"));
        switch (w.status(now)) {
            case CANCELLED -> throw new InvalidWindowException(List.of("the window was cancelled"));
            case SCHEDULED -> throw new InvalidWindowException(List.of("the window hasn't started yet"));
            case COMPLETED -> throw new InvalidWindowException(List.of("the window has ended"));
            case IN_PROGRESS -> {
            }
        }
    }

    private static MaintenanceWindow withEnd(MaintenanceWindow w, Instant end, Instant updatedAt) {
        return new MaintenanceWindow(w.id(), w.version() + 1, w.title(), w.description(), w.start(), end,
                w.endpoints(), w.categories(), w.notifyChannels(), w.notes(), w.createdBy(), w.createdByName(),
                w.createdAt(), updatedAt, w.cancelledAt(), w.recurrence(), w.exceptions(), w.seriesId(),
                w.occurrence());
    }

    // ---- notes ----

    /**
     * Adds a note (allowed in every state, like notes on events).
     *
     * @return the note, or empty if there's no window {@code windowId}
     */
    public synchronized Optional<EventNote> addNote(String windowId, String text, EventNote.Visibility visibility,
                                                    Actor actor) throws InvalidWindowException, IOException {
        MaintenanceWindow old = window(windowId).orElse(null);
        if (old == null) return Optional.empty();
        String clean = noteText(text);
        Instant now = clock.instant();
        Set<String> used = new HashSet<>();
        old.notes().forEach(n -> used.add(n.id()));
        String noteId;
        do {
            noteId = UUID.randomUUID().toString();
        } while (used.contains(noteId));
        EventNote note = new EventNote(noteId, actor.name(), now, null, clean, Objects.requireNonNull(visibility));
        List<EventNote> notes = new ArrayList<>(old.notes());
        notes.add(note);
        replace(changed(old, notes, old.exceptions(), now, old.cancelledAt()), now);
        record(actor, "maintenance.note.add", windowId + "/" + noteId, null, noteValue(note));
        return Optional.of(note);
    }

    /**
     * Changes a note's text and visibility.
     *
     * @return the changed note, or empty if there's no such window or note
     */
    public synchronized Optional<EventNote> editNote(String windowId, String noteId, String text,
                                                     EventNote.Visibility visibility, Actor actor)
            throws InvalidWindowException, IOException {
        MaintenanceWindow old = window(windowId).orElse(null);
        EventNote before = old == null ? null : old.note(noteId).orElse(null);
        if (before == null) return Optional.empty();
        String clean = noteText(text);
        Instant now = clock.instant();
        EventNote after = new EventNote(noteId, before.author(), before.createdAt(), now, clean,
                Objects.requireNonNull(visibility));
        List<EventNote> notes = new ArrayList<>(old.notes());
        notes.replaceAll(n -> n.id().equals(noteId) ? after : n);
        replace(changed(old, notes, old.exceptions(), now, old.cancelledAt()), now);
        record(actor, "maintenance.note.edit", windowId + "/" + noteId, noteValue(before), noteValue(after));
        return Optional.of(after);
    }

    /** Deletes a note; returns whether it existed. */
    public synchronized boolean deleteNote(String windowId, String noteId, Actor actor) throws IOException {
        MaintenanceWindow old = window(windowId).orElse(null);
        EventNote before = old == null ? null : old.note(noteId).orElse(null);
        if (before == null) return false;
        List<EventNote> notes = new ArrayList<>(old.notes());
        notes.removeIf(n -> n.id().equals(noteId));
        Instant now = clock.instant();
        replace(changed(old, notes, old.exceptions(), now, old.cancelledAt()), now);
        record(actor, "maintenance.note.delete", windowId + "/" + noteId, noteValue(before), null);
        return true;
    }

    // ---- series ----

    /**
     * Skips one occurrence of a series. It must not have started.
     *
     * @return the changed series, or empty if there's no window {@code seriesId}
     */
    public synchronized Optional<MaintenanceWindow> skipOccurrence(String seriesId, int expectedVersion,
                                                                   LocalDate date, Actor actor)
            throws InvalidWindowException, VersionConflictException, IOException {
        return changeOccurrence(seriesId, expectedVersion, date, OccurrenceChange.skip(date), null,
                "maintenance.occurrence.skip", actor);
    }

    /**
     * Gives one occurrence of a series (skipped or not) its own title, description and times. It must not
     * have started; the new times follow the rules for a new window.
     *
     * @return the changed series, or empty if there's no window {@code seriesId}
     */
    public synchronized Optional<MaintenanceWindow> editOccurrence(String seriesId, int expectedVersion,
                                                                   LocalDate date, OccurrenceDraft draft,
                                                                   Actor actor)
            throws InvalidWindowException, VersionConflictException, IOException {
        return changeOccurrence(seriesId, expectedVersion, date, null, Objects.requireNonNull(draft, "draft"),
                "maintenance.occurrence.edit", actor);
    }

    /**
     * Undoes a skip or edit of one occurrence, so it follows the series again. It must not have started.
     *
     * @return the series (changed, or as it was when the occurrence had no change), or empty if there's no
     * window {@code seriesId}
     */
    public synchronized Optional<MaintenanceWindow> restoreOccurrence(String seriesId, int expectedVersion,
                                                                      LocalDate date, Actor actor)
            throws InvalidWindowException, VersionConflictException, IOException {
        return changeOccurrence(seriesId, expectedVersion, date, null, null, "maintenance.occurrence.restore",
                actor);
    }

    /** Applies {@code skip}, or an edit from {@code draft}, or (both {@code null}) a restore. */
    private Optional<MaintenanceWindow> changeOccurrence(String seriesId, int expectedVersion, LocalDate date,
                                                         OccurrenceChange skip, OccurrenceDraft draft,
                                                         String action, Actor actor)
            throws InvalidWindowException, VersionConflictException, IOException {
        Objects.requireNonNull(date, "date");
        MaintenanceWindow old = window(seriesId).orElse(null);
        if (old == null) return Optional.empty();
        if (old.version() != expectedVersion) throw new VersionConflictException(old.version());
        if (!old.isSeries()) throw new InvalidWindowException(List.of("the window isn't a recurring series"));
        if (old.cancelledAt() != null) throw new InvalidWindowException(List.of("the series was cancelled"));
        Instant now = clock.instant();
        List<MaintenanceWindow> windows = current(now);
        if (!isOccurrenceDate(old, date)) {
            throw new InvalidWindowException(List.of("date: the series has no occurrence on " + date));
        }
        String occurrenceId = occurrenceId(seriesId, date);
        OccurrenceChange before = old.exception(date).orElse(null);
        Instant plannedStart = before != null && !before.skipped() ? before.start() : regularTimes(old, date)[0];
        if (windows.stream().anyMatch(w -> w.id().equals(occurrenceId)) || !plannedStart.isAfter(now)) {
            throw new InvalidWindowException(List.of("date: that occurrence has already started"));
        }
        OccurrenceChange after = skip;
        if (draft != null) {
            List<String> errors = new ArrayList<>();
            String title = cleanTitle(draft.title(), errors);
            String description = cleanDescription(draft.description(), errors);
            checkNewTimes(draft.start(), draft.end(), now, errors);
            if (!errors.isEmpty()) throw new InvalidWindowException(errors);
            after = OccurrenceChange.edit(date, title, description, draft.start(), draft.end());
        }
        if (before == null && after == null) return Optional.of(old);
        List<OccurrenceChange> exceptions = new ArrayList<>(old.exceptions());
        exceptions.removeIf(e -> e.date().equals(date));
        if (after != null) exceptions.add(after);
        exceptions.sort(Comparator.comparing(OccurrenceChange::date));
        MaintenanceWindow updated = changed(old, old.notes(), exceptions, now, null);
        windows.replaceAll(w -> w.id().equals(seriesId) ? updated : w);
        write(windows);
        record(actor, action, seriesId + "/" + date, before == null ? null : exceptionValue(before),
                after == null ? null : exceptionValue(after));
        return Optional.of(updated);
    }

    /**
     * Stores every occurrence of a series that has started by now as a window of its own (see the class
     * description). The scheduler calls this when occurrences start; every write does it too.
     *
     * @return the newly stored occurrences, by start
     */
    public synchronized List<MaintenanceWindow> materialise() throws IOException {
        Instant now = clock.instant();
        List<MaintenanceWindow> windows = new ArrayList<>(state.windows());
        List<MaintenanceWindow> created = materialiseDue(windows, now);
        if (!created.isEmpty()) write(windows);
        return created;
    }

    /**
     * Every window and occurrence overlapping {@code [from, to)}, by start: stored one-off windows and
     * occurrences (any status, including cancelled), and the occurrences of series that aren't stored yet
     * (these have the series' {@code version}, {@code createdAt} and {@code updatedAt}). Series themselves and
     * skipped occurrences aren't included.
     */
    public List<MaintenanceWindow> occurrences(Instant from, Instant to) {
        List<MaintenanceWindow> windows = state.windows();
        Set<String> ids = new HashSet<>();
        windows.forEach(w -> ids.add(w.id()));
        List<MaintenanceWindow> out = new ArrayList<>();
        for (MaintenanceWindow w : windows) {
            if (w.isSeries()) {
                if (w.cancelledAt() != null) continue;
                for (Planned p : planned(w, from, to, ids)) {
                    out.add(occurrenceWindow(w, p, w.version(), w.createdAt(), w.updatedAt()));
                }
            } else if (w.start().isBefore(to) && w.end().isAfter(from)) {
                out.add(w);
            }
        }
        out.sort(Comparator.comparing(MaintenanceWindow::start).thenComparing(MaintenanceWindow::id));
        return out;
    }

    /**
     * A one-off window or an occurrence by id: stored, or an occurrence {@code {seriesId}_{date}} of a series
     * that isn't stored yet. Empty for a series id, a skipped occurrence, an occurrence of a cancelled series,
     * or one that would have started before the series' last change (it never happened).
     */
    public Optional<MaintenanceWindow> occurrence(String id) {
        Optional<MaintenanceWindow> stored = window(id);
        if (stored.isPresent()) return stored.filter(w -> !w.isSeries());
        int sep = id.lastIndexOf('_');
        if (sep < 0) return Optional.empty();
        LocalDate date;
        try {
            date = LocalDate.parse(id.substring(sep + 1));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
        MaintenanceWindow series = window(id.substring(0, sep)).orElse(null);
        if (series == null || !series.isSeries() || series.cancelledAt() != null) return Optional.empty();
        if (!isOccurrenceDate(series, date)) return Optional.empty();
        Planned p = plannedOn(series, date);
        if (p == null || !p.start().isAfter(series.updatedAt())) return Optional.empty();
        return Optional.of(occurrenceWindow(series, p, series.version(), series.createdAt(), series.updatedAt()));
    }

    /** The id of the occurrence of series {@code seriesId} on {@code date}. */
    public static String occurrenceId(String seriesId, LocalDate date) {
        return seriesId + "_" + date;
    }

    /** One occurrence of a series as planned: its date and times, and its change if it was edited. */
    private record Planned(LocalDate date, Instant start, Instant end, OccurrenceChange change) {
    }

    /** Whether the rule of {@code series} yields {@code date} (on or after the first occurrence's date). */
    private boolean isOccurrenceDate(MaintenanceWindow series, LocalDate date) {
        return !date.isBefore(series.start().atZone(zone).toLocalDate()) && series.recurrence().matches(date);
    }

    /**
     * The start and end of the occurrence of {@code series} on {@code date} as the rule gives it: on the first
     * date exactly the series' times; on other dates the same local start time and the same local length (the
     * absolute length if that would end before it starts, around a DST change).
     */
    Instant[] regularTimes(MaintenanceWindow series, LocalDate date) {
        LocalDateTime localStart = series.start().atZone(zone).toLocalDateTime();
        LocalDateTime localEnd = series.end().atZone(zone).toLocalDateTime();
        if (date.equals(localStart.toLocalDate())) return new Instant[]{series.start(), series.end()};
        LocalDateTime start = date.atTime(localStart.toLocalTime());
        Instant s = ZonedDateTime.of(start, zone).toInstant();
        Instant e = ZonedDateTime.of(start.plus(Duration.between(localStart, localEnd)), zone).toInstant();
        if (!e.isAfter(s)) e = s.plus(Duration.between(series.start(), series.end()));
        return new Instant[]{s, e};
    }

    /** The occurrence on an occurrence date, or {@code null} if it's skipped. */
    private Planned plannedOn(MaintenanceWindow series, LocalDate date) {
        OccurrenceChange change = series.exception(date).orElse(null);
        if (change != null && change.skipped()) return null;
        if (change != null) return new Planned(date, change.start(), change.end(), change);
        Instant[] times = regularTimes(series, date);
        return new Planned(date, times[0], times[1], null);
    }

    /**
     * The occurrences of {@code series} overlapping {@code [from, to)} that aren't stored (by id in
     * {@code stored}) or skipped and that start after the series' {@code updatedAt}, by date.
     */
    private List<Planned> planned(MaintenanceWindow series, Instant from, Instant to, Set<String> stored) {
        Recurrence rule = series.recurrence();
        LocalDate first = series.start().atZone(zone).toLocalDate();
        long lengthDays = Duration.between(series.start(), series.end()).toDays();
        // An occurrence starting after updatedAt and ending after `from` falls on one of these dates (with a
        // day to spare for offset changes); edited occurrences can be anywhere, so they're added separately.
        LocalDate lo = max(first, max(series.updatedAt().atZone(zone).toLocalDate().minusDays(1),
                from.atZone(zone).toLocalDate().minusDays(lengthDays + 2)));
        LocalDate hi = to.atZone(zone).toLocalDate().plusDays(1);
        TreeSet<LocalDate> dates = new TreeSet<>();
        for (LocalDate d = rule.next(lo); d != null && !d.isAfter(hi); d = rule.next(d.plusDays(1))) dates.add(d);
        for (OccurrenceChange c : series.exceptions()) {
            if (!c.skipped() && isOccurrenceDate(series, c.date())) dates.add(c.date());
        }
        List<Planned> out = new ArrayList<>();
        for (LocalDate date : dates) {
            if (stored.contains(occurrenceId(series.id(), date))) continue;
            Planned p = plannedOn(series, date);
            if (p == null || !p.start().isAfter(series.updatedAt())) continue;
            if (p.start().isBefore(to) && p.end().isAfter(from)) out.add(p);
        }
        return out;
    }

    private static LocalDate max(LocalDate a, LocalDate b) {
        return a.isAfter(b) ? a : b;
    }

    private static MaintenanceWindow occurrenceWindow(MaintenanceWindow series, Planned p, int version,
                                                      Instant createdAt, Instant updatedAt) {
        String title = p.change() != null ? p.change().title() : series.title();
        String description = p.change() != null ? p.change().description() : series.description();
        return new MaintenanceWindow(occurrenceId(series.id(), p.date()), version, title, description, p.start(),
                p.end(), series.endpoints(), series.categories(), series.notifyChannels(), List.of(),
                series.createdBy(), series.createdByName(), createdAt, updatedAt, null, null, List.of(),
                series.id(), p.date());
    }

    /** Adds every occurrence that has started by {@code now} and isn't stored yet to {@code windows}. */
    private List<MaintenanceWindow> materialiseDue(List<MaintenanceWindow> windows, Instant now) {
        Set<String> ids = new HashSet<>();
        windows.forEach(w -> ids.add(w.id()));
        List<MaintenanceWindow> created = new ArrayList<>();
        for (MaintenanceWindow w : List.copyOf(windows)) {
            if (!w.isSeries() || w.cancelledAt() != null) continue;
            for (Planned p : planned(w, w.updatedAt(), now.plusNanos(1), ids)) {
                MaintenanceWindow o = occurrenceWindow(w, p, 1, now, now);
                created.add(o);
                ids.add(o.id());
            }
        }
        created.sort(Comparator.comparing(MaintenanceWindow::start).thenComparing(MaintenanceWindow::id));
        windows.addAll(created);
        return created;
    }

    /** The stored windows plus the occurrences due at {@code now}, to base a write on. */
    private List<MaintenanceWindow> current(Instant now) {
        List<MaintenanceWindow> windows = new ArrayList<>(state.windows());
        materialiseDue(windows, now);
        return windows;
    }

    // ---- validation ----

    private record Clean(String title, String description, Instant start, Instant end, List<String> endpoints,
                         List<String> categories, List<String> channels, Recurrence recurrence) {
    }

    /** Validates a draft for a new window ({@code old == null}) or an edit of {@code old}. */
    private Clean clean(Draft draft, MaintenanceWindow old, Instant now, List<String> errors) {
        String title = cleanTitle(draft.title(), errors);
        String description = cleanDescription(draft.description(), errors);
        Recurrence recurrence = draft.recurrence();
        boolean series = old != null ? old.isSeries() : recurrence != null;
        if (old != null && old.isSeries() != (recurrence != null)) {
            errors.add(old.isSeries()
                    ? "recurrence: a series can't be turned into a single window; set an end date or cancel it"
                    : "recurrence: a window can't be turned into a series after it was created");
        }

        Instant start = draft.start();
        Instant end = draft.end();
        // A series never runs itself (its occurrences are stored when they start), so its times may change
        // as long as the new first occurrence is in the future.
        boolean started = !series && old != null && !now.isBefore(old.start());
        boolean ended = !series && old != null && !now.isBefore(old.end());
        boolean timesChanged = old == null || !Objects.equals(start, old.start()) || !Objects.equals(end, old.end());
        if (start == null) {
            errors.add("start: required");
        } else if (started && !start.equals(old.start())) {
            errors.add("start: can't be changed after the window started");
        } else if (!started && (!series || timesChanged) && !start.isAfter(now)) {
            errors.add(series && old != null
                    ? "start: to change the times of a series, its first occurrence must be in the future"
                    : "start: must be in the future");
        }
        if (end == null) {
            errors.add("end: required");
        } else if (ended && !end.equals(old.end())) {
            errors.add("end: can't be changed after the window ended");
        } else if (!ended && !series && !end.isAfter(now)) {
            errors.add("end: must be in the future");
        } else if (start != null && !end.isAfter(start)) {
            errors.add("end: must be after the start");
        } else if (start != null && timesChanged
                && Duration.between(start, end).compareTo(Duration.ofDays(settings.maxLengthDays())) > 0) {
            errors.add("end: a window can be at most " + days(settings.maxLengthDays()) + " long");
        } else if (start != null && recurrence != null && recurrence.problems().isEmpty()
                && Duration.between(start, end).compareTo(Duration.ofDays(recurrence.minGapDays())) > 0) {
            errors.add("end: occurrences of this series can be at most " + days(recurrence.minGapDays())
                    + " long, or they would overlap");
        }
        if (recurrence != null) {
            List<String> problems = recurrence.problems();
            errors.addAll(problems);
            if (problems.isEmpty() && start != null) {
                LocalDate first = start.atZone(zone).toLocalDate();
                if (recurrence.until() != null && recurrence.until().isBefore(first)) {
                    errors.add("recurrence: the end date can't be before the start");
                } else if (recurrence.next(first) == null) {
                    errors.add("recurrence: no occurrence before the end date");
                }
            }
        }

        List<String> endpoints = distinct(draft.endpoints());
        List<String> categories = distinct(draft.categories());
        if (ended && (!endpoints.equals(old.endpoints()) || !categories.equals(old.categories()))) {
            errors.add("endpoints: can't be changed after the window ended");
        } else {
            if (endpoints.isEmpty() && categories.isEmpty()) {
                errors.add("endpoints: choose at least one service or category");
            }
            checkIds(endpoints, old == null ? List.of() : old.endpoints(), references::endpointExists,
                    "endpoints", "unknown service", errors);
            checkIds(categories, old == null ? List.of() : old.categories(), references::categoryExists,
                    "categories", "unknown category", errors);
        }
        List<String> channels = distinct(draft.notifyChannels());
        checkIds(channels, old == null ? List.of() : old.notifyChannels(), references::channelExists,
                "notifyChannels", "unknown notification channel", errors);
        return new Clean(title, description, start, end, endpoints, categories, channels, recurrence);
    }

    /** The rules for the times of a new window (also an edited occurrence). */
    private void checkNewTimes(Instant start, Instant end, Instant now, List<String> errors) {
        if (start == null) {
            errors.add("start: required");
        } else if (!start.isAfter(now)) {
            errors.add("start: must be in the future");
        }
        if (end == null) {
            errors.add("end: required");
        } else if (start != null && !end.isAfter(start)) {
            errors.add("end: must be after the start");
        } else if (start != null
                && Duration.between(start, end).compareTo(Duration.ofDays(settings.maxLengthDays())) > 0) {
            errors.add("end: a window can be at most " + days(settings.maxLengthDays()) + " long");
        }
    }

    private static String days(int n) {
        return n + " day" + (n == 1 ? "" : "s");
    }

    /** The trimmed title, or adds an error. */
    private static String cleanTitle(String raw, List<String> errors) {
        String title = raw == null ? "" : raw.strip();
        if (title.isEmpty()) {
            errors.add("title: required");
        } else if (title.codePointCount(0, title.length()) > MAX_TITLE_LENGTH) {
            errors.add("title: at most " + MAX_TITLE_LENGTH + " characters");
        } else if (title.codePoints().anyMatch(Character::isISOControl)) {
            errors.add("title: can't contain control characters");
        }
        return title;
    }

    /** The trimmed description with normalised newlines, {@code null} when blank, or adds an error. */
    private static String cleanDescription(String raw, List<String> errors) {
        String description = raw == null ? "" : raw.replace("\r\n", "\n").replace('\r', '\n').strip();
        if (description.codePointCount(0, description.length()) > MAX_TEXT_LENGTH) {
            errors.add("description: at most " + MAX_TEXT_LENGTH + " characters");
        } else if (hasControlCharacters(description)) {
            errors.add("description: can't contain control characters");
        }
        return description.isEmpty() ? null : description;
    }

    private static void checkIds(List<String> ids, List<String> kept, Predicate<String> exists, String key,
                                 String message, List<String> errors) {
        for (String id : ids) {
            if (!kept.contains(id) && !exists.test(id)) errors.add(key + ": " + message + " \"" + id + "\"");
        }
    }

    private static List<String> distinct(List<String> values) {
        Set<String> out = new LinkedHashSet<>();
        for (String v : values) {
            if (v != null && !v.isBlank()) out.add(v.strip());
        }
        return List.copyOf(out);
    }

    private static boolean hasControlCharacters(String text) {
        return text.codePoints().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\t');
    }

    private static String noteText(String text) throws InvalidWindowException {
        String clean = text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n').strip();
        if (clean.isEmpty()) throw new InvalidWindowException(List.of("text: required"));
        if (clean.codePointCount(0, clean.length()) > MAX_TEXT_LENGTH) {
            throw new InvalidWindowException(List.of("text: at most " + MAX_TEXT_LENGTH + " characters"));
        }
        if (hasControlCharacters(clean)) {
            throw new InvalidWindowException(List.of("text: can't contain control characters"));
        }
        return clean;
    }

    // ---- writing ----

    private static MaintenanceWindow changed(MaintenanceWindow w, List<EventNote> notes,
                                             List<OccurrenceChange> exceptions, Instant updatedAt,
                                             Instant cancelledAt) {
        return new MaintenanceWindow(w.id(), w.version() + 1, w.title(), w.description(), w.start(), w.end(),
                w.endpoints(), w.categories(), w.notifyChannels(), notes, w.createdBy(), w.createdByName(),
                w.createdAt(), updatedAt, cancelledAt, w.recurrence(), exceptions, w.seriesId(), w.occurrence());
    }

    /** Writes the windows as of {@code now} (due occurrences stored first) with {@code updated} replaced. */
    private void replace(MaintenanceWindow updated, Instant now) throws IOException {
        List<MaintenanceWindow> windows = current(now);
        windows.replaceAll(w -> w.id().equals(updated.id()) ? updated : w);
        write(windows);
    }

    private void write(List<MaintenanceWindow> windows) throws IOException {
        int current = state.version();
        if (current == Integer.MAX_VALUE) throw new IOException(file + ": version limit reached");
        State next = new State(current + 1, windows);
        AtomicFiles.write(file, encode(next).getBytes(StandardCharsets.UTF_8));
        state = next;
        for (Runnable listener : listeners) {
            try {
                listener.run();
            } catch (RuntimeException e) {
                System.getLogger(MaintenanceStore.class.getName())
                        .log(System.Logger.Level.ERROR, "Maintenance change listener failed", e);
            }
        }
    }

    /** Adds a listener run after every successful write, under the store's lock. */
    public void addChangeListener(Runnable listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    public void removeChangeListener(Runnable listener) {
        listeners.remove(listener);
    }

    private void record(Actor actor, String action, String id, JsonElement before, JsonElement after) {
        audit.record(actor == null ? null : actor.id(), actor == null ? null : actor.name(), action, id, before,
                after);
    }

    /** A window as recorded in the audit log: its fields without notes (those have their own entries). */
    static JsonObject auditValue(MaintenanceWindow w) {
        JsonObject o = encodeWindow(w);
        o.remove("notes");
        return o;
    }

    static JsonObject exceptionValue(OccurrenceChange change) {
        JsonObject o = encodeException(change);
        o.remove("date");
        return o;
    }

    static JsonObject noteValue(EventNote note) {
        JsonObject o = encodeNote(note);
        o.remove("id");
        return o;
    }

    // ---- format ----

    static String encode(State state) {
        JsonObject root = new JsonObject();
        root.addProperty("version", state.version());
        JsonArray windows = new JsonArray();
        state.windows().forEach(w -> windows.add(encodeWindow(w)));
        root.add("windows", windows);
        return GSON.toJson(root) + "\n";
    }

    private static JsonObject encodeWindow(MaintenanceWindow w) {
        JsonObject o = new JsonObject();
        o.addProperty("id", w.id());
        o.addProperty("version", w.version());
        o.addProperty("title", w.title());
        if (w.description() != null) o.addProperty("description", w.description());
        o.addProperty("start", w.start().toString());
        o.addProperty("end", w.end().toString());
        o.add("endpoints", strings(w.endpoints()));
        o.add("categories", strings(w.categories()));
        o.add("notifyChannels", strings(w.notifyChannels()));
        JsonArray notes = new JsonArray();
        w.notes().forEach(n -> notes.add(encodeNote(n)));
        o.add("notes", notes);
        o.add("createdBy", w.createdBy() == null ? JsonNull.INSTANCE : new JsonPrimitive(w.createdBy()));
        o.add("createdByName", w.createdByName() == null ? JsonNull.INSTANCE : new JsonPrimitive(w.createdByName()));
        o.addProperty("createdAt", w.createdAt().toString());
        o.addProperty("updatedAt", w.updatedAt().toString());
        if (w.cancelledAt() != null) o.addProperty("cancelledAt", w.cancelledAt().toString());
        if (w.recurrence() != null) {
            o.add("recurrence", encodeRecurrence(w.recurrence()));
            JsonArray exceptions = new JsonArray();
            w.exceptions().forEach(e -> exceptions.add(encodeException(e)));
            o.add("exceptions", exceptions);
        }
        if (w.seriesId() != null) {
            o.addProperty("seriesId", w.seriesId());
            o.addProperty("occurrence", w.occurrence().toString());
        }
        return o;
    }

    private static JsonObject encodeRecurrence(Recurrence r) {
        JsonObject o = new JsonObject();
        o.addProperty("frequency", r.frequency().name().toLowerCase(Locale.ROOT));
        if (r.frequency() == Recurrence.Frequency.WEEKLY) {
            JsonArray days = new JsonArray();
            r.weekdays().forEach(d -> days.add(d.name().toLowerCase(Locale.ROOT)));
            o.add("weekdays", days);
        }
        if (r.dayOfMonth() != 0) o.addProperty("dayOfMonth", r.dayOfMonth());
        if (r.week() != 0) o.addProperty("week", r.week());
        if (r.weekday() != null) o.addProperty("weekday", r.weekday().name().toLowerCase(Locale.ROOT));
        if (r.until() != null) o.addProperty("until", r.until().toString());
        return o;
    }

    private static JsonObject encodeException(OccurrenceChange c) {
        JsonObject o = new JsonObject();
        o.addProperty("date", c.date().toString());
        if (c.skipped()) {
            o.addProperty("skip", true);
        } else {
            o.addProperty("title", c.title());
            if (c.description() != null) o.addProperty("description", c.description());
            o.addProperty("start", c.start().toString());
            o.addProperty("end", c.end().toString());
        }
        return o;
    }

    private static JsonObject encodeNote(EventNote n) {
        JsonObject o = new JsonObject();
        o.addProperty("id", n.id());
        o.addProperty("author", n.author());
        o.addProperty("createdAt", n.createdAt().toString());
        if (n.editedAt() != null) o.addProperty("editedAt", n.editedAt().toString());
        o.addProperty("text", n.text());
        o.addProperty("visibility", n.visibility().name().toLowerCase(Locale.ROOT));
        return o;
    }

    private static JsonArray strings(List<String> values) {
        JsonArray a = new JsonArray();
        values.forEach(a::add);
        return a;
    }

    private static final class FormatException extends Exception {
        FormatException(String message) {
            super(message);
        }
    }

    static State decode(String json) throws FormatException {
        JsonElement root;
        try {
            root = JsonParser.parseString(json);
        } catch (JsonParseException e) {
            throw new FormatException("invalid JSON");
        }
        JsonObject o = object(root, "maintenance");
        checkKeys(o, ROOT_KEYS, "maintenance");
        int version = nonNegativeInt(o.get("version"), "version");
        JsonArray windowsJson = array(o.get("windows"), "windows");
        List<MaintenanceWindow> windows = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < windowsJson.size(); i++) {
            String where = "windows[" + i + "]";
            JsonObject w = object(windowsJson.get(i), where);
            checkKeys(w, WINDOW_KEYS, where);
            String id = string(w.get("id"), where + ".id");
            if (!ids.add(id)) throw new FormatException(where + ": duplicate id \"" + id + "\"");
            int windowVersion = nonNegativeInt(w.get("version"), where + ".version");
            Instant start = instant(w.get("start"), where + ".start");
            Instant end = instant(w.get("end"), where + ".end");
            if (!end.isAfter(start)) throw new FormatException(where + ": end must be after start");
            JsonArray notesJson = array(w.get("notes"), where + ".notes");
            List<EventNote> notes = new ArrayList<>();
            for (int j = 0; j < notesJson.size(); j++) {
                String nw = where + ".notes[" + j + "]";
                JsonObject n = object(notesJson.get(j), nw);
                checkKeys(n, NOTE_KEYS, nw);
                notes.add(new EventNote(string(n.get("id"), nw + ".id"), string(n.get("author"), nw + ".author"),
                        instant(n.get("createdAt"), nw + ".createdAt"), optionalInstant(n.get("editedAt"),
                        nw + ".editedAt"), string(n.get("text"), nw + ".text"),
                        visibility(n.get("visibility"), nw + ".visibility")));
            }
            Recurrence recurrence = w.has("recurrence") ? recurrence(w.get("recurrence"), where + ".recurrence")
                    : null;
            List<OccurrenceChange> exceptions = new ArrayList<>();
            if (w.has("exceptions")) {
                if (recurrence == null) throw new FormatException(where + ": exceptions without recurrence");
                JsonArray exceptionsJson = array(w.get("exceptions"), where + ".exceptions");
                Set<LocalDate> dates = new HashSet<>();
                for (int j = 0; j < exceptionsJson.size(); j++) {
                    OccurrenceChange c = exception(exceptionsJson.get(j), where + ".exceptions[" + j + "]");
                    if (!dates.add(c.date())) {
                        throw new FormatException(where + ".exceptions[" + j + "]: duplicate date " + c.date());
                    }
                    exceptions.add(c);
                }
            }
            String seriesId = optionalString(w.get("seriesId"), where + ".seriesId");
            LocalDate occurrence = w.has("occurrence") ? date(w.get("occurrence"), where + ".occurrence") : null;
            if ((seriesId == null) != (occurrence == null)) {
                throw new FormatException(where + ": seriesId and occurrence go together");
            }
            if (seriesId != null && recurrence != null) {
                throw new FormatException(where + ": an occurrence can't have a recurrence");
            }
            windows.add(new MaintenanceWindow(id, windowVersion, string(w.get("title"), where + ".title"),
                    optionalString(w.get("description"), where + ".description"), start, end,
                    stringList(w.get("endpoints"), where + ".endpoints"),
                    stringList(w.get("categories"), where + ".categories"),
                    stringList(w.get("notifyChannels"), where + ".notifyChannels"), notes,
                    optionalString(w.get("createdBy"), where + ".createdBy"),
                    optionalString(w.get("createdByName"), where + ".createdByName"),
                    instant(w.get("createdAt"), where + ".createdAt"),
                    instant(w.get("updatedAt"), where + ".updatedAt"),
                    optionalInstant(w.get("cancelledAt"), where + ".cancelledAt"), recurrence, exceptions,
                    seriesId, occurrence));
        }
        return new State(version, windows);
    }

    private static Recurrence recurrence(JsonElement e, String where) throws FormatException {
        JsonObject o = object(e, where);
        checkKeys(o, RECURRENCE_KEYS, where);
        Recurrence.Frequency frequency = switch (string(o.get("frequency"), where + ".frequency")) {
            case "daily" -> Recurrence.Frequency.DAILY;
            case "weekly" -> Recurrence.Frequency.WEEKLY;
            case "monthly" -> Recurrence.Frequency.MONTHLY;
            default -> throw new FormatException(where + ".frequency: expected \"daily\", \"weekly\" or \"monthly\"");
        };
        List<DayOfWeek> weekdays = new ArrayList<>();
        if (o.has("weekdays")) {
            List<String> names = stringList(o.get("weekdays"), where + ".weekdays");
            for (int i = 0; i < names.size(); i++) weekdays.add(dayOfWeek(names.get(i), where + ".weekdays[" + i + "]"));
        }
        int dayOfMonth = o.has("dayOfMonth") ? intValue(o.get("dayOfMonth"), where + ".dayOfMonth") : 0;
        int week = o.has("week") ? intValue(o.get("week"), where + ".week") : 0;
        DayOfWeek weekday = o.has("weekday") ? dayOfWeek(string(o.get("weekday"), where + ".weekday"),
                where + ".weekday") : null;
        LocalDate until = o.has("until") ? date(o.get("until"), where + ".until") : null;
        Recurrence r = new Recurrence(frequency, weekdays, dayOfMonth, week, weekday, until);
        List<String> problems = r.problems();
        if (!problems.isEmpty()) throw new FormatException(where + ": " + problems.get(0));
        return r;
    }

    private static OccurrenceChange exception(JsonElement e, String where) throws FormatException {
        JsonObject o = object(e, where);
        checkKeys(o, EXCEPTION_KEYS, where);
        LocalDate date = date(o.get("date"), where + ".date");
        if (o.has("skip")) {
            if (!(o.get("skip") instanceof JsonPrimitive p && p.isBoolean() && p.getAsBoolean())
                    || o.size() != 2) {
                throw new FormatException(where + ": a skipped occurrence has only \"date\" and \"skip\": true");
            }
            return OccurrenceChange.skip(date);
        }
        Instant start = instant(o.get("start"), where + ".start");
        Instant end = instant(o.get("end"), where + ".end");
        if (!end.isAfter(start)) throw new FormatException(where + ": end must be after start");
        return OccurrenceChange.edit(date, string(o.get("title"), where + ".title"),
                optionalString(o.get("description"), where + ".description"), start, end);
    }

    private static DayOfWeek dayOfWeek(String name, String where) throws FormatException {
        try {
            return DayOfWeek.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new FormatException(where + ": expected a weekday");
        }
    }

    private static LocalDate date(JsonElement e, String where) throws FormatException {
        String s = string(e, where);
        try {
            return LocalDate.parse(s);
        } catch (DateTimeParseException ex) {
            throw new FormatException(where + ": expected a date");
        }
    }

    private static int intValue(JsonElement e, String where) throws FormatException {
        if (e instanceof JsonPrimitive p && p.isNumber()) {
            double d = p.getAsDouble();
            if (d >= Integer.MIN_VALUE && d <= Integer.MAX_VALUE && d == Math.rint(d)) return (int) d;
        }
        throw new FormatException(where + ": expected an integer");
    }

    private static void checkKeys(JsonObject o, Set<String> allowed, String where) throws FormatException {
        for (String key : o.keySet()) {
            if (!allowed.contains(key)) throw new FormatException(where + ": unknown key \"" + key + "\"");
        }
    }

    private static JsonObject object(JsonElement e, String where) throws FormatException {
        if (e == null || !e.isJsonObject()) throw new FormatException(where + ": expected an object");
        return e.getAsJsonObject();
    }

    private static JsonArray array(JsonElement e, String where) throws FormatException {
        if (e == null || !e.isJsonArray()) throw new FormatException(where + ": expected an array");
        return e.getAsJsonArray();
    }

    private static String string(JsonElement e, String where) throws FormatException {
        if (e instanceof JsonPrimitive p && p.isString()) return p.getAsString();
        throw new FormatException(where + ": expected a string");
    }

    private static String optionalString(JsonElement e, String where) throws FormatException {
        return e == null || e.isJsonNull() ? null : string(e, where);
    }

    private static List<String> stringList(JsonElement e, String where) throws FormatException {
        JsonArray a = array(e, where);
        List<String> out = new ArrayList<>(a.size());
        for (int i = 0; i < a.size(); i++) out.add(string(a.get(i), where + "[" + i + "]"));
        return out;
    }

    private static Instant instant(JsonElement e, String where) throws FormatException {
        String s = string(e, where);
        try {
            return Instant.parse(s);
        } catch (DateTimeParseException ex) {
            throw new FormatException(where + ": expected a time");
        }
    }

    private static Instant optionalInstant(JsonElement e, String where) throws FormatException {
        return e == null || e.isJsonNull() ? null : instant(e, where);
    }

    private static EventNote.Visibility visibility(JsonElement e, String where) throws FormatException {
        String s = string(e, where);
        return switch (s) {
            case "public" -> EventNote.Visibility.PUBLIC;
            case "private" -> EventNote.Visibility.PRIVATE;
            default -> throw new FormatException(where + ": expected \"public\" or \"private\"");
        };
    }

    private static int nonNegativeInt(JsonElement e, String where) throws FormatException {
        if (e instanceof JsonPrimitive p && p.isNumber()) {
            double d = p.getAsDouble();
            if (d >= 0 && d <= Integer.MAX_VALUE && d == Math.rint(d)) return (int) d;
        }
        throw new FormatException(where + ": expected a non-negative integer");
    }
}
