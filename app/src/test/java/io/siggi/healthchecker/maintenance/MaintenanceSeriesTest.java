package io.siggi.healthchecker.maintenance;

import io.siggi.healthchecker.audit.AuditLog;
import io.siggi.healthchecker.config.MaintenanceSettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;

import static java.time.DayOfWeek.MONDAY;
import static java.time.DayOfWeek.SUNDAY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaintenanceSeriesTest {
    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    /** A Saturday, 13:00 in Berlin. */
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

        void set(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
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
            return Set.of("discord").contains(name);
        }
    };

    @BeforeEach
    void setUp() {
        audit = new AuditLog(dir.resolve(AuditLog.FILE_NAME), clock);
    }

    private MaintenanceStore store() throws Exception {
        MaintenanceStore store = new MaintenanceStore(dir, new MaintenanceSettings(false, 7, 7), REFS, audit, clock,
                BERLIN);
        store.load();
        return store;
    }

    /** {@code local} as a time in Berlin. */
    private static Instant berlin(String local) {
        return ZonedDateTime.of(java.time.LocalDateTime.parse(local), BERLIN).toInstant();
    }

    private static MaintenanceStore.Draft series(String title, Instant start, Instant end, Recurrence rule) {
        return new MaintenanceStore.Draft(title, null, start, end, List.of("website"), List.of(), List.of("discord"),
                rule);
    }

    private static LocalTime localTime(Instant t) {
        return t.atZone(BERLIN).toLocalTime();
    }

    private static List<String> errors(Exception e) {
        return ((MaintenanceStore.InvalidWindowException) e).errors();
    }

    /** Weekly on Sundays 04:00–05:00 Berlin time, from 2026-11-15. */
    private MaintenanceWindow weeklyReboot(MaintenanceStore store) throws Exception {
        return store.create(series("Weekly reboot", berlin("2026-11-15T04:00"), berlin("2026-11-15T05:00"),
                Recurrence.weekly(List.of(SUNDAY), null)), SIGGI);
    }

    @Test
    void weeklyAcrossDstChangesKeepsLocalTime() throws Exception {
        MaintenanceStore store = store();
        MaintenanceWindow s = weeklyReboot(store);
        assertTrue(s.isSeries());
        assertEquals(MaintenanceWindow.Status.SCHEDULED, s.status(Instant.parse("2027-06-01T00:00:00Z")));

        // Spring forward (2027-03-28: CET → CEST).
        List<MaintenanceWindow> spring = store.occurrences(Instant.parse("2027-03-20T00:00:00Z"),
                Instant.parse("2027-04-05T00:00:00Z"));
        assertEquals(List.of(Instant.parse("2027-03-21T03:00:00Z"), Instant.parse("2027-03-28T02:00:00Z"),
                Instant.parse("2027-04-04T02:00:00Z")), spring.stream().map(MaintenanceWindow::start).toList());
        // Fall back (2027-10-31: CEST → CET).
        List<MaintenanceWindow> fall = store.occurrences(Instant.parse("2027-10-23T00:00:00Z"),
                Instant.parse("2027-11-08T00:00:00Z"));
        assertEquals(List.of(Instant.parse("2027-10-24T02:00:00Z"), Instant.parse("2027-10-31T03:00:00Z"),
                Instant.parse("2027-11-07T03:00:00Z")), fall.stream().map(MaintenanceWindow::start).toList());
        for (MaintenanceWindow o : spring) {
            assertEquals(LocalTime.of(4, 0), localTime(o.start()));
            assertEquals(LocalTime.of(5, 0), localTime(o.end()));
            assertEquals(s.id(), o.seriesId());
            assertEquals(MaintenanceStore.occurrenceId(s.id(), o.occurrence()), o.id());
            assertEquals("Weekly reboot", o.title());
            assertEquals(List.of("website"), o.endpoints());
        }
        for (MaintenanceWindow o : fall) {
            assertEquals(LocalTime.of(4, 0), localTime(o.start()));
            assertEquals(LocalTime.of(5, 0), localTime(o.end()));
        }
        assertEquals(LocalDate.parse("2027-03-28"), spring.get(1).occurrence());
        assertEquals(spring.get(1), store.occurrence(spring.get(1).id()).orElseThrow());
        assertTrue(store.occurrence(s.id()).isEmpty());
    }

    @Test
    void localTimeInTheSpringGapMovesForward() throws Exception {
        MaintenanceStore store = store();
        store.create(series("Nightly", berlin("2027-03-27T02:30"), berlin("2027-03-27T03:30"),
                Recurrence.daily(LocalDate.parse("2027-03-29"))), SIGGI);
        List<MaintenanceWindow> all = store.occurrences(T0, Instant.parse("2028-01-01T00:00:00Z"));
        assertEquals(3, all.size());
        // 02:30 doesn't exist on 2027-03-28; it becomes 03:30 CEST, still an hour long.
        assertEquals(Instant.parse("2027-03-28T01:30:00Z"), all.get(1).start());
        assertEquals(Instant.parse("2027-03-28T02:30:00Z"), all.get(1).end());
        assertEquals(LocalTime.of(2, 30), localTime(all.get(2).start()));
        assertEquals(LocalTime.of(3, 30), localTime(all.get(2).end()));
    }

    @Test
    void monthlyFirstSunday() throws Exception {
        MaintenanceStore store = store();
        // The first possible occurrence is a Tuesday; the rule decides the dates.
        MaintenanceWindow s = store.create(series("Monthly patching", berlin("2026-12-01T10:00"),
                berlin("2026-12-01T12:00"), Recurrence.monthlyOnWeekday(1, SUNDAY, LocalDate.parse("2027-04-30"))),
                SIGGI);
        List<MaintenanceWindow> all = store.occurrences(T0, Instant.parse("2028-01-01T00:00:00Z"));
        assertEquals(List.of("2026-12-06", "2027-01-03", "2027-02-07", "2027-03-07", "2027-04-04"),
                all.stream().map(o -> o.occurrence().toString()).toList());
        for (MaintenanceWindow o : all) {
            assertEquals(SUNDAY, o.start().atZone(BERLIN).getDayOfWeek());
            assertEquals(LocalTime.of(10, 0), localTime(o.start()));
            assertEquals(LocalTime.of(12, 0), localTime(o.end()));
        }
        assertEquals(Instant.parse("2027-04-04T08:00:00Z"), all.get(4).start());
        assertEquals(s.recurrence(), store().window(s.id()).orElseThrow().recurrence());
    }

    @Test
    void skipAndEditOneOccurrence() throws Exception {
        MaintenanceStore store = store();
        MaintenanceWindow s = weeklyReboot(store);
        LocalDate nov22 = LocalDate.parse("2026-11-22");
        LocalDate nov29 = LocalDate.parse("2026-11-29");

        MaintenanceWindow skipped = store.skipOccurrence(s.id(), 1, nov22, SIGGI).orElseThrow();
        assertEquals(2, skipped.version());
        assertThrows(MaintenanceStore.VersionConflictException.class,
                () -> store.skipOccurrence(s.id(), 1, nov29, SIGGI));
        Instant editedStart = berlin("2026-11-29T06:00");
        Instant editedEnd = berlin("2026-11-29T08:00");
        MaintenanceWindow edited = store.editOccurrence(s.id(), 2, nov29, new MaintenanceStore.OccurrenceDraft(
                " Kernel upgrade ", "Longer this time", editedStart, editedEnd), SIGGI).orElseThrow();
        assertEquals(3, edited.version());
        // The series itself is unchanged.
        assertEquals("Weekly reboot", edited.title());
        assertEquals(s.start(), edited.start());

        Instant to = berlin("2026-12-07T00:00");
        List<MaintenanceWindow> all = store.occurrences(T0, to);
        assertEquals(List.of("2026-11-15", "2026-11-29", "2026-12-06"),
                all.stream().map(o -> o.occurrence().toString()).toList());
        MaintenanceWindow nov29Occurrence = all.get(1);
        assertEquals("Kernel upgrade", nov29Occurrence.title());
        assertEquals("Longer this time", nov29Occurrence.description());
        assertEquals(editedStart, nov29Occurrence.start());
        assertEquals(editedEnd, nov29Occurrence.end());
        assertEquals("Weekly reboot", all.get(2).title());
        assertTrue(store.occurrence(MaintenanceStore.occurrenceId(s.id(), nov22)).isEmpty());

        // Persisted.
        MaintenanceStore reloaded = store();
        assertEquals(all, reloaded.occurrences(T0, to));
        assertEquals(store.windows(), reloaded.windows());

        // Bad requests.
        Exception notAnOccurrence = assertThrows(MaintenanceStore.InvalidWindowException.class,
                () -> store.skipOccurrence(s.id(), 3, LocalDate.parse("2026-11-23"), SIGGI));
        assertEquals(List.of("date: the series has no occurrence on 2026-11-23"), errors(notAnOccurrence));
        Exception badTimes = assertThrows(MaintenanceStore.InvalidWindowException.class,
                () -> store.editOccurrence(s.id(), 3, LocalDate.parse("2026-12-06"),
                        new MaintenanceStore.OccurrenceDraft("", null, T0.minusSeconds(60), T0.minusSeconds(120)),
                        SIGGI));
        assertEquals(List.of("title: required", "start: must be in the future", "end: must be after the start"),
                errors(badTimes));

        // Restoring brings the skipped one back.
        store.restoreOccurrence(s.id(), 3, nov22, SIGGI);
        assertEquals(List.of("2026-11-15", "2026-11-22", "2026-11-29", "2026-12-06"),
                store.occurrences(T0, to).stream().map(o -> o.occurrence().toString()).toList());

        // An occurrence that has started can't be skipped.
        clock.set(berlin("2026-11-15T04:30"));
        Exception started = assertThrows(MaintenanceStore.InvalidWindowException.class,
                () -> store.skipOccurrence(s.id(), 4, LocalDate.parse("2026-11-15"), SIGGI));
        assertEquals(List.of("date: that occurrence has already started"), errors(started));

        // The edited occurrence is stored as edited when it starts.
        clock.set(editedStart);
        List<MaintenanceWindow> created = store.materialise();
        assertEquals(List.of("2026-11-15", "2026-11-22", "2026-11-29"),
                created.stream().map(o -> o.occurrence().toString()).toList());
        MaintenanceWindow stored = store.window(MaintenanceStore.occurrenceId(s.id(), nov29)).orElseThrow();
        assertEquals("Kernel upgrade", stored.title());
        assertEquals(editedStart, stored.start());
        assertEquals(MaintenanceWindow.Status.IN_PROGRESS, stored.status(clock.instant()));

        List<String> actions = audit.entries().stream().map(AuditLog.Entry::action).toList();
        assertEquals(List.of("maintenance.create", "maintenance.occurrence.skip", "maintenance.occurrence.edit",
                "maintenance.occurrence.restore"), actions);
        AuditLog.Entry editEntry = audit.entries().get(2);
        assertEquals(s.id() + "/2026-11-29", editEntry.id());
        assertEquals("Kernel upgrade", editEntry.after().getAsJsonObject().get("title").getAsString());
    }

    @Test
    void editingSeriesDoesNotChangePastOccurrences() throws Exception {
        MaintenanceStore store = store();
        MaintenanceWindow s = weeklyReboot(store);
        // Two occurrences (Nov 15 and 22) have happened, but nothing called materialise().
        clock.set(Instant.parse("2026-11-23T00:00:00Z"));

        MaintenanceStore.Draft changed = new MaintenanceStore.Draft("Weekly reboot v2", null, s.start(), s.end(),
                List.of("survival"), List.of(), List.of(), Recurrence.weekly(List.of(SUNDAY, MONDAY), null));
        MaintenanceWindow updated = store.update(s.id(), 1, changed, SIGGI).orElseThrow();
        assertEquals(2, updated.version());

        Instant from = berlin("2026-11-14T00:00");
        Instant to = berlin("2026-11-25T00:00");
        List<MaintenanceWindow> all = store.occurrences(from, to);
        // Monday Nov 16 never happened, so the new rule doesn't add it; Monday Nov 23 is still ahead.
        assertEquals(List.of("2026-11-15", "2026-11-22", "2026-11-23"),
                all.stream().map(o -> o.occurrence().toString()).toList());
        assertEquals(List.of("Weekly reboot", "Weekly reboot", "Weekly reboot v2"),
                all.stream().map(MaintenanceWindow::title).toList());
        assertEquals(List.of("website"), all.get(0).endpoints());
        assertEquals(List.of("survival"), all.get(2).endpoints());
        assertTrue(store.window(all.get(0).id()).isPresent());
        assertTrue(store.window(all.get(1).id()).isPresent());
        assertFalse(store.window(all.get(2).id()).isPresent());
        assertEquals(List.of(), store.materialise());

        // Times can only change to a first occurrence in the future.
        Exception past = assertThrows(MaintenanceStore.InvalidWindowException.class,
                () -> store.update(s.id(), 2, new MaintenanceStore.Draft("Weekly reboot v2", null, s.start(),
                        s.end().plusSeconds(1800), List.of("survival"), List.of(), List.of(), updated.recurrence()),
                        SIGGI));
        assertEquals(List.of("start: to change the times of a series, its first occurrence must be in the future"),
                errors(past));
        MaintenanceWindow moved = store.update(s.id(), 2, new MaintenanceStore.Draft("Weekly reboot v3", null,
                berlin("2026-11-29T06:00"), berlin("2026-11-29T07:00"), List.of("survival"), List.of(), List.of(),
                updated.recurrence()), SIGGI).orElseThrow();
        assertEquals(3, moved.version());
        all = store.occurrences(from, berlin("2026-12-01T00:00"));
        // Nov 23 was dropped by moving the start past it.
        assertEquals(List.of("2026-11-15", "2026-11-22", "2026-11-29", "2026-11-30"),
                all.stream().map(o -> o.occurrence().toString()).toList());
        assertEquals(LocalTime.of(4, 0), localTime(all.get(0).start()));
        assertEquals(LocalTime.of(4, 0), localTime(all.get(1).start()));
        assertEquals(LocalTime.of(6, 0), localTime(all.get(2).start()));
        assertEquals(LocalTime.of(6, 0), localTime(all.get(3).start()));
        assertEquals("Weekly reboot", all.get(1).title());

        // The stored occurrences are plain windows; past times are fixed as for any window.
        MaintenanceWindow nov15 = store.window(all.get(0).id()).orElseThrow();
        assertNull(nov15.recurrence());
        assertEquals(s.id(), nov15.seriesId());
        assertEquals(MaintenanceWindow.Status.COMPLETED, nov15.status(clock.instant()));
        MaintenanceStore reloaded = store();
        assertEquals(store.windows(), reloaded.windows());
    }

    @Test
    void cancellingSeriesKeepsWhatHappened() throws Exception {
        MaintenanceStore store = store();
        MaintenanceWindow s = weeklyReboot(store);
        clock.set(berlin("2026-11-15T04:30"));
        MaintenanceWindow cancelled = store.cancel(s.id(), 1, SIGGI).orElseThrow();
        assertEquals(MaintenanceWindow.Status.CANCELLED, cancelled.status(clock.instant()));
        List<MaintenanceWindow> all = store.occurrences(T0, berlin("2027-01-01T00:00"));
        assertEquals(1, all.size());
        assertEquals(MaintenanceWindow.Status.IN_PROGRESS, all.get(0).status(clock.instant()));
        assertThrows(MaintenanceStore.InvalidWindowException.class,
                () -> store.skipOccurrence(s.id(), 2, LocalDate.parse("2026-11-22"), SIGGI));
        clock.set(berlin("2026-11-30T00:00"));
        assertEquals(List.of(), store.materialise());
    }

    @Test
    void seriesValidation() throws Exception {
        MaintenanceStore store = store();
        Exception overlap = assertThrows(MaintenanceStore.InvalidWindowException.class,
                () -> store.create(series("Daily", berlin("2026-11-15T04:00"), berlin("2026-11-16T05:00"),
                        Recurrence.daily(null)), SIGGI));
        assertEquals(List.of("end: occurrences of this series can be at most 1 day long, or they would overlap"),
                errors(overlap));
        Exception until = assertThrows(MaintenanceStore.InvalidWindowException.class,
                () -> store.create(series("Daily", berlin("2026-11-15T04:00"), berlin("2026-11-15T05:00"),
                        Recurrence.daily(LocalDate.parse("2026-11-14"))), SIGGI));
        assertEquals(List.of("recurrence: the end date can't be before the start"), errors(until));
        Exception none = assertThrows(MaintenanceStore.InvalidWindowException.class,
                () -> store.create(series("Monthly", berlin("2026-11-15T04:00"), berlin("2026-11-15T05:00"),
                        Recurrence.monthlyOnDay(31, LocalDate.parse("2026-11-30"))), SIGGI));
        assertEquals(List.of("recurrence: no occurrence before the end date"), errors(none));
        Exception noDays = assertThrows(MaintenanceStore.InvalidWindowException.class,
                () -> store.create(series("Weekly", berlin("2026-11-15T04:00"), berlin("2026-11-15T05:00"),
                        Recurrence.weekly(List.of(), null)), SIGGI));
        assertEquals(List.of("recurrence: choose at least one weekday"), errors(noDays));

        MaintenanceWindow oneOff = store.create(series("Once", berlin("2026-11-15T04:00"),
                berlin("2026-11-15T05:00"), null), SIGGI);
        Exception toSeries = assertThrows(MaintenanceStore.InvalidWindowException.class,
                () -> store.update(oneOff.id(), 1, series("Once", oneOff.start(), oneOff.end(),
                        Recurrence.daily(null)), SIGGI));
        assertEquals(List.of("recurrence: a window can't be turned into a series after it was created"),
                errors(toSeries));
        MaintenanceWindow s = weeklyReboot(store);
        Exception toOneOff = assertThrows(MaintenanceStore.InvalidWindowException.class,
                () -> store.update(s.id(), 1, series("x", s.start(), s.end(), null), SIGGI));
        assertTrue(errors(toOneOff).get(0).startsWith("recurrence: a series can't be turned into a single window"));
        assertThrows(MaintenanceStore.InvalidWindowException.class,
                () -> store.skipOccurrence(oneOff.id(), 1, LocalDate.parse("2026-11-15"), SIGGI));
        assertEquals(2, store.windows().size());
    }
}
