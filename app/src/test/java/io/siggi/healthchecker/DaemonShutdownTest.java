package io.siggi.healthchecker;

import com.google.gson.Gson;
import io.siggi.healthchecker.api.CheckPlugin;
import io.siggi.healthchecker.api.CheckResult;
import io.siggi.healthchecker.api.Checker;
import io.siggi.healthchecker.api.EndpointConfig;
import io.siggi.healthchecker.api.SettingSpec;
import io.siggi.healthchecker.config.Config;
import io.siggi.healthchecker.config.ConfigLoader;
import io.siggi.healthchecker.config.EndpointStore;
import io.siggi.healthchecker.history.CoveragePeriod;
import io.siggi.healthchecker.history.HistoryStore;
import io.siggi.healthchecker.notify.FakeWebhook;
import io.siggi.healthchecker.plugin.PluginManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shutdown: checkers are closed, coverage is written up to the shutdown, pending notifications are
 * flushed within the bound, and the web server stops, all within the time limit.
 */
class DaemonShutdownTest {
    @TempDir
    Path dataDir;

    private final AtomicInteger checks = new AtomicInteger();
    private final List<String> closedCheckers = new CopyOnWriteArrayList<>();
    private final List<Closeable> toClose = new ArrayList<>();
    private PluginManager plugins;
    private Daemon daemon;

    private interface Closeable {
        void close() throws Exception;
    }

    /** Always fails; records when its checkers are closed. */
    final class FailingPlugin implements CheckPlugin {
        @Override
        public String type() {
            return "failing";
        }

        @Override
        public List<SettingSpec> settings() {
            return List.of();
        }

        @Override
        public Checker create(EndpointConfig config) {
            String id = config.id();
            return new Checker() {
                @Override
                public CheckResult check(Duration timeout) {
                    checks.incrementAndGet();
                    return CheckResult.fail("refused");
                }

                @Override
                public void close() {
                    closedCheckers.add(id);
                }
            };
        }
    }

    @AfterEach
    void stop() throws Exception {
        if (daemon != null) daemon.close();
        if (plugins != null) plugins.close();
        for (Closeable c : toClose) c.close();
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    private static void await(BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("timed out waiting for " + what);
            Thread.sleep(20);
        }
    }

    /** Starts the daemon with two always-failing endpoints, notifying a webhook with a one-hour batch window. */
    private void start(String webhookUrl) throws Exception {
        Config config = new ConfigLoader(name -> name.equals("HOOK") ? webhookUrl : null, ZoneId.of("UTC")).load("""
                { "server": { "port": %d, "bind": "127.0.0.1" },
                  "dataDir": %s, "timezone": "UTC",
                  "notifications": { "batchSeconds": 3600, "channels": {
                    "discord": { "type": "discord", "webhookUrl": "${env:HOOK}" } } },
                  "defaults": { "notify": ["discord"] },
                  "endpoints": [
                    { "id": "one", "name": "One", "type": "failing", "intervalSeconds": 1 },
                    { "id": "two", "name": "Two", "type": "failing", "intervalSeconds": 1 } ] }
                """.formatted(freePort(), new Gson().toJson(dataDir.toString())));
        plugins = PluginManager.load(List.of(new FailingPlugin()), null, getClass().getClassLoader());
        daemon = Daemon.start(config, plugins, EndpointStore.load(config, plugins::validate), HistoryStore.open(dataDir));
        // Down (default threshold) with an open event in both, so a notification is waiting in the batch window.
        await(() -> daemon.history().openEvent("one").isPresent() && daemon.history().openEvent("two").isPresent(),
                "both endpoints down");
        assertTrue(daemon.notifications().status("discord").orElseThrow().pending() > 0, "a notification is pending");
    }

    private Instant lastCoverageEndOnDisk(String id) throws IOException {
        HistoryStore reloaded = HistoryStore.open(dataDir);
        List<CoveragePeriod> periods = reloaded.coverage(id);
        return periods.getLast().end();
    }

    @Test
    void shutdownClosesCheckersFlushesNotificationsAndWritesFinalCoverage() throws Exception {
        FakeWebhook webhook = new FakeWebhook();
        toClose.add(webhook::close);
        start(webhook.url().toString());
        int port = daemon.port();
        assertTrue(webhook.posts().isEmpty(), "still in the batch window");

        Instant beforeClose = Instant.now();
        long started = System.nanoTime();
        daemon.close();
        Duration took = Duration.ofNanos(System.nanoTime() - started);
        Instant afterClose = Instant.now();

        assertTrue(took.compareTo(Duration.ofSeconds(5)) < 0, "shutdown took " + took);
        assertEquals(List.of("one", "two"), closedCheckers.stream().sorted().toList());
        assertEquals(1, webhook.posts().size(), "the pending batch was flushed");
        assertEquals(2, webhook.posts().getFirst().json().getAsJsonArray("embeds").size());
        for (String id : List.of("one", "two")) {
            Instant end = lastCoverageEndOnDisk(id);
            assertFalse(end.isBefore(beforeClose.minusMillis(5)), id + " coverage ends at the shutdown: " + end);
            assertFalse(end.isAfter(afterClose), id + " coverage ends at the shutdown: " + end);
        }
        int after = checks.get();
        Thread.sleep(1500);
        assertEquals(after, checks.get(), "no checks after shutdown");
        HttpClient client = HttpClient.newHttpClient();
        assertThrows(IOException.class, () -> client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/health")).build(),
                HttpResponse.BodyHandlers.discarding()));
    }

    @Test
    void hangingNotificationReceiverDoesNotHoldUpShutdown() throws Exception {
        // Accepts connections but never answers: a delivery would wait for the notifier's 10 s timeout.
        ServerSocket hanging = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        List<Socket> accepted = new CopyOnWriteArrayList<>();
        Thread acceptor = Thread.ofVirtual().start(() -> {
            try {
                while (true) accepted.add(hanging.accept());
            } catch (IOException ignored) {
            }
        });
        toClose.add(() -> {
            hanging.close();
            for (Socket s : accepted) s.close();
            acceptor.join();
        });
        start("http://127.0.0.1:" + hanging.getLocalPort() + "/webhook");
        daemon.notificationFlushTimeout(Duration.ofMillis(500));

        long started = System.nanoTime();
        daemon.close();
        Duration took = Duration.ofNanos(System.nanoTime() - started);
        Instant afterClose = Instant.now();

        // Grace period (1 s) + flush bound (0.5 s) + web server, well under the 10 s delivery timeout.
        assertTrue(took.compareTo(Duration.ofSeconds(4)) < 0, "shutdown took " + took);
        assertTrue(took.compareTo(Duration.ofMillis(450)) >= 0, "waited for the flush: " + took);
        assertFalse(accepted.isEmpty(), "delivery was attempted");
        assertEquals(List.of("one", "two"), closedCheckers.stream().sorted().toList());
        for (String id : List.of("one", "two")) {
            Instant end = lastCoverageEndOnDisk(id);
            assertFalse(end.isAfter(afterClose));
            assertTrue(Duration.between(end, afterClose).compareTo(took.plusMillis(50)) <= 0,
                    id + " coverage written during shutdown: " + end);
        }
    }

    @Test
    void secondCloseWaitsForTheFirst() throws Exception {
        FakeWebhook webhook = new FakeWebhook();
        toClose.add(webhook::close);
        start(webhook.url().toString());
        Thread other = Thread.ofPlatform().start(daemon::close);
        daemon.close();
        other.join(Duration.ofSeconds(5));
        assertFalse(other.isAlive());
        assertEquals(List.of("one", "two"), closedCheckers.stream().sorted().toList());
        assertEquals(1, webhook.posts().size());
    }
}
