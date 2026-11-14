package io.siggi.healthchecker.schedule;

import com.google.gson.JsonObject;
import io.siggi.healthchecker.api.CheckPlugin;
import io.siggi.healthchecker.api.CheckResult;
import io.siggi.healthchecker.api.Checker;
import io.siggi.healthchecker.api.ConfigException;
import io.siggi.healthchecker.api.EndpointConfig;
import io.siggi.healthchecker.api.SettingSpec;
import io.siggi.healthchecker.config.EndpointDefinition;
import io.siggi.healthchecker.config.Visibility;
import io.siggi.healthchecker.plugin.PluginManager;
import io.siggi.healthchecker.status.CheckRecord;
import io.siggi.healthchecker.status.EndpointStatus;
import io.siggi.healthchecker.status.State;
import io.siggi.healthchecker.status.StatusRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.System.Logger;
import java.text.MessageFormat;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.ResourceBundle;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CheckSchedulerTest {
    /** One "second" of intervalSeconds in these tests. */
    private static final Duration SECOND = Duration.ofMillis(50);
    private static final Duration GRACE = Duration.ofMillis(50);

    private final FakePlugin plugin = new FakePlugin();
    private final List<String> warnings = new CopyOnWriteArrayList<>();
    private final StatusRegistry status = new StatusRegistry(new SilentLogger(new CopyOnWriteArrayList<>()));
    private PluginManager plugins;
    private EndpointRegistry registry;
    private CheckScheduler scheduler;

    @BeforeEach
    void setUp() throws Exception {
        plugins = PluginManager.load(List.of(plugin), null, getClass().getClassLoader());
        registry = new EndpointRegistry(new EndpointFactory(plugins));
        scheduler = new CheckScheduler(registry, status, Clock.systemUTC(), GRACE, Duration.ZERO, SECOND,
                new SilentLogger(warnings));
    }

    @AfterEach
    void tearDown() {
        plugin.release.countDown();
        scheduler.close();
        plugins.close();
    }

    // ---- helpers ----

    private static EndpointDefinition def(String id, String mode, int timeoutMillis) {
        return def(id, id, mode, timeoutMillis, 1, "");
    }

    private static EndpointDefinition def(String id, String name, String mode, int timeoutMillis,
                                          int failureThreshold, String tag) {
        JsonObject settings = new JsonObject();
        settings.addProperty("mode", mode);
        if (!tag.isEmpty()) settings.addProperty("tag", tag);
        return new EndpointDefinition(id, name, "fake", null, Visibility.PUBLIC, 1, timeoutMillis,
                failureThreshold, List.of(), List.of(30, 60, 120), 0, settings);
    }

    private static void await(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("timed out waiting for " + what);
            Thread.sleep(5);
        }
    }

    private EndpointStatus statusOf(String id) {
        return status.status(id).orElseThrow();
    }

    private List<String> messages(String id) {
        return status.status(id).map(s -> s.recentChecks().stream().map(CheckRecord::message).toList())
                .orElse(List.of());
    }

    private FakeChecker checker(String id) {
        return (FakeChecker) registry.get(id).orElseThrow().checker();
    }

    // ---- failures, timeouts, stuck checks ----

    @Test
    void throwingPluginIsRecordedAsFailureAndKeepsRunning() throws Exception {
        registry.add(def("throws", "throw", 1000));
        registry.add(def("error", "error", 1000));
        registry.add(def("null", "null", 1000));
        registry.add(def("good", "ok", 1000));
        scheduler.start();
        FakeChecker throwing = checker("throws");
        FakeChecker erroring = checker("error");
        await("several runs of the throwing check", () -> throwing.calls.get() >= 3 && erroring.calls.get() >= 3);

        assertEquals(State.DOWN, statusOf("throws").state());
        assertEquals("check failed (IllegalStateException)", statusOf("throws").lastCheck().message());
        assertEquals("check failed (StackOverflowError)", statusOf("error").lastCheck().message());
        // The exception text (which may name the target) never ends up in the result.
        assertTrue(messages("throws").stream().noneMatch(m -> m.contains("secret.example")));
        await("null result recorded", () -> !messages("null").isEmpty());
        assertEquals("check returned no result", statusOf("null").lastCheck().message());
        await("good endpoint up", () -> statusOf("good").state() == State.UP);
    }

    @Test
    void hangingCheckTimesOutIsInterruptedAndLaterRunsContinue() throws Exception {
        registry.add(def("hang", "hang", 100));
        scheduler.start();
        FakeChecker c = checker("hang");
        await("timeout recorded", () -> messages("hang").contains("timeout after 100 ms"));
        await("check interrupted", () -> c.interrupts.get() >= 1);
        await("next run started", () -> c.calls.get() >= 2);
        assertEquals(State.DOWN, statusOf("hang").state());
        CheckRecord timeout = statusOf("hang").recentChecks().get(0);
        assertTrue(timeout.latency().compareTo(Duration.ofMillis(100)) >= 0, "latency " + timeout.latency());
        assertTrue(messages("hang").stream().noneMatch(m -> m.startsWith("skipped")),
                "an interruptible check is never stuck: " + messages("hang"));
        assertEquals(1, c.maxConcurrent.get());
    }

    @Test
    void checkIgnoringInterruptsIsSkippedWhileStuck() throws Exception {
        registry.add(def("stuck", "stuck", 100));
        scheduler.start();
        FakeChecker c = checker("stuck");
        await("two skips after the timeout", () -> messages("stuck").stream()
                .filter("skipped, previous check still stuck"::equals).count() >= 2);
        assertEquals("timeout after 100 ms", messages("stuck").get(0));
        assertEquals(1, c.calls.get(), "no second check while the first is stuck");
        assertTrue(c.interrupts.get() >= 1, "the stuck check was interrupted");
        assertEquals(State.DOWN, statusOf("stuck").state());

        plugin.release.countDown();
        await("a new check after the stuck one returned", () -> c.calls.get() >= 2);
        assertEquals(1, c.maxConcurrent.get(), "never called concurrently");
        await("recovers", () -> statusOf("stuck").state() == State.UP);
        assertFalse(messages("stuck").contains("late"), "the stuck check's own result is discarded");
    }

    @Test
    void slowCheckWithinItsTimeoutIsNotCountedAsStuck() throws Exception {
        registry.add(def("slow", "slow", 400)); // takes 120 ms; interval is 50 ms
        scheduler.start();
        FakeChecker c = checker("slow");
        await("a few runs", () -> c.calls.get() >= 3);
        assertTrue(messages("slow").stream().allMatch("ok"::equals), messages("slow").toString());
        assertEquals(1, c.maxConcurrent.get());
    }

    // ---- registry changes while running ----

    @Test
    void endpointAddedWhileRunningIsChecked() throws Exception {
        registry.add(def("first", "ok", 1000));
        scheduler.start();
        await("first up", () -> statusOf("first").state() == State.UP);
        registry.add(def("second", "fail", 1000));
        await("second checked", () -> status.status("second").map(s -> s.state() == State.DOWN).orElse(false));
    }

    @Test
    void replacedEndpointClosesOldCheckerAndStartsOverAtUnknown() throws Exception {
        registry.add(def("svc", "fail", 1000));
        scheduler.start();
        FakeChecker old = checker("svc");
        await("down", () -> statusOf("svc").state() == State.DOWN);

        assertEquals(EndpointRegistry.UpdateKind.REPLACED, registry.update(def("svc", "ok", 1000)));
        FakeChecker now = checker("svc");
        assertNotSame(old, now);
        await("old checker closed", () -> old.closed.get());
        int oldCalls = old.calls.get();
        await("new checker up", () -> statusOf("svc").state() == State.UP);
        // State started over: nothing recorded from the old checker survives.
        assertTrue(messages("svc").stream().allMatch("ok"::equals), messages("svc").toString());
        Thread.sleep(SECOND.toMillis() * 4);
        assertEquals(oldCalls, old.calls.get(), "no stray runs of the old checker");
    }

    @Test
    void replacedWhileCheckInFlightClosesAfterItReturnsAndDiscardsItsResult() throws Exception {
        registry.add(def("svc", "block", 5000));
        scheduler.start();
        FakeChecker old = checker("svc");
        await("check in flight", () -> old.active.get() == 1);

        registry.update(def("svc", "svc", "ok", 5000, 1, "v2"));
        await("old checker closed", () -> old.closed.get());
        assertFalse(old.closedWhileChecking.get(), "closed only after its check returned");
        assertTrue(old.interrupts.get() >= 1);
        await("new checker up", () -> statusOf("svc").state() == State.UP);
        Thread.sleep(SECOND.toMillis() * 4);
        assertEquals(1, old.calls.get(), "no stray runs of the old checker");
        assertFalse(messages("svc").contains("late"), "the in-flight result is discarded");
    }

    @Test
    void replacedWhileStuckClosesOldCheckerAfterGrace() throws Exception {
        registry.add(def("svc", "stuck", 5000));
        scheduler.start();
        FakeChecker old = checker("svc");
        await("check in flight", () -> old.active.get() == 1);

        registry.update(def("svc", "svc", "ok", 5000, 1, "v2"));
        await("old checker force-closed", () -> old.closed.get());
        assertTrue(old.closedWhileChecking.get());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("closing its checker anyway")), warnings.toString());
        await("new checker up", () -> statusOf("svc").state() == State.UP);

        plugin.release.countDown();
        await("stuck check returned", () -> old.active.get() == 0);
        Thread.sleep(SECOND.toMillis() * 4);
        assertEquals(1, old.calls.get());
        assertFalse(messages("svc").contains("late"));
    }

    @Test
    void removedWhileCheckInFlightStopsAndClosesChecker() throws Exception {
        registry.add(def("gone", "block", 5000));
        registry.add(def("stays", "ok", 1000));
        scheduler.start();
        FakeChecker old = checker("gone");
        await("check in flight", () -> old.active.get() == 1);

        assertTrue(registry.remove("gone"));
        await("checker closed", () -> old.closed.get());
        assertTrue(status.status("gone").isEmpty());
        Thread.sleep(SECOND.toMillis() * 4);
        assertEquals(1, old.calls.get(), "no stray runs");
        assertTrue(status.status("gone").isEmpty(), "no late result recorded");
        assertEquals(State.UP, statusOf("stays").state());
        assertFalse(checker("stays").closed.get());
    }

    @Test
    void removedIdleEndpointClosesCheckerAtOnce() throws Exception {
        registry.add(def("gone", "ok", 1000));
        scheduler.start();
        FakeChecker old = checker("gone");
        await("checked", () -> statusOf("gone").state() == State.UP);
        registry.remove("gone");
        await("closed", () -> old.closed.get());
        int calls = old.calls.get();
        Thread.sleep(SECOND.toMillis() * 4);
        assertEquals(calls, old.calls.get());
    }

    @Test
    void inPlaceUpdateKeepsCheckerScheduleAndState() throws Exception {
        registry.add(def("svc", "Service", "fail", 1000, 1, ""));
        scheduler.start();
        FakeChecker c = checker("svc");
        await("down", () -> statusOf("svc").state() == State.DOWN);
        EndpointStatus before = statusOf("svc");

        assertEquals(EndpointRegistry.UpdateKind.IN_PLACE,
                registry.update(def("svc", "Renamed", "fail", 1000, 1, "")));
        assertSame(c, checker("svc"));
        assertEquals("Renamed", registry.get("svc").orElseThrow().name());
        int calls = c.calls.get();
        await("still checked", () -> c.calls.get() > calls + 1);
        assertFalse(c.closed.get());
        EndpointStatus after = statusOf("svc");
        assertEquals(State.DOWN, after.state());
        assertEquals(before.since(), after.since(), "outage kept");
        assertEquals(before.streakStart(), after.streakStart());
    }

    @Test
    void closeStopsEverythingAndClosesCheckers() throws Exception {
        registry.add(def("a", "ok", 1000));
        registry.add(def("b", "block", 5000));
        scheduler.start();
        FakeChecker a = checker("a");
        FakeChecker b = checker("b");
        await("b in flight", () -> b.active.get() == 1);
        scheduler.close();
        assertTrue(a.closed.get());
        assertTrue(b.closed.get());
        int calls = a.calls.get();
        Thread.sleep(SECOND.toMillis() * 4);
        assertEquals(calls, a.calls.get());
        // The registry no longer drives a closed scheduler.
        registry.add(def("c", "ok", 1000));
        Thread.sleep(SECOND.toMillis() * 2);
        assertEquals(0, checker("c").calls.get());
    }

    // ---- registry rules ----

    @Test
    void registryRejectsInvalidChangesWithoutSideEffects() throws Exception {
        registry.add(def("svc", "ok", 1000));
        FakeChecker c = checker("svc");

        ConfigException dup = assertThrows(ConfigException.class, () -> registry.add(def("svc", "ok", 1000)));
        assertEquals("id", dup.settingKey().orElseThrow());

        JsonObject bad = new JsonObject();
        bad.addProperty("mode", "ok");
        bad.addProperty("typo", 1);
        EndpointDefinition invalid = new EndpointDefinition("svc", "svc", "fake", null, Visibility.PUBLIC, 1,
                1000, 1, List.of(), List.of(), 0, bad);
        ConfigException e = assertThrows(ConfigException.class, () -> registry.update(invalid));
        assertEquals("typo", e.settingKey().orElseThrow());
        assertSame(c, checker("svc"));
        assertFalse(c.closed.get());
        // The rejected checker was closed again.
        assertTrue(plugin.created.get(plugin.created.size() - 1).closed.get());

        assertThrows(ConfigException.class, () -> registry.add(new EndpointDefinition("x", "x", "nope", null,
                Visibility.PUBLIC, 1, 1000, 1, List.of(), List.of(), 0, new JsonObject())));
        assertThrows(IllegalArgumentException.class, () -> registry.update(def("missing", "ok", 1000)));
        assertEquals(EndpointRegistry.UpdateKind.UNCHANGED, registry.update(def("svc", "ok", 1000)));
        assertFalse(registry.remove("missing"));
    }

    @Test
    void changeKindsFollowThePlan() {
        EndpointDefinition base = def("svc", "Svc", "ok", 1000, 1, "");
        // Presentation and routing: in place.
        assertFalse(Endpoint.needsNewChecker(base, def("svc", "Other name", "ok", 1000, 1, "")));
        assertFalse(Endpoint.needsNewChecker(base, new EndpointDefinition("svc", "Svc", "fake", "cat",
                Visibility.PRIVATE, 1, 1000, 1, List.of("mail"), List.of(5), 10, base.settings())));
        // The check itself: new checker.
        assertTrue(Endpoint.needsNewChecker(base, def("svc", "Svc", "ok", 2000, 1, "")));
        assertTrue(Endpoint.needsNewChecker(base, def("svc", "Svc", "ok", 1000, 3, "")));
        assertTrue(Endpoint.needsNewChecker(base, def("svc", "Svc", "ok", 1000, 1, "x")));
        assertTrue(Endpoint.needsNewChecker(base, new EndpointDefinition("svc", "Svc", "fake", null,
                Visibility.PUBLIC, 2, 1000, 1, List.of(), List.of(30, 60, 120), 0, base.settings())));
    }

    // ---- fakes ----

    /** Type "fake"; setting "mode" picks the behaviour, "tag" only exists to change the settings. */
    private static final class FakePlugin implements CheckPlugin {
        final CountDownLatch release = new CountDownLatch(1);
        final List<FakeChecker> created = new CopyOnWriteArrayList<>();

        @Override
        public String type() {
            return "fake";
        }

        @Override
        public List<SettingSpec> settings() {
            return List.of();
        }

        @Override
        public Checker create(EndpointConfig config) throws ConfigException {
            String mode = config.getString("mode");
            config.optString("tag");
            config.declareTarget("host", "secret.example");
            FakeChecker c = new FakeChecker(mode, release);
            created.add(c);
            return c;
        }
    }

    private static final class FakeChecker implements Checker {
        final String mode;
        final CountDownLatch release;
        final AtomicInteger calls = new AtomicInteger();
        final AtomicInteger active = new AtomicInteger();
        final AtomicInteger maxConcurrent = new AtomicInteger();
        final AtomicInteger interrupts = new AtomicInteger();
        final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();
        final java.util.concurrent.atomic.AtomicBoolean closedWhileChecking =
                new java.util.concurrent.atomic.AtomicBoolean();

        FakeChecker(String mode, CountDownLatch release) {
            this.mode = mode;
            this.release = release;
        }

        @Override
        public CheckResult check(Duration timeout) throws Exception {
            calls.incrementAndGet();
            int n = active.incrementAndGet();
            maxConcurrent.accumulateAndGet(n, Math::max);
            try {
                return switch (mode) {
                    case "ok" -> CheckResult.ok("ok");
                    case "fail" -> CheckResult.fail("fail");
                    case "null" -> null;
                    case "throw" -> throw new IllegalStateException("can't reach secret.example");
                    case "error" -> throw new StackOverflowError();
                    case "slow" -> {
                        Thread.sleep(120);
                        yield CheckResult.ok("ok");
                    }
                    case "hang", "block" -> {
                        try {
                            Thread.sleep(Duration.ofHours(1));
                        } catch (InterruptedException e) {
                            interrupts.incrementAndGet();
                            if (mode.equals("hang")) throw e;
                        }
                        yield CheckResult.ok("late");
                    }
                    case "stuck" -> {
                        if (calls.get() > 1) yield CheckResult.ok("ok");
                        while (true) {
                            try {
                                if (release.await(5, java.util.concurrent.TimeUnit.SECONDS)) break;
                            } catch (InterruptedException e) {
                                interrupts.incrementAndGet(); // ignored
                            }
                        }
                        yield CheckResult.ok("late");
                    }
                    default -> throw new AssertionError(mode);
                };
            } finally {
                active.decrementAndGet();
            }
        }

        @Override
        public void close() {
            if (active.get() > 0) closedWhileChecking.set(true);
            closed.set(true);
        }
    }

    private static final class SilentLogger implements Logger {
        private final List<String> warnings;

        SilentLogger(List<String> warnings) {
            this.warnings = warnings;
        }

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
            if (level.getSeverity() >= Level.WARNING.getSeverity()) warnings.add(msg);
        }

        @Override
        public void log(Level level, ResourceBundle bundle, String format, Object... params) {
            log(level, bundle, params == null || params.length == 0 ? format : MessageFormat.format(format, params),
                    (Throwable) null);
        }
    }
}
