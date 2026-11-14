package io.siggi.healthchecker.history;

import io.siggi.healthchecker.api.CheckResult;
import io.siggi.healthchecker.status.CheckRecord;
import io.siggi.healthchecker.status.StateChange;
import io.siggi.healthchecker.status.StatusRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.System.Logger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.ResourceBundle;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HistoryStoreTest {
    private static final Instant T0 = Instant.parse("2026-03-01T12:00:00Z");
    private static final Logger QUIET = new QuietLogger();

    @TempDir
    Path dataDir;

    private final MutableClock clock = new MutableClock(T0);
    private final StatusRegistry status = new StatusRegistry(QUIET);

    private HistoryStore open() throws IOException {
        return HistoryStore.open(dataDir, clock, QUIET);
    }

    /** Runs a check at {@code T0 + seconds} through the status registry and the store. */
    private void check(HistoryStore store, String id, long seconds, boolean success) {
        Instant time = T0.plusSeconds(seconds);
        clock.set(time.plusMillis(10));
        CheckResult result = success ? CheckResult.ok("ok") : CheckResult.fail("fail at " + seconds);
        CheckRecord record = new CheckRecord(time, Duration.ofMillis(10), result);
        StateChange change = status.record(id, record).orElse(null);
        store.record(id, record, change);
    }

    private static CoveragePeriod period(long fromSeconds, Instant end) {
        return new CoveragePeriod(T0.plusSeconds(fromSeconds), end);
    }

    @Test
    void emptyWhenNothingStored() throws IOException {
        HistoryStore store = open();
        assertTrue(store.endpointIds().isEmpty());
        assertTrue(store.events("a").isEmpty());
        assertTrue(store.coverage("a").isEmpty());
        assertFalse(Files.exists(dataDir.resolve("endpoints")));
    }

    @Test
    void eventStartsAtFirstFailureOfStreak() throws IOException {
        status.register("a", 3);
        HistoryStore store = open();
        check(store, "a", 0, true);
        check(store, "a", 60, false);
        check(store, "a", 120, false);
        assertTrue(store.events("a").isEmpty());
        check(store, "a", 180, false);
        HistoryEvent e = store.openEvent("a").orElseThrow();
        assertEquals(T0.plusSeconds(60), e.start());
        assertNull(e.end());
        assertEquals("fail at 60", e.firstFailureMessage());
        assertEquals("fail at 180", e.lastFailureMessage());
        check(store, "a", 240, false);
        check(store, "a", 300, true);
        e = store.events("a").getFirst();
        assertEquals(T0.plusSeconds(60), e.start());
        assertEquals(T0.plusSeconds(300), e.end());
        assertEquals("fail at 240", e.lastFailureMessage());
        assertEquals(T0.plusSeconds(240), e.lastFailureAt());
        assertFalse(e.endedUnobserved());
        assertEquals(Duration.ofSeconds(240), e.duration(T0.plusSeconds(1000)));
        assertTrue(store.openEvent("a").isEmpty());
    }

    @Test
    void writeReadRoundTrip() throws IOException {
        status.register("a", 1);
        status.register("b", 1);
        List<HistoryEvent> listened = new ArrayList<>();
        HistoryStore store = open();
        store.addListener(listened::add);
        check(store, "a", 0, true);
        check(store, "b", 0, false);
        check(store, "a", 60, false);
        check(store, "a", 120, true);
        check(store, "b", 120, true);
        // An event in the next year goes to its own file.
        long nextYear = Duration.between(T0, Instant.parse("2027-01-01T00:00:30Z")).toSeconds();
        check(store, "a", nextYear, false);
        check(store, "a", nextYear + 60, true);
        clock.set(T0.plusSeconds(nextYear + 90));
        store.close();
        assertEquals(6, listened.size());

        assertTrue(Files.isRegularFile(dataDir.resolve("endpoints/a/coverage.json")));
        assertTrue(Files.isRegularFile(dataDir.resolve("endpoints/a/events/2026.jsonl")));
        assertTrue(Files.isRegularFile(dataDir.resolve("endpoints/a/events/2027.jsonl")));
        assertTrue(Files.isRegularFile(dataDir.resolve("endpoints/b/events/2026.jsonl")));

        HistoryStore reloaded = open();
        assertEquals(store.endpointIds(), reloaded.endpointIds());
        for (String id : List.of("a", "b")) {
            assertEquals(store.events(id), reloaded.events(id), id);
            assertEquals(store.coverage(id), reloaded.coverage(id), id);
        }
        assertEquals(2, reloaded.events("a").size());
        assertEquals(List.of(period(0, T0.plusSeconds(nextYear + 90))), reloaded.coverage("a"));
        assertFalse(reloaded.monitored("a"));
    }

    @Test
    void codecRoundTripsEveryField() throws Exception {
        HistoryEvent e = new HistoryEvent("e1", "a", HistoryEvent.Kind.MAINTENANCE, T0, T0.plusSeconds(5),
                "Custom \"title\"\nline", "first", "last", T0.plusSeconds(4),
                List.of(new EventNote("n1", "Siggi", T0, T0.plusSeconds(9), "line 1\nline 2 <b> ",
                                EventNote.Visibility.PRIVATE),
                        new EventNote("n2", "Other", T0, null, "public", EventNote.Visibility.PUBLIC)),
                true, true, "m1");
        String line = HistoryCodec.encodeEvent(e);
        assertFalse(line.contains("\n"));
        assertEquals(e, HistoryCodec.decodeEvent(line));
    }

    @Test
    void restartWhileDownClosesEventAsEndedUnobserved() throws IOException {
        status.register("a", 1);
        HistoryStore store = open();
        check(store, "a", 0, true);
        check(store, "a", 60, false);
        check(store, "a", 120, false);
        clock.set(T0.plusSeconds(150));
        store.heartbeat();
        check(store, "a", 180, false); // not written: the app dies before the next heartbeat
        // Crash: no close(). Restart five minutes later.
        clock.set(T0.plusSeconds(480));

        HistoryStore restarted = open();
        HistoryEvent e = restarted.events("a").getFirst();
        assertEquals(T0.plusSeconds(60), e.start());
        assertEquals(T0.plusSeconds(120), e.end(), "closed at the last stored failed check");
        assertTrue(e.endedUnobserved());
        assertEquals("fail at 120", e.lastFailureMessage());
        assertTrue(restarted.openEvent("a").isEmpty());

        // Still down after the restart: a new event starts.
        StatusRegistry fresh = new StatusRegistry(QUIET);
        fresh.register("a", 1);
        CheckRecord failed = new CheckRecord(T0.plusSeconds(500), Duration.ZERO, CheckResult.fail("still down"));
        restarted.record("a", failed, fresh.record("a", failed).orElseThrow());
        assertEquals(2, restarted.events("a").size());
        assertEquals(T0.plusSeconds(500), restarted.openEvent("a").orElseThrow().start());

        // The closed event was written back, so a third start sees the same thing.
        HistoryEvent again = open().events("a").getFirst();
        assertEquals(e, again);
    }

    @Test
    void restartWhileUpLeavesCoverageGap() throws IOException {
        status.register("a", 1);
        HistoryStore store = open();
        check(store, "a", 0, true);
        check(store, "a", 30, true);
        clock.set(T0.plusSeconds(60));
        store.heartbeat();
        check(store, "a", 90, true); // after the last heartbeat: lost in the crash
        // Crash, restart ten minutes later.
        clock.set(T0.plusSeconds(700));
        HistoryStore restarted = open();
        assertEquals(List.of(period(0, T0.plusSeconds(60))), restarted.coverage("a"));
        assertFalse(restarted.monitored("a"));

        check(restarted, "a", 720, true);
        clock.set(T0.plusSeconds(780));
        restarted.heartbeat();
        List<CoveragePeriod> expected = List.of(period(0, T0.plusSeconds(60)), period(720, T0.plusSeconds(780)));
        assertEquals(expected, restarted.coverage("a"));
        assertTrue(restarted.events("a").isEmpty());
        assertEquals(expected, open().coverage("a"));
    }

    @Test
    void cleanShutdownWritesFinalCoverage() throws IOException {
        status.register("a", 1);
        HistoryStore store = open();
        check(store, "a", 0, true);
        clock.set(T0.plusSeconds(45));
        store.close();
        assertEquals(List.of(period(0, T0.plusSeconds(45))), open().coverage("a"));
    }

    @Test
    void stopMonitoringEndsCoverageAndOpenEvent() throws IOException {
        status.register("a", 1);
        HistoryStore store = open();
        check(store, "a", 0, false);
        check(store, "a", 60, false);
        clock.set(T0.plusSeconds(90));
        store.stopMonitoring("a");
        assertFalse(store.monitored("a"));
        assertEquals(List.of(period(0, T0.plusSeconds(90))), store.coverage("a"));
        HistoryEvent e = store.events("a").getFirst();
        assertEquals(T0.plusSeconds(60), e.end());
        assertTrue(e.endedUnobserved());

        // Monitored again later: a new period after a gap.
        status.remove("a");
        status.register("a", 1);
        check(store, "a", 300, true);
        assertEquals(2, store.coverage("a").size());
        assertEquals(T0.plusSeconds(300), store.coverage("a").get(1).start());
    }

    @Test
    void stopMonitoringForAConfigChangeMarksTheEventEndedByConfigChange() throws IOException {
        status.register("a", 1);
        HistoryStore store = open();
        check(store, "a", 0, false);
        check(store, "a", 60, false);
        clock.set(T0.plusSeconds(90));
        store.stopMonitoring("a", true);
        HistoryEvent e = store.events("a").getFirst();
        assertEquals(T0.plusSeconds(60), e.end());
        assertTrue(e.endedByConfigChange());
        assertFalse(e.endedUnobserved());
        assertFalse(store.monitored("a"));
        HistoryEvent reloaded = open().events("a").getFirst();
        assertTrue(reloaded.endedByConfigChange());
        assertFalse(reloaded.endedUnobserved());
    }

    @Test
    void rememberedTargetsAccumulateAndSurviveRestart() throws IOException {
        HistoryStore store = open();
        store.rememberTargets("a", List.of("old.example.com", "25565"));
        store.rememberTargets("a", List.of("new.example.com", "25565", ""));
        assertEquals(Set.of("old.example.com", "new.example.com", "25565"), store.knownTargets("a"));
        assertEquals(Set.of(), store.knownTargets("b"));
        assertEquals(Set.of("old.example.com", "new.example.com", "25565"), open().knownTargets("a"));
    }

    @Test
    void failedAtomicWriteLeavesPreviousFileIntact(@TempDir Path dir) throws IOException {
        Path target = dir.resolve("file.json");
        AtomicFiles.write(target, "old content".getBytes(StandardCharsets.UTF_8));
        IOException e = assertThrows(IOException.class, () -> AtomicFiles.write(target, out -> {
            out.write("new con".getBytes(StandardCharsets.UTF_8));
            throw new IOException("disk on fire");
        }));
        assertEquals("disk on fire", e.getMessage());
        assertEquals("old content", Files.readString(target));
        try (Stream<Path> files = Files.list(dir)) {
            assertEquals(List.of(target), files.toList(), "temp file removed");
        }
    }

    @Test
    void crashMidWriteLeavesPreviousFileIntact() throws IOException {
        status.register("a", 1);
        HistoryStore store = open();
        check(store, "a", 0, false);
        check(store, "a", 60, true);
        clock.set(T0.plusSeconds(90));
        store.close();
        Path eventsFile = dataDir.resolve("endpoints/a/events/2026.jsonl");
        Path coverageFile = dataDir.resolve("endpoints/a/coverage.json");
        String eventsBefore = Files.readString(eventsFile);
        String coverageBefore = Files.readString(coverageFile);

        // The process "dies" halfway through writing a temp file: it's left behind, half written.
        HistoryStore.FileWriter crashing = (target, data) -> {
            Path temp = target.resolveSibling("." + target.getFileName() + ".crashed.tmp");
            Files.createDirectories(temp.getParent());
            try (OutputStream out = Files.newOutputStream(temp)) {
                out.write(data, 0, data.length / 2);
            }
            throw new IOException("killed");
        };
        HistoryStore dying = HistoryStore.open(dataDir, clock, QUIET, crashing);
        StatusRegistry fresh = new StatusRegistry(QUIET);
        fresh.register("a", 1);
        CheckRecord failed = new CheckRecord(T0.plusSeconds(600), Duration.ZERO, CheckResult.fail("down again"));
        dying.record("a", failed, fresh.record("a", failed).orElseThrow());
        clock.set(T0.plusSeconds(660));
        dying.heartbeat();
        assertTrue(Files.exists(eventsFile.resolveSibling(".2026.jsonl.crashed.tmp")));
        assertEquals(eventsBefore, Files.readString(eventsFile));
        assertEquals(coverageBefore, Files.readString(coverageFile));

        HistoryStore restarted = open();
        assertEquals(store.events("a"), restarted.events("a"));
        assertEquals(store.coverage("a"), restarted.coverage("a"));
        assertFalse(Files.exists(eventsFile.resolveSibling(".2026.jsonl.crashed.tmp")), "leftover temp file removed");
        assertFalse(Files.exists(coverageFile.resolveSibling(".coverage.json.crashed.tmp")));
    }

    @Test
    void failedWriteIsRetriedOnNextHeartbeat() throws IOException {
        boolean[] failing = {true};
        HistoryStore.FileWriter flaky = (target, data) -> {
            if (failing[0]) {
                throw new IOException("full");
            }
            AtomicFiles.write(target, data);
        };
        status.register("a", 1);
        HistoryStore store = HistoryStore.open(dataDir, clock, QUIET, flaky);
        check(store, "a", 0, false);
        assertFalse(Files.exists(dataDir.resolve("endpoints/a/events/2026.jsonl")));
        failing[0] = false;
        store.heartbeat();
        String id = store.openEvent("a").orElseThrow().id();
        assertTrue(Files.readString(dataDir.resolve("endpoints/a/events/2026.jsonl")).contains(id));
        assertEquals(store.coverage("a"), open().coverage("a"));
    }

    @Test
    void malformedFileIsReportedWithItsName() throws IOException {
        Path file = dataDir.resolve("endpoints/a/events/2026.jsonl");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"id\":\"x\",\"endpointId\":\"a\",\"kind\":\"outage\",\"start\":\"2026-01-01T00:00:00Z\"}\n"
                + "{not json\n");
        IOException e = assertThrows(IOException.class, this::open);
        assertTrue(e.getMessage().contains(file + ":2: invalid JSON"), e.getMessage());

        Files.writeString(file, "{\"id\":\"x\",\"endpointId\":\"a\",\"kind\":\"outage\",\"start\":\"2025-01-01T00:00:00Z\","
                + "\"end\":\"2025-01-01T00:01:00Z\"}\n");
        e = assertThrows(IOException.class, this::open);
        assertTrue(e.getMessage().contains("starts in 2025"), e.getMessage());

        Files.writeString(file, "");
        Files.writeString(dataDir.resolve("endpoints/a/coverage.json"), "[{\"start\":\"2026-01-01T00:00:00Z\"}]");
        e = assertThrows(IOException.class, this::open);
        assertTrue(e.getMessage().contains("coverage.json: [0]: end is missing"), e.getMessage());
    }

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

    private static final class QuietLogger implements Logger {
        @Override
        public String getName() {
            return "quiet";
        }

        @Override
        public boolean isLoggable(Level level) {
            return false;
        }

        @Override
        public void log(Level level, ResourceBundle bundle, String msg, Throwable thrown) {
        }

        @Override
        public void log(Level level, ResourceBundle bundle, String format, Object... params) {
        }

        @Override
        public void log(Level level, Supplier<String> msgSupplier) {
        }
    }
}
