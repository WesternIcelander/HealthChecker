package io.siggi.healthchecker.maintenance;

import com.google.gson.JsonObject;
import io.siggi.healthchecker.audit.AuditLog;
import io.siggi.healthchecker.config.MaintenanceSettings;
import io.siggi.healthchecker.history.EventNote;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaintenanceStoreTest {
    private static final Instant T0 = Instant.parse("2026-11-14T12:00:00Z");
    private static final MaintenanceStore.Actor SIGGI = new MaintenanceStore.Actor("siggi", "Siggi");

    @TempDir
    Path dir;

    private final MutableClock clock = new MutableClock(T0);
    private AuditLog audit;

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static final MaintenanceStore.References REFS = new MaintenanceStore.References() {
        @Override
        public boolean endpointExists(String id) {
            return Set.of("website", "survival").contains(id);
        }

        @Override
        public boolean categoryExists(String id) {
            return Set.of("web", "minecraft").contains(id);
        }

        @Override
        public boolean channelExists(String name) {
            return Set.of("discord", "ops-email").contains(name);
        }
    };

    @BeforeEach
    void setUp() {
        audit = new AuditLog(dir.resolve(AuditLog.FILE_NAME), clock);
    }

    private MaintenanceStore store() throws Exception {
        MaintenanceStore store = new MaintenanceStore(dir, new MaintenanceSettings(false, 7, 7), REFS, audit, clock);
        store.load();
        return store;
    }

    private static MaintenanceStore.Draft draft(Instant start, Instant end) {
        return new MaintenanceStore.Draft("Weekly reboot", "Kernel update", start, end, List.of("website"),
                List.of("minecraft"), List.of("discord"));
    }

    private static Instant in(Duration d) {
        return T0.plus(d);
    }

    private static List<String> errors(Exception e) {
        return ((MaintenanceStore.InvalidWindowException) e).errors();
    }

    @Test
    void createsAndReloadsWindow() throws Exception {
        MaintenanceStore store = store();
        assertEquals(0, store.version());
        MaintenanceWindow w = store.create(draft(in(Duration.ofHours(1)), in(Duration.ofHours(2))), SIGGI);
        assertEquals(1, w.version());
        assertEquals("Weekly reboot", w.title());
        assertEquals("Kernel update", w.description());
        assertEquals(List.of("website"), w.endpoints());
        assertEquals(List.of("minecraft"), w.categories());
        assertEquals(List.of("discord"), w.notifyChannels());
        assertEquals("siggi", w.createdBy());
        assertEquals(T0, w.createdAt());
        assertEquals(MaintenanceWindow.Status.SCHEDULED, w.status(T0));
        assertEquals(MaintenanceWindow.Status.IN_PROGRESS, w.status(in(Duration.ofHours(1))));
        assertEquals(MaintenanceWindow.Status.COMPLETED, w.status(in(Duration.ofHours(2))));
        assertTrue(w.affects("survival", "minecraft"));
        assertTrue(w.affects("website", null));
        assertFalse(w.affects("admin", "internal"));
        assertEquals(1, store.version());

        MaintenanceStore reloaded = store();
        assertEquals(List.of(w), reloaded.windows());
        assertEquals(1, reloaded.version());
    }

    @Test
    void cannotCreateInThePast() throws Exception {
        MaintenanceStore store = store();
        var e = assertThrows(MaintenanceStore.InvalidWindowException.class,
                () -> store.create(draft(in(Duration.ofMinutes(-5)), in(Duration.ofHours(1))), SIGGI));
        assertEquals(List.of("start: must be in the future"), errors(e));
        e = assertThrows(MaintenanceStore.InvalidWindowException.class,
                () -> store.create(draft(T0, in(Duration.ofHours(1))), SIGGI));
        assertEquals(List.of("start: must be in the future"), errors(e));
        e = assertThrows(MaintenanceStore.InvalidWindowException.class,
                () -> store.create(draft(in(Duration.ofHours(-2)), in(Duration.ofHours(-1))), SIGGI));
        assertTrue(errors(e).contains("start: must be in the future"));
        assertTrue(errors(e).contains("end: must be in the future"));
        assertTrue(store.windows().isEmpty());
        assertFalse(Files.exists(store.file()));
    }

    @Test
    void cannotBeLongerThanTheLimit() throws Exception {
        MaintenanceStore store = store();
        Instant start = in(Duration.ofHours(1));
        var e = assertThrows(MaintenanceStore.InvalidWindowException.class,
                () -> store.create(draft(start, start.plus(Duration.ofDays(7)).plusSeconds(1)), SIGGI));
        assertEquals(List.of("end: a window can be at most 7 days long"), errors(e));
        store.create(draft(start, start.plus(Duration.ofDays(7))), SIGGI);

        MaintenanceStore oneDay = new MaintenanceStore(dir, new MaintenanceSettings(false, 1, 7), REFS, audit, clock);
        oneDay.load();
        e = assertThrows(MaintenanceStore.InvalidWindowException.class,
                () -> oneDay.create(draft(start, start.plus(Duration.ofHours(25))), SIGGI));
        assertEquals(List.of("end: a window can be at most 1 day long"), errors(e));
    }

    @Test
    void endMustBeAfterStart() throws Exception {
        MaintenanceStore store = store();
        Instant start = in(Duration.ofHours(2));
        var e = assertThrows(MaintenanceStore.InvalidWindowException.class,
                () -> store.create(draft(start, start), SIGGI));
        assertEquals(List.of("end: must be after the start"), errors(e));
        e = assertThrows(MaintenanceStore.InvalidWindowException.class,
                () -> store.create(draft(start, start.minusSeconds(60)), SIGGI));
        assertEquals(List.of("end: must be after the start"), errors(e));
        e = assertThrows(MaintenanceStore.InvalidWindowException.class,
                () -> store.create(draft(null, null), SIGGI));
        assertEquals(List.of("start: required", "end: required"), errors(e));
    }

    @Test
    void validatesFieldsAndReferences() throws Exception {
        MaintenanceStore store = store();
        Instant start = in(Duration.ofHours(1));
        var e = assertThrows(MaintenanceStore.InvalidWindowException.class, () -> store.create(
                new MaintenanceStore.Draft("  ", null, start, start.plusSeconds(60), List.of(), List.of(), List.of()),
                SIGGI));
        assertEquals(List.of("title: required", "endpoints: choose at least one service or category"), errors(e));

        e = assertThrows(MaintenanceStore.InvalidWindowException.class, () -> store.create(
                new MaintenanceStore.Draft("x".repeat(201), "a\u0007b", start, start.plusSeconds(60),
                        List.of("nope"), List.of("gone"), List.of("pager")), SIGGI));
        assertEquals(List.of("title: at most 200 characters", "description: can't contain control characters",
                "endpoints: unknown service \"nope\"", "categories: unknown category \"gone\"",
                "notifyChannels: unknown notification channel \"pager\""), errors(e));

        MaintenanceWindow w = store.create(new MaintenanceStore.Draft(" Reboot ", "  ", start, start.plusSeconds(60),
                List.of("website", "website", " "), List.of(), null), SIGGI);
        assertEquals("Reboot", w.title());
        assertNull(w.description());
        assertEquals(List.of("website"), w.endpoints());
        assertEquals(List.of(), w.notifyChannels());
    }

    @Test
    void staleSaveIsAConflict() throws Exception {
        MaintenanceStore store = store();
        MaintenanceWindow w = store.create(draft(in(Duration.ofHours(1)), in(Duration.ofHours(2))), SIGGI);
        MaintenanceWindow other = store.create(draft(in(Duration.ofHours(3)), in(Duration.ofHours(4))), SIGGI);

        MaintenanceStore.Draft renamed = new MaintenanceStore.Draft("Renamed", null, w.start(), w.end(),
                w.endpoints(), w.categories(), w.notifyChannels());
        MaintenanceWindow edited = store.update(w.id(), 1, renamed, SIGGI).orElseThrow();
        assertEquals(2, edited.version());
        assertEquals("Renamed", edited.title());

        var conflict = assertThrows(MaintenanceStore.VersionConflictException.class,
                () -> store.update(w.id(), 1, draft(w.start(), w.end()), SIGGI));
        assertEquals(2, conflict.currentVersion());
        assertThrows(MaintenanceStore.VersionConflictException.class, () -> store.cancel(w.id(), 1, SIGGI));
        // Other windows aren't affected by the edit.
        assertTrue(store.cancel(other.id(), 1, SIGGI).isPresent());
        assertEquals("Renamed", store().window(w.id()).orElseThrow().title());
        assertTrue(store.update("missing", 1, renamed, SIGGI).isEmpty());
    }

    @Test
    void pastTimesAreFixed() throws Exception {
        MaintenanceStore store = store();
        MaintenanceWindow w = store.create(draft(in(Duration.ofHours(1)), in(Duration.ofHours(3))), SIGGI);
        clock.advance(Duration.ofHours(2)); // in progress

        var e = assertThrows(MaintenanceStore.InvalidWindowException.class,
                () -> store.update(w.id(), 1, draft(in(Duration.ofMinutes(150)), w.end()), SIGGI));
        assertEquals(List.of("start: can't be changed after the window started"), errors(e));
        e = assertThrows(MaintenanceStore.InvalidWindowException.class,
                () -> store.cancel(w.id(), 1, SIGGI));
        assertEquals(List.of("the window has started; end it instead"), errors(e));
        // The end is still in the future, so it can move.
        MaintenanceWindow extended = store.update(w.id(), 1, draft(w.start(), in(Duration.ofHours(5))), SIGGI)
                .orElseThrow();
        assertEquals(in(Duration.ofHours(5)), extended.end());

        clock.advance(Duration.ofHours(4)); // completed
        e = assertThrows(MaintenanceStore.InvalidWindowException.class,
                () -> store.update(w.id(), 2, draft(w.start(), in(Duration.ofHours(7))), SIGGI));
        assertEquals(List.of("end: can't be changed after the window ended"), errors(e));
        MaintenanceStore.Draft otherServices = new MaintenanceStore.Draft("Weekly reboot", null, w.start(),
                extended.end(), List.of("survival"), List.of(), List.of());
        e = assertThrows(MaintenanceStore.InvalidWindowException.class,
                () -> store.update(w.id(), 2, otherServices, SIGGI));
        assertEquals(List.of("endpoints: can't be changed after the window ended"), errors(e));
        // Title and description can still be corrected.
        MaintenanceStore.Draft retitled = new MaintenanceStore.Draft("Reboot (done)", null, w.start(),
                extended.end(), w.endpoints(), w.categories(), w.notifyChannels());
        assertEquals("Reboot (done)", store.update(w.id(), 2, retitled, SIGGI).orElseThrow().title());
    }

    @Test
    void cancelledWindowsCantBeEdited() throws Exception {
        MaintenanceStore store = store();
        MaintenanceWindow w = store.create(draft(in(Duration.ofHours(1)), in(Duration.ofHours(2))), SIGGI);
        MaintenanceWindow cancelled = store.cancel(w.id(), 1, SIGGI).orElseThrow();
        assertEquals(T0, cancelled.cancelledAt());
        assertEquals(MaintenanceWindow.Status.CANCELLED, cancelled.status(in(Duration.ofHours(1))));
        var e = assertThrows(MaintenanceStore.InvalidWindowException.class,
                () -> store.update(w.id(), 2, draft(w.start(), w.end()), SIGGI));
        assertEquals(List.of("the window was cancelled"), errors(e));
        e = assertThrows(MaintenanceStore.InvalidWindowException.class, () -> store.cancel(w.id(), 2, SIGGI));
        assertEquals(List.of("the window was already cancelled"), errors(e));
        assertEquals(T0, store().window(w.id()).orElseThrow().cancelledAt());
    }

    @Test
    void notes() throws Exception {
        MaintenanceStore store = store();
        MaintenanceWindow w = store.create(draft(in(Duration.ofHours(1)), in(Duration.ofHours(2))), SIGGI);
        EventNote note = store.addNote(w.id(), " Rebooting\r\nnow ", EventNote.Visibility.PRIVATE, SIGGI)
                .orElseThrow();
        assertEquals("Rebooting\nnow", note.text());
        assertEquals("Siggi", note.author());
        clock.advance(Duration.ofMinutes(1));
        EventNote edited = store.editNote(w.id(), note.id(), "Done", EventNote.Visibility.PUBLIC, SIGGI).orElseThrow();
        assertEquals(in(Duration.ofMinutes(1)), edited.editedAt());
        assertEquals(List.of(edited), store().window(w.id()).orElseThrow().notes());
        assertThrows(MaintenanceStore.InvalidWindowException.class,
                () -> store.addNote(w.id(), "x".repeat(10_001), EventNote.Visibility.PUBLIC, SIGGI));
        assertThrows(MaintenanceStore.InvalidWindowException.class,
                () -> store.addNote(w.id(), "  ", EventNote.Visibility.PUBLIC, SIGGI));
        assertTrue(store.deleteNote(w.id(), note.id(), SIGGI));
        assertFalse(store.deleteNote(w.id(), note.id(), SIGGI));
        assertTrue(store().window(w.id()).orElseThrow().notes().isEmpty());
        assertTrue(store.addNote("missing", "x", EventNote.Visibility.PUBLIC, SIGGI).isEmpty());
    }

    @Test
    void writesAuditEntries() throws Exception {
        MaintenanceStore store = store();
        MaintenanceWindow w = store.create(draft(in(Duration.ofHours(1)), in(Duration.ofHours(2))), SIGGI);
        MaintenanceStore.Draft renamed = new MaintenanceStore.Draft("Renamed", null, w.start(), w.end(),
                w.endpoints(), w.categories(), w.notifyChannels());
        store.update(w.id(), 1, renamed, SIGGI);
        EventNote note = store.addNote(w.id(), "Hello", EventNote.Visibility.PUBLIC, SIGGI).orElseThrow();
        store.editNote(w.id(), note.id(), "Hi", EventNote.Visibility.PRIVATE, SIGGI);
        store.deleteNote(w.id(), note.id(), SIGGI);
        int version = store.window(w.id()).orElseThrow().version();
        store.cancel(w.id(), version, SIGGI);
        // Refused changes write nothing.
        assertThrows(MaintenanceStore.VersionConflictException.class, () -> store.update(w.id(), 1, renamed, SIGGI));
        assertThrows(MaintenanceStore.InvalidWindowException.class,
                () -> store.create(draft(in(Duration.ofHours(-1)), in(Duration.ofHours(1))), SIGGI));

        List<AuditLog.Entry> entries = audit.entries();
        assertEquals(List.of("maintenance.create", "maintenance.edit", "maintenance.note.add",
                "maintenance.note.edit", "maintenance.note.delete", "maintenance.cancel"),
                entries.stream().map(AuditLog.Entry::action).toList());
        String noteId = w.id() + "/" + note.id();
        assertEquals(List.of(w.id(), w.id(), noteId, noteId, noteId, w.id()),
                entries.stream().map(AuditLog.Entry::id).toList());
        entries.forEach(e -> assertEquals("siggi", e.user()));
        entries.forEach(e -> assertEquals("Siggi", e.userName()));

        AuditLog.Entry create = entries.get(0);
        assertTrue(create.before().isJsonNull());
        JsonObject after = create.after().getAsJsonObject();
        assertEquals("Weekly reboot", after.get("title").getAsString());
        assertEquals(w.start().toString(), after.get("start").getAsString());
        assertFalse(after.has("notes"));

        AuditLog.Entry edit = entries.get(1);
        assertEquals("Weekly reboot", edit.before().getAsJsonObject().get("title").getAsString());
        assertEquals("Renamed", edit.after().getAsJsonObject().get("title").getAsString());

        assertTrue(entries.get(2).before().isJsonNull());
        assertEquals("Hello", entries.get(2).after().getAsJsonObject().get("text").getAsString());
        assertEquals("Hi", entries.get(3).after().getAsJsonObject().get("text").getAsString());
        assertEquals("private", entries.get(3).after().getAsJsonObject().get("visibility").getAsString());
        assertTrue(entries.get(4).after().isJsonNull());

        AuditLog.Entry cancel = entries.get(5);
        assertFalse(cancel.before().getAsJsonObject().has("cancelledAt"));
        assertEquals(T0.toString(), cancel.after().getAsJsonObject().get("cancelledAt").getAsString());
    }

    @Test
    void atomicWritesAndMalformedFile() throws Exception {
        MaintenanceStore store = store();
        store.create(draft(in(Duration.ofHours(1)), in(Duration.ofHours(2))), SIGGI);
        Path leftover = dir.resolve("." + MaintenanceStore.FILE_NAME + ".abc.tmp");
        Files.writeString(leftover, "partial");
        MaintenanceStore reloaded = store();
        assertFalse(Files.exists(leftover));
        assertEquals(1, reloaded.windows().size());
        try (var files = Files.list(dir)) {
            assertTrue(files.noneMatch(p -> p.getFileName().toString().endsWith(".tmp")));
        }

        String json = Files.readString(store.file(), StandardCharsets.UTF_8);
        Files.writeString(store.file(), json.replace("\"title\"", "\"titel\""));
        var e = assertThrows(java.io.IOException.class, this::store);
        assertTrue(e.getMessage().contains(MaintenanceStore.FILE_NAME), e.getMessage());
        assertTrue(e.getMessage().contains("unknown key \"titel\""), e.getMessage());
        Files.writeString(store.file(), "{");
        assertThrows(java.io.IOException.class, this::store);
    }
}
