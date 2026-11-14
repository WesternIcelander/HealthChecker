package io.siggi.healthchecker.status;

import io.siggi.healthchecker.api.CheckResult;
import org.junit.jupiter.api.Test;

import java.lang.System.Logger;
import java.text.MessageFormat;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatusRegistryTest {
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private final List<String> logLines = new ArrayList<>();
    private final StatusRegistry registry = new StatusRegistry(new CapturingLogger(logLines));
    private final List<StateChange> changes = new ArrayList<>();

    {
        registry.addListener(changes::add);
    }

    private static CheckRecord ok(int second) {
        return new CheckRecord(T0.plusSeconds(second), Duration.ofMillis(5), CheckResult.ok("ok " + second));
    }

    private static CheckRecord fail(int second) {
        return new CheckRecord(T0.plusSeconds(second), Duration.ofMillis(5), CheckResult.fail("fail " + second));
    }

    private EndpointStatus status(String id) {
        return registry.status(id).orElseThrow();
    }

    @Test
    void startsUnknown() {
        registry.register("a", 3);
        EndpointStatus s = status("a");
        assertEquals(State.UNKNOWN, s.state());
        assertNull(s.since());
        assertNull(s.lastCheck());
        assertTrue(s.recentChecks().isEmpty());
    }

    @Test
    void unknownToUpOnFirstSuccess() {
        registry.register("a", 3);
        Optional<StateChange> change = registry.record("a", ok(0));
        StateChange c = change.orElseThrow();
        assertEquals(State.UNKNOWN, c.from());
        assertEquals(State.UP, c.to());
        assertEquals(T0, c.time());
        assertNull(c.streakStart());
        assertEquals(0, c.failureCount());
        assertEquals(State.UP, status("a").state());
        assertEquals(T0, status("a").since());
        assertEquals(List.of(c), changes);
    }

    @Test
    void unknownToDownOnFirstFailureEvenWithThreshold() {
        registry.register("a", 3);
        StateChange c = registry.record("a", fail(0)).orElseThrow();
        assertEquals(State.UNKNOWN, c.from());
        assertEquals(State.DOWN, c.to());
        assertEquals(T0, c.streakStart());
        assertEquals("fail 0", c.firstFailureMessage());
        assertEquals(1, c.failureCount());
        assertEquals(State.DOWN, status("a").state());
    }

    @Test
    void thresholdOne() {
        registry.register("a", 1);
        registry.record("a", ok(0));
        StateChange down = registry.record("a", fail(10)).orElseThrow();
        assertEquals(State.UP, down.from());
        assertEquals(State.DOWN, down.to());
        assertEquals(T0.plusSeconds(10), down.streakStart());
        assertEquals(1, down.failureCount());

        assertTrue(registry.record("a", fail(20)).isEmpty());
        StateChange up = registry.record("a", ok(30)).orElseThrow();
        assertEquals(State.DOWN, up.from());
        assertEquals(State.UP, up.to());
        assertEquals(T0.plusSeconds(10), up.streakStart());
        assertEquals("fail 10", up.firstFailureMessage());
        assertEquals(2, up.failureCount());
        assertEquals(3, changes.size());
    }

    @Test
    void thresholdThree() {
        registry.register("a", 3);
        registry.record("a", ok(0));
        assertTrue(registry.record("a", fail(10)).isEmpty());
        assertTrue(registry.record("a", fail(20)).isEmpty());
        assertEquals(State.UP, status("a").state());
        assertEquals(2, status("a").failureCount());

        StateChange down = registry.record("a", fail(30)).orElseThrow();
        assertEquals(State.UP, down.from());
        assertEquals(State.DOWN, down.to());
        assertEquals(T0.plusSeconds(30), down.time());
        assertEquals(3, down.failureCount());
        assertEquals(T0.plusSeconds(30), status("a").since());

        StateChange up = registry.record("a", ok(40)).orElseThrow();
        assertEquals(State.UP, up.to());
        assertEquals(State.UP, status("a").state());
        assertEquals(0, status("a").failureCount());
    }

    @Test
    void successResetsStreakBelowThreshold() {
        registry.register("a", 3);
        registry.record("a", ok(0));
        registry.record("a", fail(10));
        registry.record("a", fail(20));
        assertTrue(registry.record("a", ok(30)).isEmpty());
        assertNull(status("a").streakStart());
        assertTrue(registry.record("a", fail(40)).isEmpty());
        assertTrue(registry.record("a", fail(50)).isEmpty());
        assertEquals(State.UP, status("a").state());
        StateChange down = registry.record("a", fail(60)).orElseThrow();
        assertEquals(T0.plusSeconds(40), down.streakStart());
        assertEquals(List.of(State.UP, State.DOWN), changes.stream().map(StateChange::to).toList());
    }

    @Test
    void streakStartIsFirstFailureOfStreak() {
        registry.register("a", 3);
        registry.record("a", ok(0));
        registry.record("a", fail(10));
        assertEquals(T0.plusSeconds(10), status("a").streakStart());
        assertEquals("fail 10", status("a").firstFailureMessage());
        registry.record("a", fail(20));
        StateChange down = registry.record("a", fail(30)).orElseThrow();
        assertEquals(T0.plusSeconds(10), down.streakStart());
        assertEquals("fail 10", down.firstFailureMessage());
        assertEquals("fail 30", down.check().message());

        // stays the outage start while down
        registry.record("a", fail(40));
        assertEquals(T0.plusSeconds(10), status("a").streakStart());
        StateChange up = registry.record("a", ok(50)).orElseThrow();
        assertEquals(T0.plusSeconds(10), up.streakStart());
        assertEquals(T0.plusSeconds(50), up.time());
        assertEquals(4, up.failureCount());
        assertNull(status("a").streakStart());
    }

    @Test
    void recentChecksCappedAtHundred() {
        registry.register("a", 1);
        for (int i = 0; i < 150; i++) {
            registry.record("a", i % 2 == 0 ? ok(i) : fail(i));
        }
        List<CheckRecord> recent = status("a").recentChecks();
        assertEquals(StatusRegistry.RECENT_CHECKS, recent.size());
        assertEquals(T0.plusSeconds(50), recent.get(0).time());
        assertEquals(T0.plusSeconds(149), recent.get(99).time());
        assertSame(recent.get(99), status("a").lastCheck());
        assertThrows(UnsupportedOperationException.class, () -> recent.add(ok(0)));
    }

    @Test
    void thresholdChangeKeepsStateAndAppliesToNextCheck() {
        registry.register("a", 5);
        registry.record("a", ok(0));
        registry.record("a", fail(10));
        registry.record("a", fail(20));
        registry.register("a", 2); // update in place
        assertEquals(State.UP, status("a").state());
        assertEquals(2, status("a").failureCount());
        assertEquals(2, status("a").failureThreshold());
        StateChange down = registry.record("a", fail(30)).orElseThrow();
        assertEquals(T0.plusSeconds(10), down.streakStart());
        registry.setFailureThreshold("a", 4);
        assertEquals(4, status("a").failureThreshold());
        assertThrows(IllegalArgumentException.class, () -> registry.setFailureThreshold("b", 1));
        assertThrows(IllegalArgumentException.class, () -> registry.register("a", 0));
    }

    @Test
    void removedEndpointIgnoresResultsAndRestartsUnknown() {
        registry.register("a", 1);
        registry.record("a", ok(0));
        registry.remove("a");
        assertTrue(registry.status("a").isEmpty());
        assertTrue(registry.record("a", fail(10)).isEmpty());
        assertTrue(registry.all().isEmpty());
        registry.register("a", 1);
        assertEquals(State.UNKNOWN, status("a").state());
        assertEquals(1, changes.size());
    }

    @Test
    void endpointsAreIndependent() {
        registry.register("a", 1);
        registry.register("b", 1);
        registry.record("a", ok(0));
        registry.record("b", fail(0));
        assertEquals(State.UP, status("a").state());
        assertEquals(State.DOWN, status("b").state());
        assertEquals(2, registry.all().size());
    }

    @Test
    void failingListenerDoesNotBreakOthers() {
        List<StateChange> second = new ArrayList<>();
        StatusRegistry r = new StatusRegistry(new CapturingLogger(logLines));
        r.addListener(c -> {
            throw new IllegalStateException("boom");
        });
        r.addListener(second::add);
        r.register("a", 1);
        r.record("a", ok(0));
        assertEquals(1, second.size());
        assertEquals(State.UP, r.status("a").orElseThrow().state());
        assertTrue(logLines.stream().anyMatch(l -> l.startsWith("ERROR") && l.contains("listener")));
    }

    @Test
    void transitionsAreLogged() {
        registry.register("a", 2);
        registry.record("a", ok(0));
        registry.record("a", fail(10));
        registry.record("a", fail(20));
        registry.record("a", ok(30));
        assertEquals(List.of(
                "INFO a: UNKNOWN -> UP (ok 0)",
                "WARNING a: UP -> DOWN after 2 consecutive failures since 2026-01-01T00:00:10Z (fail 20)",
                "INFO a: DOWN -> UP, down since 2026-01-01T00:00:10Z (ok 30)"
        ), logLines);
    }

    private record CapturingLogger(List<String> lines) implements Logger {
        @Override
        public String getName() {
            return "test";
        }

        @Override
        public boolean isLoggable(Level level) {
            return true;
        }

        @Override
        public void log(Level level, ResourceBundle bundle, String msg, Throwable thrown) {
            lines.add(level.getName() + " " + msg);
        }

        @Override
        public void log(Level level, ResourceBundle bundle, String format, Object... params) {
            lines.add(level.getName() + " " + (params == null || params.length == 0 ? format : MessageFormat.format(format, params)));
        }

        @Override
        public void log(Level level, Supplier<String> msgSupplier) {
            lines.add(level.getName() + " " + msgSupplier.get());
        }
    }
}
