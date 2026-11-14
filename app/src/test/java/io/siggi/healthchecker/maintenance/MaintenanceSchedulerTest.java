package io.siggi.healthchecker.maintenance;

import io.siggi.healthchecker.api.CheckResult;
import io.siggi.healthchecker.audit.AuditLog;
import io.siggi.healthchecker.config.MaintenanceSettings;
import io.siggi.healthchecker.history.HistoryEvent;
import io.siggi.healthchecker.history.HistoryStore;
import io.siggi.healthchecker.history.Uptime;
import io.siggi.healthchecker.history.UptimeCalculator;
import io.siggi.healthchecker.status.CheckRecord;
import io.siggi.healthchecker.status.EndpointStatus;
import io.siggi.healthchecker.status.State;
import io.siggi.healthchecker.status.StatusRegistry;
import org.junit.jupiter.api.AfterEach;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaintenanceSchedulerTest {
    private static final Instant T0 = Instant.parse("2026-11-14T12:00:00Z");
    private static final MaintenanceStore.Actor SIGGI = new MaintenanceStore.Actor("siggi", "Siggi");
    private static final System.Logger QUIET = new System.Logger() {
        @Override
        public String getName() {
            return "quiet";
        }

        @Override
        public boolean isLoggable(Level level) {
            return false;
        }

        @Override
        public void log(Level level, java.util.ResourceBundle bundle, String msg, Throwable thrown) {
        }

        @Override
        public void log(Level level, java.util.ResourceBundle bundle, String format, Object... params) {
        }
    };

    private static final MaintenanceStore.References REFS = new MaintenanceStore.References() {
        @Override
        public boolean endpointExists(String id) {
            return Set.of("website", "survival", "creative").contains(id);
        }

        @Override
        public boolean categoryExists(String id) {
            return Set.of("web", "minecraft").contains(id);
        }

        @Override
        public boolean channelExists(String name) {
            return false;
        }
    };

    @TempDir
    Path dir;

    private final MutableClock clock = new MutableClock(T0);
    private final Map<String, String> categories = new HashMap<>();
    private AuditLog audit;
    private MaintenanceStore store;
    private StatusRegistry status;
    private HistoryStore history;
    private MaintenanceScheduler scheduler;

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void set(Instant instant) {
            now = instant;
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

    @BeforeEach
    void setUp() throws Exception {
        categories.put("website", "web");
        categories.put("survival", "minecraft");
        categories.put("creative", "minecraft");
        audit = new AuditLog(dir.resolve(AuditLog.FILE_NAME), clock);
        store = new MaintenanceStore(dir, new MaintenanceSettings(false, 7, 7), REFS, audit, clock);
        store.load();
        status = new StatusRegistry(QUIET);
        categories.keySet().forEach(id -> status.register(id, 1));
        history = HistoryStore.open(dir, clock, QUIET);
        scheduler = new MaintenanceScheduler(store, status, history, () -> new HashMap<>(categories), clock, QUIET);
    }

    @AfterEach
    void tearDown() {
        scheduler.close();
    }

    private static Instant at(int minutes) {
        return T0.plus(Duration.ofMinutes(minutes));
    }

    /** A window from minute {@code from} to minute {@code to}, created at T0. */
    private MaintenanceWindow window(int from, int to, List<String> endpoints, List<String> categories)
            throws Exception {
        clock.set(T0);
        return store.create(new MaintenanceStore.Draft("Reboot", null, at(from), at(to), endpoints, categories,
                List.of()), SIGGI);
    }

    /** Sets the clock to minute {@code minute} and records a check (as the check scheduler would). */
    private void check(String id, int minute, boolean success) {
        clock.set(at(minute));
        CheckRecord check = new CheckRecord(at(minute), Duration.ZERO,
                success ? CheckResult.ok("status 200") : CheckResult.fail("status 503"));
        history.record(id, check, status.record(id, check).orElse(null));
    }

    private void tickAt(int minute) {
        clock.set(at(minute));
        scheduler.tick();
    }

    private EndpointStatus statusOf(String id) {
        return status.status(id).orElseThrow();
    }

    @Test
    void startAndEndTransitions() throws Exception {
        MaintenanceWindow w = window(10, 20, List.of("website"), List.of("minecraft"));
        check("website", 1, true);
        check("survival", 1, true);

        tickAt(5);
        assertEquals(State.UP, statusOf("website").state());
        assertTrue(scheduler.windowOf("website").isEmpty());

        tickAt(10);
        for (String id : List.of("website", "survival", "creative")) {
            EndpointStatus s = statusOf(id);
            assertEquals(State.MAINTENANCE, s.state(), id);
            assertEquals(w.id(), s.maintenanceId(), id);
            assertEquals(at(10), s.since(), id);
            assertEquals(w.id(), scheduler.windowOf(id).orElseThrow().id());
        }
        assertEquals(State.UP, statusOf("website").checkState());
        assertEquals(State.UNKNOWN, statusOf("creative").checkState());

        // Checks keep running and are recorded underneath.
        check("website", 15, true);
        assertEquals(State.MAINTENANCE, statusOf("website").state());
        assertEquals(at(15), statusOf("website").lastCheck().time());

        tickAt(20);
        assertEquals(State.UP, statusOf("website").state());
        assertNull(statusOf("website").maintenanceId());
        assertEquals(State.UNKNOWN, statusOf("creative").state());
        assertTrue(scheduler.windowOf("survival").isEmpty());
    }

    @Test
    void lateTickStillUsesWindowTimes() throws Exception {
        window(10, 20, List.of("website"), List.of());
        tickAt(12);
        assertEquals(at(10), statusOf("website").since());
        tickAt(25);
        assertFalse(statusOf("website").inMaintenance());
        assertTrue(history.maintenanceWindow("website").isEmpty());
    }

    @Test
    void categoryMembershipFollowsLayout() throws Exception {
        window(10, 20, List.of(), List.of("web"));
        tickAt(10);
        assertTrue(statusOf("website").inMaintenance());
        assertFalse(statusOf("survival").inMaintenance());
        // Moved into the category during the window.
        categories.put("survival", "web");
        tickAt(11);
        assertTrue(statusOf("survival").inMaintenance());
        assertEquals(at(11), statusOf("survival").since());
        categories.put("website", null);
        tickAt(12);
        assertFalse(statusOf("website").inMaintenance());
    }

    @Test
    void endNow() throws Exception {
        MaintenanceWindow w = window(10, 60, List.of("website"), List.of());
        tickAt(10);
        assertTrue(statusOf("website").inMaintenance());

        clock.set(at(25));
        MaintenanceWindow ended = scheduler.endNow(w.id(), w.version(), SIGGI).orElseThrow();
        assertEquals(at(25), ended.end());
        assertEquals(w.version() + 1, ended.version());
        assertEquals(MaintenanceWindow.Status.COMPLETED, ended.status(at(25)));
        assertFalse(statusOf("website").inMaintenance());
        assertEquals(at(25), store.window(w.id()).orElseThrow().end());
        assertTrue(Files.readString(audit.file(), StandardCharsets.UTF_8).contains("\"maintenance.end\""));

        // Stale version, and only in progress.
        assertThrows(MaintenanceStore.VersionConflictException.class,
                () -> scheduler.endNow(w.id(), w.version(), SIGGI));
        var e = assertThrows(MaintenanceStore.InvalidWindowException.class,
                () -> scheduler.endNow(w.id(), ended.version(), SIGGI));
        assertEquals(List.of("the window has ended"), e.errors());
        MaintenanceWindow later = window(100, 120, List.of("website"), List.of());
        clock.set(at(30));
        e = assertThrows(MaintenanceStore.InvalidWindowException.class,
                () -> scheduler.endNow(later.id(), later.version(), SIGGI));
        assertEquals(List.of("the window hasn't started yet"), e.errors());
    }

    @Test
    void extend() throws Exception {
        MaintenanceWindow w = window(10, 20, List.of("website"), List.of());
        tickAt(15);
        MaintenanceWindow extended = scheduler.extend(w.id(), w.version(), at(40), SIGGI).orElseThrow();
        assertEquals(at(40), extended.end());
        assertTrue(Files.readString(audit.file(), StandardCharsets.UTF_8).contains("\"maintenance.extend\""));

        tickAt(25);
        assertTrue(statusOf("website").inMaintenance());
        tickAt(40);
        assertFalse(statusOf("website").inMaintenance());

        MaintenanceWindow w2 = window(50, 60, List.of("website"), List.of());
        clock.set(at(55));
        var e = assertThrows(MaintenanceStore.InvalidWindowException.class,
                () -> scheduler.extend(w2.id(), w2.version(), at(58), SIGGI));
        assertEquals(List.of("end: must be later than the current end"), e.errors());
        e = assertThrows(MaintenanceStore.InvalidWindowException.class,
                () -> scheduler.extend(w2.id(), w2.version(), at(50 + 7 * 24 * 60 + 1), SIGGI));
        assertEquals(List.of("end: a window can be at most 7 days long"), e.errors());
    }

    @Test
    void downInsideWindowIsMaintenanceEvent() throws Exception {
        MaintenanceWindow w = window(10, 30, List.of("website"), List.of());
        check("website", 5, true);
        tickAt(10);
        check("website", 12, false);
        check("website", 14, false);
        check("website", 18, true);
        tickAt(30);

        List<HistoryEvent> events = history.events("website");
        assertEquals(1, events.size());
        HistoryEvent e = events.getFirst();
        assertEquals(HistoryEvent.Kind.MAINTENANCE, e.kind());
        assertEquals(w.id(), e.maintenanceId());
        assertEquals(at(12), e.start());
        assertEquals(at(18), e.end());
        assertTrue(history.events("website").stream().noneMatch(ev -> ev.kind() == HistoryEvent.Kind.OUTAGE));
    }

    @Test
    void downPastWindowEndBecomesOutageAtWindowEnd() throws Exception {
        MaintenanceWindow w = window(10, 30, List.of("website"), List.of());
        check("website", 5, true);
        tickAt(10);
        check("website", 20, false);
        check("website", 29, false);
        tickAt(30);
        check("website", 35, false);
        check("website", 40, true);

        List<HistoryEvent> events = history.events("website");
        assertEquals(2, events.size());
        HistoryEvent planned = events.get(0);
        assertEquals(HistoryEvent.Kind.MAINTENANCE, planned.kind());
        assertEquals(w.id(), planned.maintenanceId());
        assertEquals(at(20), planned.start());
        assertEquals(at(30), planned.end());
        assertFalse(planned.endedUnobserved());
        HistoryEvent outage = events.get(1);
        assertEquals(HistoryEvent.Kind.OUTAGE, outage.kind());
        assertNull(outage.maintenanceId());
        assertEquals(at(30), outage.start());
        assertEquals(at(40), outage.end());
        assertEquals("status 503", outage.firstFailureMessage());

        // Persisted: a reload sees the same events.
        assertEquals(events, HistoryStore.open(dir, clock, QUIET).events("website"));
    }

    @Test
    void endNowWhileDownStartsOutageThen() throws Exception {
        MaintenanceWindow w = window(10, 60, List.of("website"), List.of());
        check("website", 5, true);
        tickAt(10);
        check("website", 15, false);
        clock.set(at(20));
        scheduler.endNow(w.id(), w.version(), SIGGI);
        assertEquals(State.DOWN, statusOf("website").state());
        HistoryEvent open = history.openEvent("website").orElseThrow();
        assertEquals(HistoryEvent.Kind.OUTAGE, open.kind());
        assertEquals(at(20), open.start());
    }

    @Test
    void outageOngoingAtWindowStartBecomesMaintenance() throws Exception {
        MaintenanceWindow w = window(10, 30, List.of("website"), List.of());
        check("website", 5, false);
        tickAt(10);
        check("website", 20, true);
        List<HistoryEvent> events = history.events("website");
        assertEquals(2, events.size());
        assertEquals(HistoryEvent.Kind.OUTAGE, events.get(0).kind());
        assertEquals(at(5), events.get(0).start());
        assertEquals(at(10), events.get(0).end());
        assertEquals(HistoryEvent.Kind.MAINTENANCE, events.get(1).kind());
        assertEquals(w.id(), events.get(1).maintenanceId());
        assertEquals(at(10), events.get(1).start());
        assertEquals(at(20), events.get(1).end());
    }

    @Test
    void downRecordedLateAfterWindowEndIsReclassified() throws Exception {
        window(10, 30, List.of("website"), List.of());
        check("website", 5, true);
        tickAt(10);
        // A failure at minute 31 recorded before the scheduler noticed the end.
        check("website", 31, false);
        assertEquals(HistoryEvent.Kind.MAINTENANCE, history.openEvent("website").orElseThrow().kind());
        tickAt(31);
        List<HistoryEvent> events = history.events("website");
        assertEquals(1, events.size());
        assertEquals(HistoryEvent.Kind.OUTAGE, events.getFirst().kind());
        assertEquals(at(31), events.getFirst().start());
    }

    @Test
    void streakStartingBeforeWindowEndIsSplit() throws Exception {
        MaintenanceWindow w = window(10, 30, List.of("website"), List.of());
        status.register("website", 3);
        check("website", 5, true);
        tickAt(10);
        check("website", 28, false);
        check("website", 29, false);
        tickAt(30);
        check("website", 31, false); // third failure: DOWN, streak since minute 28
        List<HistoryEvent> events = history.events("website");
        assertEquals(2, events.size());
        assertEquals(HistoryEvent.Kind.MAINTENANCE, events.get(0).kind());
        assertEquals(w.id(), events.get(0).maintenanceId());
        assertEquals(at(28), events.get(0).start());
        assertEquals(at(30), events.get(0).end());
        assertEquals(HistoryEvent.Kind.OUTAGE, events.get(1).kind());
        assertEquals(at(30), events.get(1).start());
        assertTrue(events.get(1).ongoing());
    }

    @Test
    void uptimeExcludesMaintenanceUnlessCountsAsDowntime() throws Exception {
        window(10, 30, List.of("website"), List.of());
        check("website", 0, true);
        tickAt(10);
        check("website", 20, false);
        tickAt(30);
        check("website", 40, true);
        check("website", 60, true);
        // 60 minutes covered: 10 minutes of maintenance downtime (20–30), 10 minutes of outage (30–40).
        Instant now = at(60);
        try (UptimeCalculator excluded = new UptimeCalculator(history, ZoneOffset.UTC, false, clock);
             UptimeCalculator counted = new UptimeCalculator(history, ZoneOffset.UTC, true, clock)) {
            Uptime u = excluded.window("website", T0, now);
            assertEquals(Duration.ofMinutes(60), u.covered());
            assertEquals(Duration.ofMinutes(10), u.downtime());
            Uptime c = counted.window("website", T0, now);
            assertEquals(Duration.ofMinutes(20), c.downtime());
            assertEquals(1, excluded.day("website", T0.atZone(ZoneOffset.UTC).toLocalDate()).maintenanceEvents());
        }
    }

    @Test
    void recurringOccurrenceIsAppliedAndMaterialised() throws Exception {
        clock.set(T0);
        MaintenanceWindow series = store.create(new MaintenanceStore.Draft("Daily reboot", null, at(10), at(20),
                List.of("website"), List.of(), List.of(), Recurrence.daily(null)), SIGGI);
        tickAt(15);
        EndpointStatus s = statusOf("website");
        assertTrue(s.inMaintenance());
        String occurrenceId = MaintenanceStore.occurrenceId(series.id(), T0.atZone(ZoneOffset.UTC).toLocalDate());
        assertEquals(occurrenceId, s.maintenanceId());
        assertTrue(store.window(occurrenceId).isPresent());
        tickAt(20);
        assertFalse(statusOf("website").inMaintenance());
    }

    @Test
    void startedSchedulerAppliesStoreChanges() throws Exception {
        MaintenanceWindow w = window(10, 60, List.of("website"), List.of());
        clock.set(at(15));
        scheduler.start();
        assertTrue(statusOf("website").inMaintenance());
        store.endNow(w.id(), w.version(), SIGGI); // straight to the store: applied by the change listener
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (statusOf("website").inMaintenance() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertFalse(statusOf("website").inMaintenance());
    }
}
