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
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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
 *                  "createdAt": "…", "updatedAt": "…", "cancelledAt": "…" } ] }
 * }</pre>
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
 * </ul>
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
            "updatedAt", "cancelledAt");
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
     */
    public record Draft(String title, String description, Instant start, Instant end, List<String> endpoints,
                        List<String> categories, List<String> notifyChannels) {
        public Draft {
            endpoints = endpoints == null ? List.of() : List.copyOf(endpoints);
            categories = categories == null ? List.of() : List.copyOf(categories);
            notifyChannels = notifyChannels == null ? List.of() : List.copyOf(notifyChannels);
        }

        /** The editable fields of an existing window. */
        public static Draft of(MaintenanceWindow w) {
            return new Draft(w.title(), w.description(), w.start(), w.end(), w.endpoints(), w.categories(),
                    w.notifyChannels());
        }
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
    private volatile State state = new State(0, List.of());

    public MaintenanceStore(Path dataDir, MaintenanceSettings settings, References references, AuditLog audit,
                            Clock clock) {
        this.file = dataDir.resolve(FILE_NAME);
        this.settings = Objects.requireNonNull(settings, "settings");
        this.references = Objects.requireNonNull(references, "references");
        this.audit = audit == null ? AuditLog.disabled() : audit;
        this.clock = Objects.requireNonNull(clock, "clock");
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

    /** Every window, in creation order. */
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
                now, now, null);
        List<MaintenanceWindow> windows = new ArrayList<>(state.windows());
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
                old.createdBy(), old.createdByName(), old.createdAt(), now, null);
        replace(updated);
        record(actor, "maintenance.edit", id, auditValue(old), auditValue(updated));
        return Optional.of(updated);
    }

    /**
     * Cancels a window that hasn't started yet (one in progress is ended early instead).
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
        MaintenanceWindow cancelled = withNotes(old, old.notes(), now, now);
        replace(cancelled);
        record(actor, "maintenance.cancel", id, auditValue(old), auditValue(cancelled));
        return Optional.of(cancelled);
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
        replace(withNotes(old, notes, now, old.cancelledAt()));
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
        replace(withNotes(old, notes, now, old.cancelledAt()));
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
        replace(withNotes(old, notes, clock.instant(), old.cancelledAt()));
        record(actor, "maintenance.note.delete", windowId + "/" + noteId, noteValue(before), null);
        return true;
    }

    // ---- validation ----

    private record Clean(String title, String description, Instant start, Instant end, List<String> endpoints,
                         List<String> categories, List<String> channels) {
    }

    /** Validates a draft for a new window ({@code old == null}) or an edit of {@code old}. */
    private Clean clean(Draft draft, MaintenanceWindow old, Instant now, List<String> errors) {
        String title = draft.title() == null ? "" : draft.title().strip();
        if (title.isEmpty()) {
            errors.add("title: required");
        } else if (title.codePointCount(0, title.length()) > MAX_TITLE_LENGTH) {
            errors.add("title: at most " + MAX_TITLE_LENGTH + " characters");
        } else if (title.codePoints().anyMatch(Character::isISOControl)) {
            errors.add("title: can't contain control characters");
        }
        String description = draft.description() == null ? ""
                : draft.description().replace("\r\n", "\n").replace('\r', '\n').strip();
        if (description.codePointCount(0, description.length()) > MAX_TEXT_LENGTH) {
            errors.add("description: at most " + MAX_TEXT_LENGTH + " characters");
        } else if (hasControlCharacters(description)) {
            errors.add("description: can't contain control characters");
        }

        Instant start = draft.start();
        Instant end = draft.end();
        boolean started = old != null && !now.isBefore(old.start());
        boolean ended = old != null && !now.isBefore(old.end());
        if (start == null) {
            errors.add("start: required");
        } else if (started && !start.equals(old.start())) {
            errors.add("start: can't be changed after the window started");
        } else if (!started && !start.isAfter(now)) {
            errors.add("start: must be in the future");
        }
        if (end == null) {
            errors.add("end: required");
        } else if (ended && !end.equals(old.end())) {
            errors.add("end: can't be changed after the window ended");
        } else if (!ended && !end.isAfter(now)) {
            errors.add("end: must be in the future");
        } else if (start != null && !end.isAfter(start)) {
            errors.add("end: must be after the start");
        } else if (start != null && (old == null || !start.equals(old.start()) || !end.equals(old.end()))
                && Duration.between(start, end).compareTo(Duration.ofDays(settings.maxLengthDays())) > 0) {
            errors.add("end: a window can be at most " + settings.maxLengthDays() + " day"
                    + (settings.maxLengthDays() == 1 ? "" : "s") + " long");
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
        return new Clean(title, description.isEmpty() ? null : description, start, end, endpoints, categories,
                channels);
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

    private static MaintenanceWindow withNotes(MaintenanceWindow w, List<EventNote> notes, Instant updatedAt,
                                               Instant cancelledAt) {
        return new MaintenanceWindow(w.id(), w.version() + 1, w.title(), w.description(), w.start(), w.end(),
                w.endpoints(), w.categories(), w.notifyChannels(), notes, w.createdBy(), w.createdByName(),
                w.createdAt(), updatedAt, cancelledAt);
    }

    private void replace(MaintenanceWindow updated) throws IOException {
        List<MaintenanceWindow> windows = new ArrayList<>(state.windows());
        windows.replaceAll(w -> w.id().equals(updated.id()) ? updated : w);
        write(windows);
    }

    private void write(List<MaintenanceWindow> windows) throws IOException {
        int current = state.version();
        if (current == Integer.MAX_VALUE) throw new IOException(file + ": version limit reached");
        State next = new State(current + 1, windows);
        AtomicFiles.write(file, encode(next).getBytes(StandardCharsets.UTF_8));
        state = next;
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
            windows.add(new MaintenanceWindow(id, windowVersion, string(w.get("title"), where + ".title"),
                    optionalString(w.get("description"), where + ".description"), start, end,
                    stringList(w.get("endpoints"), where + ".endpoints"),
                    stringList(w.get("categories"), where + ".categories"),
                    stringList(w.get("notifyChannels"), where + ".notifyChannels"), notes,
                    optionalString(w.get("createdBy"), where + ".createdBy"),
                    optionalString(w.get("createdByName"), where + ".createdByName"),
                    instant(w.get("createdAt"), where + ".createdAt"),
                    instant(w.get("updatedAt"), where + ".updatedAt"),
                    optionalInstant(w.get("cancelledAt"), where + ".cancelledAt")));
        }
        return new State(version, windows);
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
