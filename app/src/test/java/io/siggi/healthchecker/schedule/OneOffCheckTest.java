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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link OneOffCheck}: one check with the scheduler's rules, checker closed afterwards. */
class OneOffCheckTest {
    private final AtomicInteger created = new AtomicInteger();
    private final AtomicInteger closed = new AtomicInteger();
    private PluginManager plugins;
    private OneOffCheck oneOff;

    /** {@code mode}: ok, throw, null, hang (sleeps, interruptible) or bad (rejected by create()). */
    private final class Plugin implements CheckPlugin {
        @Override
        public String type() {
            return "one";
        }

        @Override
        public List<SettingSpec> settings() {
            return List.of(SettingSpec.string("mode", "Mode"));
        }

        @Override
        public Checker create(EndpointConfig config) throws ConfigException {
            String mode = config.getString("mode");
            if (mode.equals("bad")) throw new ConfigException("mode", "bad mode");
            created.incrementAndGet();
            return new Checker() {
                @Override
                public CheckResult check(Duration timeout) throws Exception {
                    return switch (mode) {
                        case "ok" -> CheckResult.ok("fine").with("answer", 42);
                        case "throw" -> throw new IllegalStateException("secret.example.invalid");
                        case "null" -> null;
                        default -> {
                            Thread.sleep(60_000);
                            yield CheckResult.ok("late");
                        }
                    };
                }

                @Override
                public void close() {
                    closed.incrementAndGet();
                }
            };
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        plugins = PluginManager.load(List.of(new Plugin()), null, getClass().getClassLoader());
        oneOff = new OneOffCheck(new EndpointFactory(plugins), Duration.ofMillis(50), Clock.systemUTC());
    }

    @AfterEach
    void tearDown() {
        plugins.close();
    }

    private static EndpointDefinition def(String mode, int timeoutMillis) {
        JsonObject settings = new JsonObject();
        settings.addProperty("mode", mode);
        return new EndpointDefinition("probe", "Probe", "one", null, Visibility.PUBLIC, 60, timeoutMillis,
                1, List.of(), List.of(), 0, settings);
    }

    @Test
    void passingCheckReturnsItsResultAndClosesTheChecker() throws Exception {
        CheckRecord record = oneOff.run(def("ok", 1000));
        assertTrue(record.success());
        assertEquals("fine", record.message());
        assertEquals(42, record.result().details().get("answer"));
        assertFalse(record.latency().isNegative());
        assertEquals(1, created.get());
        assertEquals(1, closed.get());
    }

    @Test
    void thrownExceptionAndNullBecomeGenericFailures() throws Exception {
        CheckRecord thrown = oneOff.run(def("throw", 1000));
        assertFalse(thrown.success());
        assertEquals("check failed (IllegalStateException)", thrown.message());
        CheckRecord none = oneOff.run(def("null", 1000));
        assertEquals("check returned no result", none.message());
        assertEquals(2, closed.get());
    }

    @Test
    void timeoutIsEnforcedAndTheCheckerClosed() throws Exception {
        long start = System.nanoTime();
        CheckRecord record = oneOff.run(def("hang", 100));
        assertTrue(Duration.ofNanos(System.nanoTime() - start).compareTo(Duration.ofSeconds(5)) < 0);
        assertFalse(record.success());
        assertEquals("timeout after 100 ms", record.message());
        assertEquals(1, closed.get());
    }

    @Test
    void rejectedSettingsThrowWithoutRunning() {
        assertThrows(ConfigException.class, () -> oneOff.run(def("bad", 1000)));
        assertEquals(0, created.get());
    }
}
