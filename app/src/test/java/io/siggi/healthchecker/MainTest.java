package io.siggi.healthchecker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainTest {
    private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
    private final PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);

    private int execute(String... args) {
        return Main.execute(args, out, err);
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    private static String json(Path path) {
        return path.toString().replace("\\", "\\\\");
    }

    @Test
    void runLoadsTheGivenConfig(@TempDir Path dir) throws Exception {
        Path config = dir.resolve("my-config.json");
        Files.writeString(config, """
                { "server": { "port": %d, "bind": "127.0.0.1" }, "dataDir": "%s" }
                """.formatted(freePort(), json(dir.resolve("data"))));
        Main.Runner stop = daemon -> {
        };
        assertEquals(Main.EXIT_OK, Main.execute(new String[]{"run", config.toString()}, out, err, stop),
                errBytes.toString(StandardCharsets.UTF_8));
        assertEquals(Main.EXIT_OK, Main.execute(new String[]{config.toString()}, out, err, stop),
                errBytes.toString(StandardCharsets.UTF_8));
        assertTrue(outBytes.toString(StandardCharsets.UTF_8).contains("0 endpoint(s)"));
    }

    @Test
    void runChecksEndpointsAndServesTheStatusApi(@TempDir Path dir) throws Exception {
        int port = freePort();
        Path config = dir.resolve("config.json");
        // The endpoint checks HealthChecker's own main page, so nothing external is contacted.
        Files.writeString(config, """
                { "server": { "port": %d, "bind": "127.0.0.1" }, "dataDir": "%s",
                  "plugins": { "dir": "%s" },
                  "endpoints": [ { "id": "self", "name": "Self", "type": "http", "visibility": "public",
                                   "url": "http://127.0.0.1:%d", "maxClass": 5, "intervalSeconds": 1 } ] }
                """.formatted(port, json(dir.resolve("data")), json(dir.resolve("plugins")), port));
        AtomicReference<String> body = new AtomicReference<>();
        Main.Runner poll = daemon -> {
            assertEquals(port, daemon.port());
            try (HttpClient client = HttpClient.newHttpClient()) {
                HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/status"))
                        .timeout(Duration.ofSeconds(5)).build();
                long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
                while (System.nanoTime() < deadline) {
                    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                    body.set(response.body());
                    if (response.statusCode() == 200 && response.body().contains("\"state\":\"UP\"")) {
                        return;
                    }
                    Thread.sleep(100);
                }
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        };
        assertEquals(Main.EXIT_OK, Main.execute(new String[]{"run", config.toString()}, out, err, poll),
                errBytes.toString(StandardCharsets.UTF_8));
        assertTrue(body.get() != null && body.get().contains("\"overall\":\"UP\""), body.get());
        // Closing the daemon wrote the coverage of the check run.
        assertTrue(Files.exists(dir.resolve("data")), "history directory written");
    }

    @Test
    void runReportsConfigErrors(@TempDir Path dir) throws Exception {
        Path config = dir.resolve("bad.json");
        Files.writeString(config, "{\"timezone\": \"Nowhere/Nothing\", \"nope\": 1}");
        assertEquals(Main.EXIT_ERROR, execute("run", config.toString()));
        String errText = errBytes.toString(StandardCharsets.UTF_8);
        assertTrue(errText.contains("2 configuration errors"), errText);
    }

    @Test
    void runReportsUnknownEndpointTypes(@TempDir Path dir) throws Exception {
        Path config = dir.resolve("config.json");
        String pluginsDir = dir.resolve("plugins").toString().replace("\\", "\\\\");
        Files.writeString(config, """
                { "plugins": { "dir": "%s" },
                  "endpoints": [ { "id": "survival", "name": "Survival", "type": "minecraft", "host": "x" } ] }
                """.formatted(pluginsDir));
        assertEquals(Main.EXIT_ERROR, execute("run", config.toString()));
        String errText = errBytes.toString(StandardCharsets.UTF_8);
        assertTrue(errText.contains("1 configuration error:"), errText);
        assertTrue(errText.contains("endpoints[0] \"survival\": unknown type \"minecraft\" (available: "), errText);
    }

    @Test
    void runReportsInvalidSessionDays(@TempDir Path dir) throws Exception {
        Path config = dir.resolve("config.json");
        Files.writeString(config, """
                { "plugins": { "dir": "%s" }, "auth": { "sessionDays": 0 } }
                """.formatted(json(dir.resolve("plugins"))));
        assertEquals(Main.EXIT_ERROR, execute("run", config.toString()));
        String errText = errBytes.toString(StandardCharsets.UTF_8);
        assertTrue(errText.contains("1 configuration error:"), errText);
        assertTrue(errText.contains("auth.sessionDays must be a whole number from 1 to 365"), errText);
    }

    @Test
    void runReportsMissingFile(@TempDir Path dir) {
        assertEquals(Main.EXIT_ERROR, execute("run", dir.resolve("missing.json").toString()));
        assertTrue(errBytes.toString(StandardCharsets.UTF_8).contains("file not found"));
    }

    @Test
    void badArgumentsPrintUsage() {
        assertEquals(Main.EXIT_USAGE, execute("run", "a.json", "b.json"));
        assertEquals(Main.EXIT_USAGE, execute("hash-password", "extra"));
        assertEquals(Main.EXIT_USAGE, execute("--bogus"));
        assertTrue(errBytes.toString(StandardCharsets.UTF_8).contains("Usage:"));
    }

    @Test
    void hashPasswordWithoutConsoleFails() {
        assertEquals(Main.EXIT_ERROR, Main.hashPassword(null, out, err));
        assertTrue(errBytes.toString(StandardCharsets.UTF_8).contains("interactive console"));
    }
}
