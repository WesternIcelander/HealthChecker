package io.siggi.healthchecker.history;

import io.siggi.healthchecker.api.CheckResult;
import io.siggi.healthchecker.status.CheckRecord;
import io.siggi.healthchecker.status.State;
import io.siggi.healthchecker.status.StateChange;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.System.Logger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UptimeCalculatorTest {
    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final Logger QUIET = new QuietLogger();

    @TempDir
    Path dataDir;

    private final MutableClock clock = new MutableClock(Instant.parse("2026-01-20T12:00:00Z"));
    private final List<CoveragePeriod> coverage = new ArrayList<>();
    private final List<HistoryEvent> events = new ArrayList<>();

    private static Instant local(String dateTime) {
        return LocalDateTime.parse(dateTime).atZone(BERLIN).toInstant();
    }

    private void cover(String from, String to) {
        coverage.add(new CoveragePeriod(local(from), local(to)));
    }

    private HistoryEvent outage(String from, String to) {
        return event(HistoryEvent.Kind.OUTAGE, from, to);
    }

    private HistoryEvent event(HistoryEvent.Kind kind, String from, String to) {
        HistoryEvent e = new HistoryEvent(UUID.randomUUID().toString(), "a", kind, local(from), local(to), null,
                "fail", "fail", local(to), List.of(), false, false, null);
        events.add(e);
        return e;
    }

    /** Writes the fixture coverage and events and opens the store. */
    private HistoryStore store() throws IOException {
        Path dir = dataDir.resolve("endpoints/a");
        Files.createDirectories(dir.resolve("events"));
        Files.writeString(dir.resolve("coverage.json"), HistoryCodec.encodeCoverage(coverage), StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder();
        for (HistoryEvent e : events) {
            sb.append(HistoryCodec.encodeEvent(e)).append('\n');
        }
        Files.writeString(dir.resolve("events/2026.jsonl"), sb.toString(), StandardCharsets.UTF_8);
        return HistoryStore.open(dataDir, clock, QUIET);
    }

    private UptimeCalculator calculator(boolean maintenanceCounts) throws IOException {
        return new UptimeCalculator(store(), BERLIN, maintenanceCounts, clock);
    }

    @Test
    void eventAcrossMidnightIsSplitBetweenDays() throws IOException {
        cover("2026-01-10T00:00", "2026-01-13T00:00");
        outage("2026-01-10T23:00", "2026-01-11T01:30");
        UptimeCalculator calc = calculator(false);

        DaySummary d10 = calc.day("a", LocalDate.parse("2026-01-10"));
        assertEquals(Duration.ofHours(24), d10.uptime().covered());
        assertEquals(Duration.ofHours(1), d10.uptime().downtime());
        assertEquals(1, d10.events());

        DaySummary d11 = calc.day("a", LocalDate.parse("2026-01-11"));
        assertEquals(Duration.ofMinutes(90), d11.uptime().downtime());
        assertEquals(1, d11.events());
        assertEquals("93.75%", d11.uptime().format());

        DaySummary d12 = calc.day("a", LocalDate.parse("2026-01-12"));
        assertEquals(Duration.ZERO, d12.uptime().downtime());
        assertEquals(0, d12.events());
        assertEquals("100%", d12.uptime().format());
        assertEquals(100.0, d12.uptime().percent().orElseThrow());

        List<DaySummary> range = calc.days("a", LocalDate.parse("2026-01-10"), LocalDate.parse("2026-01-12"));
        assertEquals(List.of(d10, d11, d12), range);
    }

    @Test
    void dstTransitionDaysAre23And25Hours() throws IOException {
        cover("2026-03-28T00:00", "2026-03-31T00:00");
        cover("2026-10-24T00:00", "2026-10-27T00:00");
        outage("2026-03-29T10:00", "2026-03-29T11:00");
        outage("2026-10-25T10:00", "2026-10-25T11:00");
        clock.set(Instant.parse("2026-11-01T00:00:00Z"));
        UptimeCalculator calc = calculator(false);

        Uptime spring = calc.day("a", LocalDate.parse("2026-03-29")).uptime();
        assertEquals(Duration.ofHours(23), spring.covered());
        assertEquals(Duration.ofHours(1), spring.downtime());
        assertEquals(9565, spring.hundredths().orElseThrow()); // 1 - 1/23 = 95.652…%

        Uptime autumn = calc.day("a", LocalDate.parse("2026-10-25")).uptime();
        assertEquals(Duration.ofHours(25), autumn.covered());
        assertEquals(Duration.ofHours(1), autumn.downtime());
        assertEquals("96%", autumn.format());

        assertEquals(Duration.ofHours(24), calc.day("a", LocalDate.parse("2026-03-28")).uptime().covered());
        assertEquals(Duration.ofHours(24), calc.day("a", LocalDate.parse("2026-10-26")).uptime().covered());
    }

    @Test
    void daysWithoutCoverageHaveNoData() throws IOException {
        cover("2026-01-10T00:00", "2026-01-11T00:00");
        cover("2026-01-13T00:00", "2026-01-14T00:00");
        // An outage recorded during a gap (e.g. ended unobserved) doesn't count without coverage.
        outage("2026-01-11T12:00", "2026-01-11T13:00");
        UptimeCalculator calc = calculator(false);

        DaySummary gap = calc.day("a", LocalDate.parse("2026-01-11"));
        assertFalse(gap.uptime().hasData());
        assertTrue(gap.uptime().percent().isEmpty());
        assertTrue(gap.uptime().hundredths().isEmpty());
        assertEquals(Uptime.NO_DATA, gap.uptime().format());
        assertEquals(Duration.ZERO, gap.uptime().downtime());
        assertEquals(1, gap.events());

        assertFalse(calc.day("a", LocalDate.parse("2026-01-12")).uptime().hasData());
        assertTrue(calc.day("a", LocalDate.parse("2026-01-13")).uptime().hasData());
        // Before any history, in the future, and for unknown endpoints: no data.
        assertFalse(calc.day("a", LocalDate.parse("2025-12-01")).uptime().hasData());
        assertFalse(calc.day("a", LocalDate.parse("2026-02-01")).uptime().hasData());
        assertFalse(calc.day("nope", LocalDate.parse("2026-01-10")).uptime().hasData());
        assertFalse(calc.last24Hours("nope").hasData());
        assertEquals(Uptime.NO_DATA, calc.last60Days("nope").format());
    }

    @Test
    void partialCoverage() throws IOException {
        cover("2026-01-10T06:00", "2026-01-10T18:00");
        outage("2026-01-10T17:00", "2026-01-10T20:00"); // only 1 h of it is covered
        UptimeCalculator calc = calculator(false);

        Uptime u = calc.day("a", LocalDate.parse("2026-01-10")).uptime();
        assertEquals(Duration.ofHours(12), u.covered());
        assertEquals(Duration.ofHours(1), u.downtime());
        assertEquals(9166, u.hundredths().orElseThrow()); // 11/12 = 91.666…%
        assertEquals("91.66%", u.format());
    }

    @Test
    void overlappingEventsCountOnce() throws IOException {
        cover("2026-01-10T00:00", "2026-01-11T00:00");
        outage("2026-01-10T10:00", "2026-01-10T12:00");
        outage("2026-01-10T11:00", "2026-01-10T13:00");
        UptimeCalculator calc = calculator(false);
        DaySummary d = calc.day("a", LocalDate.parse("2026-01-10"));
        assertEquals(Duration.ofHours(3), d.uptime().downtime());
        assertEquals(2, d.events());
    }

    @Test
    void maintenanceCountsOnlyWhenConfigured() throws IOException {
        cover("2026-01-10T00:00", "2026-01-11T00:00");
        event(HistoryEvent.Kind.MAINTENANCE, "2026-01-10T02:00", "2026-01-10T08:00");
        outage("2026-01-10T12:00", "2026-01-10T13:12");
        LocalDate day = LocalDate.parse("2026-01-10");

        DaySummary excluded = calculator(false).day("a", day);
        assertEquals(Duration.ofMinutes(72), excluded.uptime().downtime());
        assertEquals(1, excluded.events());
        assertEquals(1, excluded.maintenanceEvents());
        assertEquals("95%", excluded.uptime().format());

        HistoryStore store = HistoryStore.open(dataDir, clock, QUIET);
        DaySummary included = new UptimeCalculator(store, BERLIN, true, clock).day("a", day);
        assertEquals(Duration.ofMinutes(432), included.uptime().downtime());
        assertEquals(2, included.events());
        assertEquals(0, included.maintenanceEvents());
        assertEquals("70%", included.uptime().format());
    }

    @Test
    void rollingWindowsWithOngoingOutageAndActiveCoverage() throws IOException {
        clock.set(Instant.parse("2026-01-20T12:00:00Z"));
        coverage.add(new CoveragePeriod(Instant.parse("2025-11-01T00:00:00Z"), Instant.parse("2026-01-20T10:00:00Z")));
        events.add(new HistoryEvent("old", "a", HistoryEvent.Kind.OUTAGE, Instant.parse("2026-01-10T00:00:00Z"),
                Instant.parse("2026-01-10T06:00:00Z"), null, "f", "f", Instant.parse("2026-01-10T06:00:00Z"),
                List.of(), false, false, null));
        HistoryStore store = store();
        UptimeCalculator calc = new UptimeCalculator(store, BERLIN, false, clock);

        // Not monitored: coverage ends at 10:00, so the last 24 h have 22 h of data.
        assertEquals(Duration.ofHours(22), calc.last24Hours("a").covered());
        assertEquals("100%", calc.last24Hours("a").format());
        Uptime sixty = calc.last60Days("a");
        assertEquals(Duration.ofDays(60).minusHours(2), sixty.covered());
        assertEquals(Duration.ofHours(6), sixty.downtime());

        // Monitoring resumes and the endpoint goes down; the outage and coverage run until now.
        Instant downAt = Instant.parse("2026-01-20T11:00:00Z");
        CheckRecord check = new CheckRecord(downAt, Duration.ZERO, CheckResult.fail("down"));
        store.record("a", check, new StateChange("a", State.UNKNOWN, State.DOWN, check, downAt, "down", 1));
        clock.set(Instant.parse("2026-01-20T11:30:00Z"));
        Uptime day = calc.last24Hours("a");
        assertEquals(Duration.ofHours(23), day.covered()); // 11:30 to 10:00, plus 11:00 to 11:30
        assertEquals(Duration.ofMinutes(30), day.downtime());

        assertEquals(calc.last24Hours("a"), calc.window("a", clock.instant().minus(Duration.ofHours(24)),
                clock.instant().plus(Duration.ofHours(5))));
        DaySummary today = calc.day("a", calc.today());
        assertEquals(1, today.events());
        assertEquals(Duration.ofMinutes(30), today.uptime().downtime());
    }

    @Test
    void neverRoundsUpTo100() {
        // 99.995 %
        Uptime u = new Uptime(Duration.ofSeconds(200_000), Duration.ofSeconds(10));
        assertEquals(9999, u.hundredths().orElseThrow());
        assertEquals(99.99, u.percent().orElseThrow());
        assertEquals("99.99%", u.format());

        // One nanosecond of downtime in 60 days.
        Uptime tiny = new Uptime(Duration.ofDays(60), Duration.ofNanos(1));
        assertEquals("99.99%", tiny.format());

        assertEquals("100%", new Uptime(Duration.ofDays(60), Duration.ZERO).format());
        assertEquals("0%", new Uptime(Duration.ofHours(1), Duration.ofHours(1)).format());
        assertEquals("99.5%", new Uptime(Duration.ofSeconds(1000), Duration.ofSeconds(5)).format());
        assertEquals("99.05%", new Uptime(Duration.ofSeconds(10000), Duration.ofSeconds(95)).format());
        assertEquals("98.99%", new Uptime(Duration.ofSeconds(10000), Duration.ofSeconds(100).plusNanos(1)).format());
    }

    @Test
    void neverRoundsUpTo100FromHistory() throws IOException {
        // 99.995 % over one day: 4.32 s of downtime.
        cover("2026-01-10T00:00", "2026-01-11T00:00");
        HistoryEvent e = new HistoryEvent("e", "a", HistoryEvent.Kind.OUTAGE, local("2026-01-10T12:00"),
                local("2026-01-10T12:00").plusMillis(4320), null, "f", "f", local("2026-01-10T12:00"),
                List.of(), false, false, null);
        events.add(e);
        Uptime u = calculator(false).day("a", LocalDate.parse("2026-01-10")).uptime();
        assertEquals("99.99%", u.format());
    }

    @Test
    void pastDaysAreCachedUntilAnEventChanges() throws IOException {
        cover("2026-01-10T00:00", "2026-01-12T00:00");
        HistoryStore store = store();
        UptimeCalculator calc = new UptimeCalculator(store, BERLIN, false, clock);
        LocalDate day = LocalDate.parse("2026-01-11");

        DaySummary first = calc.day("a", day);
        assertEquals("100%", first.uptime().format());
        long computed = calc.computations();
        assertEquals(first, calc.day("a", day));
        assertEquals(computed, calc.computations(), "second lookup should come from the cache");

        // An outage opens whose failure streak started on the cached day.
        Instant streakStart = local("2026-01-11T18:00");
        Instant now = clock.instant();
        CheckRecord check = new CheckRecord(now, Duration.ZERO, CheckResult.fail("down"));
        store.record("a", check, new StateChange("a", State.UP, State.DOWN, check, streakStart, "down", 100));

        DaySummary after = calc.day("a", day);
        assertEquals(computed + 1, calc.computations());
        assertEquals(Duration.ofHours(6), after.uptime().downtime());
        assertEquals("75%", after.uptime().format());
        assertEquals(1, after.events());

        // Closing it invalidates again.
        long before = calc.computations();
        calc.day("a", day);
        assertEquals(before, calc.computations());
        CheckRecord ok = new CheckRecord(now.plusSeconds(60), Duration.ZERO, CheckResult.ok("ok"));
        store.record("a", ok, new StateChange("a", State.DOWN, State.UP, ok, streakStart, "down", 101));
        calc.day("a", day);
        assertEquals(before + 1, calc.computations());

        // After close(), the calculator stops listening, but explicit invalidation still works.
        calc.close();
        calc.invalidate("a");
        calc.day("a", day);
        assertEquals(before + 2, calc.computations());
    }

    @Test
    void todayIsNotCached() throws IOException {
        clock.set(local("2026-01-12T00:05"));
        cover("2026-01-10T00:00", "2026-01-12T00:05");
        UptimeCalculator calc = calculator(false);
        LocalDate yesterday = LocalDate.parse("2026-01-11");
        LocalDate today = LocalDate.parse("2026-01-12");
        assertEquals(today, calc.today());

        calc.day("a", today);
        long n = calc.computations();
        calc.day("a", today);
        assertEquals(n + 1, calc.computations());
        // Yesterday ended less than the settle time ago, so it isn't cached yet either.
        calc.day("a", yesterday);
        calc.day("a", yesterday);
        assertEquals(n + 3, calc.computations());

        clock.set(local("2026-01-12T00:05").plus(UptimeCalculator.CACHE_SETTLE));
        calc.day("a", yesterday);
        calc.day("a", yesterday);
        assertEquals(n + 4, calc.computations());
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
