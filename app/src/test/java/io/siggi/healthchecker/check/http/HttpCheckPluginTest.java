package io.siggi.healthchecker.check.http;

import com.google.gson.JsonObject;
import io.siggi.healthchecker.api.CheckPlugin;
import io.siggi.healthchecker.api.CheckResult;
import io.siggi.healthchecker.api.Checker;
import io.siggi.healthchecker.api.ConfigException;
import io.siggi.healthchecker.plugin.JsonEndpointConfig;
import io.siggi.http.HTTPServer;
import io.siggi.http.HTTPServerBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpCheckPluginTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private static HTTPServer server;
    private static final List<ServerSocket> sockets = new ArrayList<>();
    /** What the server does, per local port. */
    private static final Map<Integer, Integer> statusByPort = new ConcurrentHashMap<>();
    private static final Map<Integer, AtomicInteger> hitsByPort = new ConcurrentHashMap<>();
    private static final CountDownLatch release = new CountDownLatch(1);
    private static final int HANG = -1;

    private static int port200, port301, port404, port503, portHang, portRedirectTarget;

    private final HttpCheckPlugin plugin = new HttpCheckPlugin();

    @BeforeAll
    static void startServer() throws IOException {
        server = new HTTPServerBuilder().setExecutor(Executors.newVirtualThreadPerTaskExecutor()).build();
        server.responderRegistry.register("/", request -> {
            int port = request.getLocalAddress().getPort();
            hitsByPort.get(port).incrementAndGet();
            int status = statusByPort.get(port);
            switch (status) {
                case HANG -> release.await(30, TimeUnit.SECONDS);
                case 200 -> request.response.write("ok");
                case 301 -> request.response.movedPermanently("http://127.0.0.1:" + portRedirectTarget + "/");
                case 404 -> {
                    request.response.setHeader("404 Not Found");
                    request.response.write("not found");
                }
                case 503 -> {
                    request.response.setHeader("503 Service Unavailable");
                    request.response.write("unavailable");
                }
                default -> throw new IllegalStateException("status " + status);
            }
        });
        port200 = listen(200);
        port301 = listen(301);
        port404 = listen(404);
        port503 = listen(503);
        portHang = listen(HANG);
        portRedirectTarget = listen(200);
    }

    private static int listen(int status) throws IOException {
        ServerSocket socket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        sockets.add(socket);
        int port = socket.getLocalPort();
        statusByPort.put(port, status);
        hitsByPort.put(port, new AtomicInteger());
        Thread.ofVirtual().start(() -> {
            while (!socket.isClosed()) {
                try {
                    Socket s = socket.accept();
                    server.handle(s);
                } catch (IOException e) {
                    return;
                }
            }
        });
        return port;
    }

    @AfterAll
    static void stopServer() throws IOException {
        release.countDown();
        for (ServerSocket s : sockets) s.close();
    }

    private static JsonEndpointConfig config(String url, Integer maxClass) {
        JsonObject o = new JsonObject();
        if (url != null) o.addProperty("url", url);
        if (maxClass != null) o.addProperty("maxClass", maxClass);
        return new JsonEndpointConfig("web", "Web", o);
    }

    private CheckResult check(int port, int maxClass, Duration timeout) throws Exception {
        JsonEndpointConfig config = config("http://127.0.0.1:" + port, maxClass);
        try (Checker checker = plugin.create(config)) {
            config.checkNoUnreadKeys();
            return checker.check(timeout);
        }
    }

    static Stream<Arguments> matrix() {
        List<Arguments> args = new ArrayList<>();
        for (int status : new int[]{200, 301, 404, 503}) {
            for (int maxClass = 2; maxClass <= 5; maxClass++) {
                args.add(Arguments.of(status, maxClass, status / 100 <= maxClass));
            }
        }
        return args.stream();
    }

    @ParameterizedTest(name = "status {0}, maxClass {1} -> pass {2}")
    @MethodSource("matrix")
    void statusMatrix(int status, int maxClass, boolean expectPass) throws Exception {
        int port = switch (status) {
            case 200 -> port200;
            case 301 -> port301;
            case 404 -> port404;
            case 503 -> port503;
            default -> throw new IllegalArgumentException();
        };
        CheckResult result = check(port, maxClass, TIMEOUT);
        assertEquals(expectPass, result.success(), result.message());
        assertEquals(status, result.details().get("statusCode"));
        assertTrue(result.message().contains(String.valueOf(status)), result.message());
        assertFalse(result.message().contains("127.0.0.1"), result.message());
    }

    @Test
    void redirectNotFollowed() throws Exception {
        int before = hitsByPort.get(portRedirectTarget).get();
        CheckResult result = check(port301, 2, TIMEOUT);
        assertFalse(result.success());
        assertEquals(301, result.details().get("statusCode"));
        assertEquals(before, hitsByPort.get(portRedirectTarget).get(), "redirect target was requested");
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 3, 4, 5})
    void connectionRefusedAlwaysFails(int maxClass) throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = s.getLocalPort();
        }
        CheckResult result = check(port, maxClass, TIMEOUT);
        assertFalse(result.success(), result.message());
        assertFalse(result.details().containsKey("statusCode"));
        assertFalse(result.message().contains(String.valueOf(port)), result.message());
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 5})
    void timeoutAlwaysFails(int maxClass) throws Exception {
        long start = System.nanoTime();
        CheckResult result = check(portHang, maxClass, Duration.ofMillis(300));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertFalse(result.success(), result.message());
        assertTrue(result.message().contains("timeout"), result.message());
        assertTrue(elapsedMs < 3000, "took " + elapsedMs + " ms");
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 6, 0, -1})
    void maxClassOutOfRangeRejected(int maxClass) {
        ConfigException e = assertThrows(ConfigException.class,
                () -> plugin.create(config("http://example.com", maxClass)));
        assertEquals("maxClass", e.settingKey().orElseThrow());
    }

    @Test
    void maxClassRequired() {
        ConfigException e = assertThrows(ConfigException.class, () -> plugin.create(config("http://example.com", null)));
        assertEquals("maxClass", e.settingKey().orElseThrow());
    }

    @Test
    void urlRequired() {
        ConfigException e = assertThrows(ConfigException.class, () -> plugin.create(config(null, 2)));
        assertEquals("url", e.settingKey().orElseThrow());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://example.com/status",       // path
            "http://example.com/a/",           // path
            "http://example.com/?x=1",         // query
            "http://example.com?x=1",          // query
            "http://example.com/?",            // empty query
            "http://example.com/#top",         // fragment
            "ftp://example.com",               // scheme
            "file:///etc/passwd",              // scheme
            "mailto:ops@example.com",          // scheme
            "example.com",                     // not absolute
            "//example.com",                   // no scheme
            "http://",                         // no host
            "http:///",                        // no host
            "http:example.com",                // opaque
            "http://user:pw@example.com",      // user info
            "http://example.com:99999",        // port out of range
            "http://exa mple.com",             // not a URL
            "",
    })
    void invalidUrlsRejected(String url) {
        ConfigException e = assertThrows(ConfigException.class, () -> plugin.create(config(url, 2)));
        assertEquals("url", e.settingKey().orElseThrow(), e.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://example.com",
            "http://example.com/",
            "https://example.com:8443",
            "HTTPS://Example.com/",
            "http://10.0.0.5:8080",
            "http://[::1]:8080/",
    })
    void validUrlsAccepted(String url) throws Exception {
        JsonEndpointConfig config = config(url, 2);
        plugin.create(config).close();
        config.checkNoUnreadKeys();
    }

    @Test
    void requestUriIsRoot() throws Exception {
        assertEquals("http://example.com:8080/", HttpCheckPlugin.parseUrl("http://example.com:8080").toString());
        assertEquals("https://example.com/", HttpCheckPlugin.parseUrl("HTTPS://example.com/").toString());
    }

    @Test
    void declaresUrlAndHostTargets() throws Exception {
        JsonEndpointConfig config = config("http://10.0.0.5:8080", 4);
        plugin.create(config).close();
        assertEquals(List.of(new JsonEndpointConfig.Target("url", "http://10.0.0.5:8080"),
                        new JsonEndpointConfig.Target("host", "10.0.0.5")),
                config.targets());

        JsonEndpointConfig v6 = config("http://[::1]:8080", 4);
        plugin.create(v6).close();
        assertEquals("::1", v6.targets().get(1).value());
    }

    @Test
    void unknownSettingReported() throws Exception {
        JsonEndpointConfig config = config("http://example.com", 2);
        // a typo'd key is left unread
        JsonObject o = new JsonObject();
        o.addProperty("url", "http://example.com");
        o.addProperty("maxClass", 2);
        o.addProperty("maxclass", 3);
        JsonEndpointConfig typo = new JsonEndpointConfig("web", "Web", o);
        plugin.create(typo).close();
        ConfigException e = assertThrows(ConfigException.class, typo::checkNoUnreadKeys);
        assertEquals("maxclass", e.settingKey().orElseThrow());
        plugin.create(config).close();
        config.checkNoUnreadKeys();
    }

    @Test
    void sharedClient() {
        assertTrue(plugin.client() == plugin.client());
    }

    @Test
    void builtInFoundByServiceLoader() {
        assertTrue(ServiceLoader.load(CheckPlugin.class).stream()
                .anyMatch(p -> p.type() == HttpCheckPlugin.class));
        assertEquals("http", plugin.type());
        assertEquals(List.of("url", "maxClass"), plugin.settings().stream().map(s -> s.key()).toList());
    }

    @Test
    void passesRule() {
        assertTrue(HttpCheckPlugin.passes(204, 2));
        assertFalse(HttpCheckPlugin.passes(302, 2));
        assertTrue(HttpCheckPlugin.passes(599, 5));
        assertFalse(HttpCheckPlugin.passes(600, 5));
    }
}
