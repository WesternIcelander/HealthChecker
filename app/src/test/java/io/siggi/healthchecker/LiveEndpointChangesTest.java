package io.siggi.healthchecker;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.siggi.healthchecker.api.CheckPlugin;
import io.siggi.healthchecker.api.CheckResult;
import io.siggi.healthchecker.api.Checker;
import io.siggi.healthchecker.api.ConfigException;
import io.siggi.healthchecker.api.EndpointConfig;
import io.siggi.healthchecker.api.SettingSpec;
import io.siggi.healthchecker.check.http.HttpCheckPlugin;
import io.siggi.healthchecker.config.Config;
import io.siggi.healthchecker.config.ConfigLoader;
import io.siggi.healthchecker.config.EndpointStore;
import io.siggi.healthchecker.history.HistoryEvent;
import io.siggi.healthchecker.history.HistoryStore;
import io.siggi.healthchecker.plugin.PluginManager;
import io.siggi.healthchecker.schedule.Endpoint;
import io.siggi.healthchecker.status.EndpointStatus;
import io.siggi.healthchecker.status.State;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Saving an endpoint in the dashboard applies to the running daemon at once (M11 "Save applies live"). */
class LiveEndpointChangesTest {
    private static final String ID = "svc";

    @TempDir
    Path dataDir;

    private Config config;
    private PluginManager plugins;
    private Daemon daemon;
    private HttpClient client;

    /** Checks nothing: a host starting with "up" passes, any other fails with a message naming the host. */
    static final class FakePlugin implements CheckPlugin {
        final Set<Checker> closed = ConcurrentHashMap.newKeySet();

        @Override
        public String type() {
            return "fake";
        }

        @Override
        public List<SettingSpec> settings() {
            return List.of(SettingSpec.string("host", "Host").asRequired());
        }

        @Override
        public Checker create(EndpointConfig config) throws ConfigException {
            String host = config.getString("host");
            config.declareTarget("host", host);
            return new Checker() {
                @Override
                public CheckResult check(Duration timeout) {
                    return host.startsWith("up") ? CheckResult.ok("ok") : CheckResult.fail("cannot reach " + host);
                }

                @Override
                public void close() {
                    closed.add(this);
                }
            };
        }
    }

    private final FakePlugin fake = new FakePlugin();

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    private void start() throws Exception {
        if (config == null) {
            config = new ConfigLoader(name -> null, ZoneId.of("UTC")).load("""
                    { "server": { "port": %d, "bind": "127.0.0.1" }, "dataDir": %s, "timezone": "UTC" }
                    """.formatted(freePort(), new Gson().toJson(dataDir.toString())));
            plugins = PluginManager.load(List.of(new HttpCheckPlugin(), fake), null, getClass().getClassLoader());
        }
        daemon = Daemon.start(config, plugins, EndpointStore.load(config, plugins::validate), HistoryStore.open(dataDir));
        // A new client per daemon: a kept-alive connection could still reach a closed daemon's server.
        if (client != null) client.close();
        client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @AfterEach
    void stop() {
        if (daemon != null) daemon.close();
        if (plugins != null) plugins.close();
        if (client != null) client.close();
    }

    private static JsonObject entry(String name, String host, int interval) {
        JsonObject o = new JsonObject();
        o.addProperty("id", ID);
        o.addProperty("name", name);
        o.addProperty("type", "fake");
        o.addProperty("visibility", "public");
        o.addProperty("intervalSeconds", interval);
        o.addProperty("failureThreshold", 1);
        o.addProperty("host", host);
        return o;
    }

    private void save(JsonObject entry) throws Exception {
        EndpointStore store = daemon.endpoints();
        store.putEndpoint(store.version(), entry);
    }

    private Optional<EndpointStatus> status() {
        return daemon.status().status(ID);
    }

    private Endpoint endpoint() {
        return daemon.registry().get(ID).orElseThrow();
    }

    private static void await(String what, Duration limit, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + limit.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("timed out waiting for " + what);
            Thread.sleep(20);
        }
    }

    private void awaitDown() throws InterruptedException {
        await("DOWN with an open event", Duration.ofSeconds(5), () ->
                status().map(s -> s.state() == State.DOWN).orElse(false)
                        && daemon.history().openEvent(ID).isPresent());
    }

    private String get(String path) throws Exception {
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + daemon.port() + path)).timeout(Duration.ofSeconds(5)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertTrue(response.statusCode() == 200 || response.statusCode() == 503,
                path + " -> " + response.statusCode() + ": " + response.body());
        return response.body();
    }

    @Test
    void createdEndpointIsCheckedWithinOneInterval() throws Exception {
        start();
        assertTrue(daemon.registry().get(ID).isEmpty());
        long saved = System.nanoTime();
        save(entry("Service", "up.example.invalid", 2));
        assertEquals(State.UNKNOWN, status().orElseThrow().state());
        await("first check", Duration.ofSeconds(5), () -> status().map(s -> s.lastCheck() != null).orElse(false));
        Duration took = Duration.ofNanos(System.nanoTime() - saved);
        // First check after a random delay of at most min(interval, 10 s); a little slack for the test machine.
        assertTrue(took.compareTo(Duration.ofMillis(2500)) <= 0, "first check after " + took);
        await("UP", Duration.ofSeconds(3), () -> status().map(s -> s.state() == State.UP).orElse(false));
        assertTrue(daemon.history().monitored(ID), "coverage started");
    }

    @Test
    void nameEditKeepsCheckerStateAndOpenEvent() throws Exception {
        start();
        save(entry("Service", "down.example.invalid", 1));
        awaitDown();
        Checker checker = endpoint().checker();
        EndpointStatus before = status().orElseThrow();
        HistoryEvent open = daemon.history().openEvent(ID).orElseThrow();

        save(entry("Renamed service", "down.example.invalid", 1));

        assertEquals("Renamed service", endpoint().name());
        assertSame(checker, endpoint().checker());
        assertFalse(fake.closed.contains(checker), "checker kept open");
        EndpointStatus after = status().orElseThrow();
        assertEquals(State.DOWN, after.state());
        assertEquals(before.since(), after.since());
        assertEquals(open.id(), daemon.history().openEvent(ID).orElseThrow().id());
        Thread.sleep(1500);
        assertEquals(State.DOWN, status().orElseThrow().state());
        assertEquals(open.id(), daemon.history().openEvent(ID).orElseThrow().id());
        assertEquals(1, daemon.history().events(ID).size());
    }

    @Test
    void hostEditReplacesCheckerAndClosesEventAsEndedByConfigChange() throws Exception {
        start();
        save(entry("Service", "down.example.invalid", 1));
        awaitDown();
        Checker oldChecker = endpoint().checker();
        HistoryEvent open = daemon.history().openEvent(ID).orElseThrow();

        save(entry("Service", "up.example.invalid", 1));

        assertNotEquals(oldChecker, endpoint().checker());
        HistoryEvent closed = daemon.history().event(ID, open.id()).orElseThrow();
        assertFalse(closed.ongoing());
        assertTrue(closed.endedByConfigChange());
        assertFalse(closed.endedUnobserved());
        assertEquals(closed.lastFailureAt(), closed.end());
        assertTrue(daemon.history().openEvent(ID).isEmpty());
        assertNotEquals(State.DOWN, status().orElseThrow().state());
        await("old checker closed", Duration.ofSeconds(3), () -> fake.closed.contains(oldChecker));
        await("UP with the new checker", Duration.ofSeconds(3),
                () -> status().map(s -> s.state() == State.UP).orElse(false));
        assertEquals(1, daemon.history().events(ID).size());
        assertTrue(daemon.history().monitored(ID), "a new coverage period started");
    }

    @Test
    void noTargetLeaksIntoPublicPagesAfterEdits() throws Exception {
        start();
        save(entry("Service", "down-a-secret.example.invalid", 1));
        awaitDown();
        // A host edit: the first event (whose messages name the old host) is closed, a new one opens.
        save(entry("Service", "down-b-secret.example.invalid", 1));
        awaitDown();
        save(entry("Renamed", "down-b-secret.example.invalid", 1));
        List<HistoryEvent> events = daemon.history().events(ID);
        assertEquals(2, events.size());
        assertTrue(events.getFirst().firstFailureMessage().contains("down-a-secret"), "old host is in the history");

        assertNoSecretInPublicPages(events);

        // Still hidden after a restart, when the endpoint only knows its current host.
        daemon.close();
        daemon = null;
        start();
        awaitDown();
        assertNoSecretInPublicPages(daemon.history().events(ID));
    }

    private void assertNoSecretInPublicPages(List<HistoryEvent> events) throws Exception {
        String today = LocalDate.now(ZoneOffset.UTC).toString();
        List<String> paths = new ArrayList<>(List.of("/", "/api/status", "/service/" + ID,
                "/service/" + ID + "?view=calendar", "/service/" + ID + "?view=calendar&day=" + today,
                "/service/" + ID + "/day/" + today, "/api/service/" + ID, "/api/service/" + ID + "/events",
                "/api/service/" + ID + "/days"));
        for (HistoryEvent e : events) {
            paths.add("/service/" + ID + "/event/" + e.id());
            paths.add("/api/service/" + ID + "/event/" + e.id());
        }
        boolean sawName = false;
        for (String path : paths) {
            String body = get(path);
            assertFalse(body.toLowerCase().contains("secret"), path + " leaks a target: " + body);
            sawName |= body.contains("Renamed");
        }
        assertTrue(sawName, "the pages show the endpoint");
        assertTrue(daemon.history().knownTargets(ID).containsAll(
                Set.of("down-a-secret.example.invalid", "down-b-secret.example.invalid")));
    }

    @Test
    void deleteStopsChecksKeepsHistoryAndBlocksIdReuse() throws Exception {
        start();
        save(entry("Service", "down.example.invalid", 1));
        awaitDown();
        Checker checker = endpoint().checker();
        HistoryEvent open = daemon.history().openEvent(ID).orElseThrow();

        EndpointStore store = daemon.endpoints();
        store.removeEndpoint(store.version(), ID);

        assertTrue(daemon.registry().get(ID).isEmpty());
        assertTrue(status().isEmpty());
        assertFalse(daemon.history().monitored(ID), "coverage ended");
        HistoryEvent closed = daemon.history().event(ID, open.id()).orElseThrow();
        assertTrue(closed.endedUnobserved());
        await("checker closed", Duration.ofSeconds(3), () -> fake.closed.contains(checker));
        int events = daemon.history().events(ID).size();
        Thread.sleep(1500);
        assertEquals(events, daemon.history().events(ID).size(), "no more checks recorded");
        assertFalse(daemon.history().monitored(ID));

        // History kept, so the id is reserved.
        EndpointStore.InvalidEntryException e = org.junit.jupiter.api.Assertions.assertThrows(
                EndpointStore.InvalidEntryException.class, () -> save(entry("Other", "up.example.invalid", 1)));
        assertTrue(e.getMessage().contains("still has history"), e.getMessage());

        // Deleting the history frees it; the new endpoint starts with a clean history.
        assertTrue(daemon.history().deleteHistory(ID));
        save(entry("Other", "up.example.invalid", 1));
        await("UP", Duration.ofSeconds(5), () -> status().map(s -> s.state() == State.UP).orElse(false));
        assertTrue(daemon.history().events(ID).isEmpty());
        assertEquals(Set.of("up.example.invalid"), daemon.history().knownTargets(ID));
    }
}
