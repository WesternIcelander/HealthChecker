package io.siggi.healthchecker.web.page;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.siggi.healthchecker.api.CheckResult;
import io.siggi.healthchecker.config.CategoryConfig;
import io.siggi.healthchecker.config.DashboardConfig;
import io.siggi.healthchecker.config.EndpointDefinition;
import io.siggi.healthchecker.config.ServerConfig;
import io.siggi.healthchecker.config.SiteConfig;
import io.siggi.healthchecker.config.UnknownHostsPolicy;
import io.siggi.healthchecker.config.Visibility;
import io.siggi.healthchecker.history.HistoryStore;
import io.siggi.healthchecker.history.UptimeCalculator;
import io.siggi.healthchecker.plugin.JsonEndpointConfig.Target;
import io.siggi.healthchecker.schedule.Endpoint;
import io.siggi.healthchecker.status.CheckRecord;
import io.siggi.healthchecker.status.EndpointStatus;
import io.siggi.healthchecker.status.State;
import io.siggi.healthchecker.web.WebServer;
import io.siggi.healthchecker.web.layout.ResolvedLayout;
import io.siggi.healthchecker.web.site.SiteResolver;
import io.siggi.healthchecker.web.view.Viewer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The service page header and its 404 rules, over real HTTP (requests written by hand for the {@code Host} header). */
class ServicePageTest {
    private static final Instant T0 = Instant.parse("2026-11-13T09:00:00Z");
    private static final Instant NOW = Instant.parse("2026-11-13T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final DashboardConfig COLORS = new DashboardConfig("Status", 100.0, 99.0, 95.0);
    private static final SiteConfig MC = new SiteConfig(List.of("mc-status.example.com"),
            "Minecraft status", null, List.of("minecraft"), List.of());

    @TempDir
    Path dataDir;
    private final List<HistoryStore> stores = new ArrayList<>();
    private final List<WebServer> servers = new ArrayList<>();
    private final Map<String, Endpoint> endpoints = new LinkedHashMap<>();
    private final Map<String, EndpointStatus> statuses = new HashMap<>();
    private final List<CategoryConfig> categories = List.of(
            new CategoryConfig("web", "Websites", null),
            new CategoryConfig("minecraft", "Minecraft servers", null));
    private Viewer viewer = Viewer.ANONYMOUS;

    ServicePageTest() {
        add("website", "web", Visibility.PUBLIC, "https://www.example.com");
        add("intranet", "web", Visibility.PRIVATE, "http://10.0.0.5:8080");
        add("survival", "minecraft", Visibility.PUBLIC_STATUS, "mc.example.net");
        add("creative", "minecraft", Visibility.PRIVATE, "creative.example.net");
        // Recent checks mentioning the target, so the leak filter has something to do.
        for (String id : endpoints.keySet()) {
            String target = endpoints.get(id).targets().getFirst().value();
            CheckRecord pass = new CheckRecord(T0, Duration.ofMillis(84),
                    new CheckResult(true, "status 200", Map.of("statusCode", 200)));
            CheckRecord fail = new CheckRecord(T0.plusSeconds(60), Duration.ofMillis(5001),
                    new CheckResult(false, "can't reach " + target, Map.of("host", target, "statusCode", 503)));
            statuses.put(id, new EndpointStatus(id, State.DOWN, T0.plusSeconds(60), 1, 1, T0.plusSeconds(60),
                    fail.message(), fail, List.of(pass, pass, fail)));
        }
    }

    @AfterEach
    void stop() {
        servers.forEach(WebServer::close);
        stores.forEach(HistoryStore::close);
    }

    private void add(String id, String category, Visibility visibility, String target) {
        EndpointDefinition def = new EndpointDefinition(id, "Name " + id, "http", category, visibility, 60, 5000, 1,
                List.of(), List.of(), 0, new JsonObject());
        endpoints.put(id, new Endpoint(def, List.of(new Target("url", target)), timeout -> CheckResult.ok("ok")));
    }

    private WebServer start(UnknownHostsPolicy policy) throws Exception {
        ServerConfig config = new ServerConfig(0, "127.0.0.1", null, List.of(), policy);
        WebServer server = new WebServer(config);
        servers.add(server);
        SiteResolver sites = new SiteResolver(List.of(MC), "Status", null, policy, server.trustedProxies());
        List<EndpointDefinition> defs = endpoints.values().stream().map(Endpoint::definition).toList();
        HistoryStore history = HistoryStore.open(dataDir, CLOCK, System.getLogger(ServicePageTest.class.getName()));
        stores.add(history);
        UptimeCalculator uptime = new UptimeCalculator(history, ZoneOffset.UTC, false, CLOCK);
        new ServicePage(sites, () -> ResolvedLayout.resolve(null, categories, defs),
                id -> Optional.ofNullable(endpoints.get(id)), id -> Optional.ofNullable(statuses.get(id)),
                request -> viewer, uptime, COLORS).register(server);
        server.start();
        return server;
    }

    record Response(int status, String body) {
    }

    private static Response get(WebServer server, String path, String host) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", server.port())) {
            socket.setSoTimeout(5000);
            String req = "GET " + path + " HTTP/1.1\r\nHost: " + host + "\r\nConnection: close\r\n\r\n";
            OutputStream out = socket.getOutputStream();
            out.write(req.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            InputStream in = socket.getInputStream();
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            in.transferTo(buffer);
            String raw = buffer.toString(StandardCharsets.UTF_8);
            int status = Integer.parseInt(raw.substring(raw.indexOf(' ') + 1, raw.indexOf(' ') + 4));
            int split = raw.indexOf("\r\n\r\n");
            String head = raw.substring(0, split);
            String body = raw.substring(split + 4);
            if (head.toLowerCase(Locale.ROOT).contains("transfer-encoding: chunked")) {
                StringBuilder dechunked = new StringBuilder();
                int pos = 0;
                while (true) {
                    int eol = body.indexOf("\r\n", pos);
                    int size = Integer.parseInt(body.substring(pos, eol).trim(), 16);
                    if (size == 0) break;
                    dechunked.append(body, eol + 2, eol + 2 + size);
                    pos = eol + 2 + size + 2;
                }
                body = dechunked.toString();
            }
            return new Response(status, body);
        }
    }

    private static List<String> facts(String body) {
        List<String> keys = new ArrayList<>();
        Matcher m = Pattern.compile("class=\"fact fact-([a-z0-9-]+)\"").matcher(body);
        while (m.find()) keys.add(m.group(1));
        return keys;
    }

    private static List<String> strip(String body) {
        List<String> checks = new ArrayList<>();
        Matcher m = Pattern.compile("class=\"check check-(pass|fail)\"").matcher(body);
        while (m.find()) checks.add(m.group(1));
        return checks;
    }

    @Test
    void notFoundCases() throws Exception {
        WebServer server = start(UnknownHostsPolicy.DEFAULT);
        // Unknown id, malformed ids and extra path segments.
        assertEquals(404, get(server, "/service/nope", "status.example.com").status());
        assertEquals(404, get(server, "/service/", "status.example.com").status());
        assertEquals(404, get(server, "/service/Website", "status.example.com").status());
        assertEquals(404, get(server, "/service/website/", "status.example.com").status());
        assertEquals(404, get(server, "/service/website/extra", "status.example.com").status());
        // Private, anonymous: 404 (not 403), like an unknown id.
        Response privateAnon = get(server, "/service/intranet", "status.example.com");
        Response unknown = get(server, "/service/nope", "status.example.com");
        assertEquals(404, privateAnon.status());
        assertEquals(unknown.body(), privateAnon.body());
        // Logged in without view.private: still 404.
        viewer = new Viewer("bob", false);
        assertEquals(404, get(server, "/service/intranet", "status.example.com").status());
        assertEquals(200, get(server, "/service/website", "status.example.com").status());
        // With view.private: visible.
        viewer = new Viewer("alice", true);
        assertEquals(200, get(server, "/service/intranet", "status.example.com").status());

        // Out of site scope: 404 even for a privileged viewer; in scope works.
        assertEquals(404, get(server, "/service/website", "mc-status.example.com").status());
        assertEquals(404, get(server, "/service/intranet", "mc-status.example.com").status());
        assertEquals(200, get(server, "/service/survival", "mc-status.example.com").status());
        assertEquals(200, get(server, "/service/creative", "mc-status.example.com").status());
        // In scope but private: 404 for anonymous.
        viewer = Viewer.ANONYMOUS;
        assertEquals(404, get(server, "/service/creative", "mc-status.example.com").status());
        assertEquals(200, get(server, "/service/survival", "mc-status.example.com").status());
        assertEquals(404, get(server, "/service/website", "mc-status.example.com").status());
    }

    @Test
    void unknownHostRejected() throws Exception {
        WebServer server = start(UnknownHostsPolicy.REJECT);
        assertEquals(404, get(server, "/service/website", "unknown.test").status());
        assertEquals(200, get(server, "/service/survival", "mc-status.example.com").status());
    }

    @Test
    void publicHeaderFields() throws Exception {
        history("website", "2026-11-01T00:00:00Z", NOW.toString(),
                event("2026-11-13T10:00:00Z", "2026-11-13T10:30:00Z"));
        WebServer server = start(UnknownHostsPolicy.DEFAULT);
        String body = get(server, "/service/website", "status.example.com").body();
        assertTrue(body.contains("<title>Name website – Status</title>"));
        assertTrue(body.contains("<span class=\"name\">Name website</span>"));
        assertTrue(body.contains("<span class=\"state-label state-down\">Down</span> <span class=\"muted\">since 2026-11-13 09:01:00</span>"));
        assertEquals(List.of("uptime-24h", "uptime-60d", "type", "last-check", "latency", "details"), facts(body));
        assertTrue(body.contains("<dt>Uptime 24h</dt><dd>97.91%</dd>"));
        assertTrue(body.contains("<dt>Type</dt><dd>http</dd>"));
        assertTrue(body.contains("<dt>Last check</dt><dd>fail at 2026-11-13 09:01:00</dd>"));
        assertTrue(body.contains("<dt>Latency</dt><dd>5001 ms</dd>"));
        // The leaking message and detail are left out; the rest stays.
        assertTrue(body.contains("statusCode:</span> 503"));
        assertFalse(body.contains("<dt>Message</dt>"));
        // 60-day bar and the pass/fail strip with latency in its tooltips.
        assertEquals(60, count(body, "class=\"seg seg-"));
        assertTrue(body.contains("href=\"/service/website?view=calendar&amp;day=2026-11-13\""));
        assertEquals(List.of("pass", "pass", "fail"), strip(body));
        assertTrue(body.contains("title=\"2026-11-13 09:00:00: pass, 84 ms, status 200\""));
        assertTrue(body.contains("aria-label=\"3 checks: 2 passed, 1 failed\""));
        assertNoTargets(body);
    }

    @Test
    void publicStatusHeaderFields() throws Exception {
        WebServer server = start(UnknownHostsPolicy.DEFAULT);
        String body = get(server, "/service/survival", "status.example.com").body();
        assertTrue(body.contains("<span class=\"name\">Name survival</span>"));
        assertTrue(body.contains("<span class=\"state-label state-down\">Down</span>"));
        // Name, state, uptimes, bar and pass/fail only: no type, latency, message or details.
        assertEquals(List.of("uptime-24h", "uptime-60d"), facts(body));
        assertTrue(body.contains("<dt>Uptime 60d</dt><dd>—</dd>"));
        assertEquals(60, count(body, "class=\"seg seg-"));
        assertEquals(List.of("pass", "pass", "fail"), strip(body));
        assertTrue(body.contains("title=\"2026-11-13 09:01:00: fail\""));
        assertFalse(body.contains(" ms"));
        assertFalse(body.contains("status 200"));
        assertFalse(body.contains("statusCode"));
        assertNoTargets(body);

        // A logged-in user without view.private sees the same.
        viewer = new Viewer("bob", false);
        assertEquals(List.of("uptime-24h", "uptime-60d"), facts(get(server, "/service/survival", "status.example.com").body()));
    }

    @Test
    void privilegedHeaderFields() throws Exception {
        viewer = new Viewer("alice", true);
        WebServer server = start(UnknownHostsPolicy.DEFAULT);
        String body = get(server, "/service/intranet", "status.example.com").body();
        assertEquals(List.of("uptime-24h", "uptime-60d", "type", "visibility", "targets", "last-check", "latency",
                "message", "details"), facts(body));
        assertTrue(body.contains("<dt>Visibility</dt><dd>private</dd>"));
        assertTrue(body.contains("url:</span> <code>http://10.0.0.5:8080</code>"));
        assertTrue(body.contains("<dt>Message</dt><dd>can&#39;t reach http://10.0.0.5:8080</dd>"));
        assertTrue(body.contains("host:</span> http://10.0.0.5:8080"));
        // public-status endpoints show everything to a privileged viewer, too.
        assertTrue(facts(get(server, "/service/survival", "status.example.com").body()).contains("targets"));
    }

    @Test
    void noChecksYet() throws Exception {
        statuses.remove("website");
        WebServer server = start(UnknownHostsPolicy.DEFAULT);
        String body = get(server, "/service/website", "status.example.com").body();
        assertTrue(body.contains("<span class=\"state-label state-unknown\">Not checked yet</span></p>"));
        assertTrue(body.contains("No checks yet."));
        assertEquals(List.of("uptime-24h", "uptime-60d", "type"), facts(body));
    }

    private void assertNoTargets(String body) {
        String lower = body.toLowerCase(Locale.ROOT);
        for (Endpoint endpoint : endpoints.values()) {
            for (Target target : endpoint.targets()) {
                assertFalse(lower.contains(target.value().toLowerCase(Locale.ROOT)), "leaks " + target.value());
            }
        }
        assertFalse(lower.contains("www.example.com"));
    }

    private static int count(String body, String needle) {
        int n = 0;
        for (int i = body.indexOf(needle); i >= 0; i = body.indexOf(needle, i + 1)) n++;
        return n;
    }

    private void history(String id, String coverageFrom, String coverageTo, String... events) throws Exception {
        Path dir = dataDir.resolve("endpoints").resolve(id);
        Files.createDirectories(dir.resolve("events"));
        Files.writeString(dir.resolve("coverage.json"),
                "[{\"start\":\"" + coverageFrom + "\",\"end\":\"" + coverageTo + "\"}]\n");
        StringBuilder lines = new StringBuilder();
        for (String event : events) lines.append(event.replace("%ID%", id)).append('\n');
        Files.writeString(dir.resolve("events/2026.jsonl"), lines.toString());
    }

    private static String event(String start, String end) {
        JsonObject o = new JsonObject();
        o.addProperty("id", java.util.UUID.randomUUID().toString());
        o.addProperty("endpointId", "%ID%");
        o.addProperty("kind", "outage");
        o.addProperty("start", start);
        o.addProperty("end", end);
        o.addProperty("firstFailureMessage", "fail");
        o.addProperty("lastFailureMessage", "fail");
        o.addProperty("lastFailureAt", end);
        o.add("notes", new JsonArray());
        o.addProperty("endedUnobserved", false);
        o.addProperty("endedByConfigChange", false);
        return o.toString();
    }
}
