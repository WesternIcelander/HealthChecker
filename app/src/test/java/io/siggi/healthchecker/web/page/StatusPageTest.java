package io.siggi.healthchecker.web.page;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
import java.time.LocalDate;
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

/**
 * The main page and {@code /api/status} (site scoping, overall state, day bars, no targets), over real HTTP. Requests are written by
 * hand because {@link java.net.http.HttpClient} won't send a custom {@code Host} header.
 */
class StatusPageTest {
    private static final Instant T0 = Instant.parse("2026-11-13T09:00:00Z");
    private static final Instant NOW = Instant.parse("2026-11-13T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final DashboardConfig DEFAULT_COLORS = new DashboardConfig("Status", 100.0, 99.0, 95.0);

    @TempDir
    Path dataDir;
    private final List<HistoryStore> stores = new ArrayList<>();
    private DashboardConfig dashboard = DEFAULT_COLORS;

    private final Map<String, Endpoint> endpoints = new LinkedHashMap<>();
    private final Map<String, EndpointStatus> statuses = new HashMap<>();
    private final List<CategoryConfig> categories = List.of(
            new CategoryConfig("web", "Websites", null),
            new CategoryConfig("minecraft", "Minecraft servers", null));
    private final List<WebServer> servers = new ArrayList<>();
    private Viewer viewer = Viewer.ANONYMOUS;

    private static final SiteConfig MC = new SiteConfig(List.of("mc-status.example.com", "*.mc.example.com"),
            "Minecraft status", "https://mc-status.example.com", List.of("minecraft"), List.of());
    private static final SiteConfig WEB = new SiteConfig(List.of("web-status.example.com"), "Website status",
            null, List.of(), List.of("website"));

    StatusPageTest() {
        add("website", "web", Visibility.PUBLIC, State.UP, "https://www.example.com");
        add("intranet", "web", Visibility.PRIVATE, State.DOWN, "http://10.0.0.5:8080");
        add("survival", "minecraft", Visibility.PUBLIC_STATUS, State.DOWN, "mc.example.net");
        add("creative", "minecraft", Visibility.PUBLIC, State.UP, "creative.example.net");
    }

    @AfterEach
    void stop() {
        servers.forEach(WebServer::close);
        stores.forEach(HistoryStore::close);
    }

    private void add(String id, String category, Visibility visibility, State state, String target) {
        EndpointDefinition def = new EndpointDefinition(id, "Name " + id, "http", category, visibility, 60, 5000, 1,
                List.of(), List.of(), 0, new JsonObject());
        // Declared like the http check does: the URL as written, plus its host.
        List<Target> targets = new ArrayList<>(List.of(new Target("url", target)));
        String host = java.net.URI.create(target.contains("://") ? target : "x://" + target).getHost();
        if (host != null && !host.equals(target)) targets.add(new Target("host", host));
        endpoints.put(id, new Endpoint(def, targets, timeout -> CheckResult.ok("ok")));
        setStatus(id, state, null);
    }

    private void setStatus(String id, State state, CheckRecord last) {
        statuses.put(id, new EndpointStatus(id, state, state == State.UNKNOWN ? null : T0, 1, 0, null, null, last,
                last == null ? List.of() : List.of(last)));
    }

    private WebServer start(UnknownHostsPolicy policy, String baseUrl, List<String> trustedProxies) throws Exception {
        ServerConfig config = new ServerConfig(0, "127.0.0.1", baseUrl, trustedProxies, policy);
        WebServer server = new WebServer(config);
        servers.add(server);
        SiteResolver sites = new SiteResolver(List.of(MC, WEB), "Status", baseUrl, policy, server.trustedProxies());
        List<EndpointDefinition> defs = endpoints.values().stream().map(Endpoint::definition).toList();
        HistoryStore history = HistoryStore.open(dataDir, CLOCK, System.getLogger(StatusPageTest.class.getName()));
        stores.add(history);
        UptimeCalculator uptime = new UptimeCalculator(history, ZoneOffset.UTC, false, CLOCK);
        new StatusPage(sites, () -> ResolvedLayout.resolve(null, categories, defs),
                id -> Optional.ofNullable(endpoints.get(id)), id -> Optional.ofNullable(statuses.get(id)),
                request -> viewer, uptime, dashboard).register(server);
        server.start();
        return server;
    }

    private WebServer start(UnknownHostsPolicy policy) throws Exception {
        return start(policy, null, List.of());
    }

    record Response(int status, String body) {
    }

    private static Response get(WebServer server, String path, String host, String... headers) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", server.port())) {
            socket.setSoTimeout(5000);
            StringBuilder req = new StringBuilder("GET " + path + " HTTP/1.1\r\n");
            if (host != null) req.append("Host: ").append(host).append("\r\n");
            for (int i = 0; i < headers.length; i += 2) {
                req.append(headers[i]).append(": ").append(headers[i + 1]).append("\r\n");
            }
            req.append("Connection: close\r\n\r\n");
            OutputStream out = socket.getOutputStream();
            out.write(req.toString().getBytes(StandardCharsets.ISO_8859_1));
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
                body = dechunk(body);
            }
            return new Response(status, body);
        }
    }

    private static String dechunk(String body) {
        StringBuilder out = new StringBuilder();
        int pos = 0;
        while (true) {
            int eol = body.indexOf("\r\n", pos);
            int size = Integer.parseInt(body.substring(pos, eol).trim(), 16);
            if (size == 0) return out.toString();
            out.append(body, eol + 2, eol + 2 + size);
            pos = eol + 2 + size + 2;
        }
    }

    private static List<String> apiIds(Response response) {
        JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
        List<String> ids = new ArrayList<>();
        for (JsonElement category : root.getAsJsonArray("categories")) {
            JsonArray services = category.getAsJsonObject().getAsJsonArray("endpoints");
            for (JsonElement service : services) {
                ids.add(service.getAsJsonObject().get("id").getAsString());
            }
        }
        return ids;
    }

    private static List<String> pageIds(Response response) {
        List<String> ids = new ArrayList<>();
        Matcher m = Pattern.compile("href=\"[^\"]*/service/([a-z0-9-]+)\"").matcher(response.body());
        while (m.find()) ids.add(m.group(1));
        return ids;
    }

    private static String overall(Response response) {
        return JsonParser.parseString(response.body()).getAsJsonObject().get("overall").getAsString();
    }

    @Test
    void scopingOnMainPageAndApi() throws Exception {
        WebServer server = start(UnknownHostsPolicy.DEFAULT);

        Response mcApi = get(server, "/api/status", "mc-status.example.com");
        assertEquals(List.of("survival", "creative"), apiIds(mcApi));
        assertEquals("DOWN", overall(mcApi));
        assertEquals(503, mcApi.status());
        Response mcPage = get(server, "/", "mc-status.example.com");
        assertEquals(200, mcPage.status());
        assertEquals(List.of("survival", "creative"), pageIds(mcPage));
        assertTrue(mcPage.body().contains("<title>Minecraft status</title>"));
        assertFalse(mcPage.body().contains("Websites"));

        // Overall state and status code are computed within the scope: the down services are elsewhere.
        Response webApi = get(server, "/api/status", "web-status.example.com");
        assertEquals(List.of("website"), apiIds(webApi));
        assertEquals("UP", overall(webApi));
        assertEquals(200, webApi.status());
        Response webPage = get(server, "/", "web-status.example.com");
        assertEquals(List.of("website"), pageIds(webPage));
        assertTrue(webPage.body().contains("<title>Website status</title>"));
        assertTrue(webPage.body().contains("All systems operational"));

        // Default site: everything visible, top-level title.
        Response all = get(server, "/api/status", "status.example.com");
        assertEquals(List.of("website", "survival", "creative"), apiIds(all));
        Response allPage = get(server, "/", "status.example.com");
        assertEquals(List.of("website", "survival", "creative"), pageIds(allPage));
        assertTrue(allPage.body().contains("<title>Status</title>"));
    }

    @Test
    void visibilityAppliesOnTopOfScopeEvenWhenLoggedIn() throws Exception {
        WebServer server = start(UnknownHostsPolicy.DEFAULT);
        // Anonymous: the private endpoint is hidden on the default site.
        assertFalse(apiIds(get(server, "/api/status", "status.example.com")).contains("intranet"));

        viewer = new Viewer("alice", true);
        assertEquals(List.of("website", "intranet", "survival", "creative"),
                apiIds(get(server, "/api/status", "status.example.com")));
        // The scope still applies to a privileged viewer.
        assertEquals(List.of("survival", "creative"), apiIds(get(server, "/api/status", "mc-status.example.com")));
        assertEquals(List.of("website"), pageIds(get(server, "/", "web-status.example.com")));
    }

    @Test
    void wildcardAndPortHandling() throws Exception {
        WebServer server = start(UnknownHostsPolicy.REJECT);
        assertEquals(List.of("survival", "creative"), apiIds(get(server, "/api/status", "MC-Status.Example.com:8080")));
        assertEquals(List.of("survival", "creative"), apiIds(get(server, "/api/status", "eu.mc.example.com")));
        assertEquals(List.of("survival", "creative"), pageIds(get(server, "/", "a.b.mc.example.com:443")));
        assertEquals(404, get(server, "/api/status", "mc.example.com").status());
        assertEquals(404, get(server, "/", "evilmc.example.com").status());
    }

    @Test
    void unknownHostDefaultVsReject() throws Exception {
        WebServer lenient = start(UnknownHostsPolicy.DEFAULT);
        assertEquals(List.of("website", "survival", "creative"), apiIds(get(lenient, "/api/status", "unknown.test")));
        assertEquals(200, get(lenient, "/", "127.0.0.1:1234").status());

        WebServer strict = start(UnknownHostsPolicy.REJECT);
        assertEquals(404, get(strict, "/api/status", "unknown.test").status());
        assertEquals(404, get(strict, "/", "unknown.test").status());
        assertEquals(404, get(strict, "/", "127.0.0.1").status());
        // /health stays available for load balancers that use an IP address.
        assertEquals(200, get(strict, "/health", "127.0.0.1").status());
        assertEquals(200, get(strict, "/", "web-status.example.com").status());
    }

    @Test
    void forwardedHostIgnoredFromUntrustedIps() throws Exception {
        // SiggiHTTP itself trusts loopback for X-Forwarded-Host; HealthChecker must not.
        WebServer untrusted = start(UnknownHostsPolicy.REJECT, null, List.of("10.0.0.0/8"));
        assertEquals(404, get(untrusted, "/api/status", "internal:8080",
                "X-Forwarded-Host", "mc-status.example.com").status());
        assertEquals(List.of("website"), apiIds(get(untrusted, "/api/status", "web-status.example.com",
                "X-Forwarded-Host", "mc-status.example.com")));

        WebServer trusted = start(UnknownHostsPolicy.REJECT, null, List.of("127.0.0.1"));
        assertEquals(List.of("survival", "creative"), apiIds(get(trusted, "/api/status", "internal:8080",
                "X-Forwarded-Host", "mc-status.example.com")));
        assertEquals(List.of("website"), pageIds(get(trusted, "/", "mc-status.example.com",
                "X-Forwarded-Host", "web-status.example.com:443")));
    }

    @Test
    void linksNeverBuiltFromHostHeader() throws Exception {
        WebServer server = start(UnknownHostsPolicy.DEFAULT, "https://status.example.com", List.of("127.0.0.1"));
        String evil = "evil.attacker.test";

        // Unknown host → default site, links from server.publicBaseUrl.
        Response page = get(server, "/", evil);
        assertFalse(page.body().contains(evil));
        assertTrue(page.body().contains("href=\"https://status.example.com/service/website\""));
        assertTrue(page.body().contains("href=\"https://status.example.com/static/style.css\""));
        assertFalse(get(server, "/api/status", evil).body().contains(evil));

        // Matched site with its own publicBaseUrl: that one, not the Host header (with its port).
        Response mc = get(server, "/", "mc-status.example.com:9999");
        assertFalse(mc.body().contains(":9999"));
        assertTrue(mc.body().contains("href=\"https://mc-status.example.com/service/survival\""));
        // A matched wildcard host never ends up in a link either.
        Response wildcard = get(server, "/", "evil.mc.example.com");
        assertFalse(wildcard.body().contains("evil.mc.example.com"));

        // Matched site without publicBaseUrl falls back to the server default.
        Response web = get(server, "/", "web-status.example.com");
        assertFalse(web.body().contains("web-status.example.com"));
        assertTrue(web.body().contains("href=\"https://status.example.com/service/website\""));

        // Via a trusted proxy, the forwarded host selects the site but isn't echoed either.
        Response forwarded = get(server, "/", "internal:8080", "X-Forwarded-Host", evil);
        assertFalse(forwarded.body().contains(evil));
        assertFalse(forwarded.body().contains("internal"));
    }

    @Test
    void linksAreRootRelativeWithoutBaseUrl() throws Exception {
        WebServer server = start(UnknownHostsPolicy.DEFAULT);
        Response page = get(server, "/", "somewhere.test");
        assertTrue(page.body().contains("href=\"/service/website\""));
        assertFalse(page.body().contains("somewhere.test"));
    }

    @Test
    void noTargetsInAnonymousOutput() throws Exception {
        // Make the public endpoint's last check and its history mention its target: the leak filter must drop them.
        setStatus("website", State.DOWN, new CheckRecord(T0, Duration.ofMillis(84), new CheckResult(false,
                "GET https://www.example.com failed", Map.of("host", "www.example.com", "statusCode", 503))));
        history("website", "2026-11-01T00:00:00Z", NOW.toString(),
                event("outage", "2026-11-05T10:00:00Z", "2026-11-05T10:10:00Z", "can't reach www.example.com"));
        history("survival", "2026-11-01T00:00:00Z", NOW.toString(),
                event("outage", "2026-11-06T10:00:00Z", "2026-11-06T10:10:00Z", "mc.example.net refused"));
        WebServer server = start(UnknownHostsPolicy.DEFAULT);
        List<String> needles = new ArrayList<>();
        for (Endpoint endpoint : endpoints.values()) {
            for (Target target : endpoint.targets()) {
                needles.add(target.value());
                java.net.URI uri = java.net.URI.create(target.value().contains("://") ? target.value() : "x://" + target.value());
                if (uri.getHost() != null) needles.add(uri.getHost());
            }
        }
        assertTrue(needles.contains("10.0.0.5") && needles.contains("www.example.com"));
        for (String path : List.of("/", "/api/status")) {
            String body = get(server, path, "status.example.com").body();
            for (String needle : needles) {
                assertFalse(body.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT)), path + " leaks " + needle);
            }
        }
        // The rest of the last check is still there for the public endpoint.
        JsonObject website = apiEndpoint(get(server, "/api/status", "status.example.com"), "website");
        JsonObject last = website.getAsJsonObject("last");
        assertFalse(last.has("message"));
        assertEquals(503, last.getAsJsonObject("details").get("statusCode").getAsInt());
        assertFalse(last.getAsJsonObject("details").has("host"));
        assertEquals(84, last.get("latencyMs").getAsLong());
        assertFalse(website.has("targets"));
        assertFalse(website.has("visibility"));
        // public-status: no last check, no type.
        JsonObject survival = apiEndpoint(get(server, "/api/status", "status.example.com"), "survival");
        assertFalse(survival.has("last"));
        assertFalse(survival.has("type"));

        // Sanity check of the search: a privileged viewer does get the targets.
        viewer = new Viewer("alice", true);
        String privileged = get(server, "/api/status", "status.example.com").body();
        assertTrue(privileged.contains("http://10.0.0.5:8080"));
        assertTrue(privileged.contains("GET https://www.example.com failed"));
    }

    @Test
    void overallStateAndStatusCodeFromVisibleEndpointsOnly() throws Exception {
        // Only the private endpoint is down.
        setStatus("survival", State.UP, null);
        WebServer server = start(UnknownHostsPolicy.DEFAULT);
        Response api = get(server, "/api/status", "status.example.com");
        assertEquals(200, api.status());
        assertEquals("UP", overall(api));
        Response page = get(server, "/", "status.example.com");
        assertEquals(200, page.status());
        assertTrue(page.body().contains("class=\"banner banner-up\""));
        assertTrue(page.body().contains("All systems operational"));

        viewer = new Viewer("alice", true);
        api = get(server, "/api/status", "status.example.com");
        assertEquals(503, api.status());
        assertEquals("DOWN", overall(api));
        page = get(server, "/", "status.example.com");
        assertEquals(200, page.status());
        assertTrue(page.body().contains("class=\"banner banner-down\""));
        assertTrue(page.body().contains("1 service down"));

        // A logged-in user without view.private gets the anonymous result.
        viewer = new Viewer("bob", false);
        assertEquals(200, get(server, "/api/status", "status.example.com").status());

        // Not checked yet: UNKNOWN, still 200.
        viewer = Viewer.ANONYMOUS;
        setStatus("creative", State.UNKNOWN, null);
        api = get(server, "/api/status", "status.example.com");
        assertEquals(200, api.status());
        assertEquals("UNKNOWN", overall(api));
        assertTrue(get(server, "/", "status.example.com").body().contains("Waiting for the first checks"));

        // Two visible services down.
        setStatus("survival", State.DOWN, null);
        setStatus("website", State.DOWN, null);
        assertEquals(503, get(server, "/api/status", "status.example.com").status());
        assertTrue(get(server, "/", "status.example.com").body().contains("2 services down"));
    }

    /** Fixture for the bar tests: monitored from 2026-09-25, outages on 1, 2, 3 and 5 October, maintenance on the 4th. */
    private void barFixture() throws Exception {
        history("website", "2026-09-25T00:00:00Z", NOW.toString(),
                event("outage", "2026-10-01T10:00:00Z", "2026-10-01T10:10:00Z", "fail"),       // 99.30 %
                event("outage", "2026-10-02T10:00:00Z", "2026-10-02T12:00:00Z", "fail"),       // 91.66 %
                event("outage", "2026-10-03T10:00:00Z", "2026-10-03T10:30:00Z", "fail"),       // 97.91 %
                event("maintenance", "2026-10-04T10:00:00Z", "2026-10-04T11:00:00Z", "fail"),  // planned
                event("outage", "2026-10-05T10:00:00Z", "2026-10-05T10:00:05Z", "fail"));      // 99.99 %
    }

    /** Expected colours: 60 days ending today (2026-11-13), the first 10 without data. */
    private static List<String> expectedColors(Map<String, String> special) {
        List<String> colors = new ArrayList<>();
        LocalDate first = LocalDate.parse("2026-09-15");
        for (int i = 0; i < 60; i++) {
            LocalDate date = first.plusDays(i);
            colors.add(special.getOrDefault(date.toString(), date.isBefore(LocalDate.parse("2026-09-25")) ? "grey" : "green"));
        }
        return colors;
    }

    @Test
    void barHasSixtySegmentsWithColorsForFixtureData() throws Exception {
        barFixture();
        WebServer server = start(UnknownHostsPolicy.DEFAULT);
        List<String> expected = expectedColors(Map.of(
                "2026-10-01", "yellow", "2026-10-02", "red", "2026-10-03", "orange",
                "2026-10-04", "blue", "2026-10-05", "yellow"));

        Response page = get(server, "/", "status.example.com");
        assertEquals(expected, pageColors(page, "website"));
        // Services without history: 60 grey segments, uptime "—".
        assertEquals(java.util.Collections.nCopies(60, "grey"), pageColors(page, "creative"));
        String creative = serviceHtml(page, "creative");
        assertTrue(creative.contains("<span class=\"label\">24h</span> —"));
        String website = serviceHtml(page, "website");
        assertTrue(website.contains("<span class=\"label\">24h</span> 100%"));
        assertTrue(website.contains("<span class=\"label\">60d</span> 99.77%"));
        assertTrue(website.contains("<span class=\"dot state-up\">"));
        // Tooltip and link of one segment.
        assertTrue(website.contains("href=\"/service/website?view=calendar&amp;day=2026-10-02\" "
                + "title=\"2026-10-02: 91.66% uptime, 2h down, 1 outage\""));
        assertTrue(website.contains("title=\"2026-10-01: 99.3% uptime, 10m down, 1 outage\""));
        assertTrue(website.contains("title=\"2026-10-04: 100% uptime, no downtime, 0 outages, 1 planned maintenance\""));
        assertTrue(website.contains("title=\"2026-09-15: no data\""));

        JsonObject api = apiEndpoint(get(server, "/api/status", "status.example.com"), "website");
        JsonArray days = api.getAsJsonArray("days");
        assertEquals(60, days.size());
        List<String> apiColors = new ArrayList<>();
        for (JsonElement day : days) apiColors.add(day.getAsJsonObject().get("color").getAsString());
        assertEquals(expected, apiColors);
        JsonObject oct2 = days.get(17).getAsJsonObject();
        assertEquals("2026-10-02", oct2.get("date").getAsString());
        assertEquals(91.66, oct2.get("uptime").getAsDouble());
        assertEquals(7200, oct2.get("downtimeSeconds").getAsLong());
        assertEquals(1, oct2.get("events").getAsInt());
        assertTrue(days.get(0).getAsJsonObject().get("uptime").isJsonNull());
        assertEquals("2026-11-13", days.get(59).getAsJsonObject().get("date").getAsString());
        assertEquals(100.0, api.getAsJsonObject("uptime").get("24h").getAsDouble());
        assertEquals(99.77, api.getAsJsonObject("uptime").get("60d").getAsDouble());
        JsonObject none = apiEndpoint(get(server, "/api/status", "status.example.com"), "survival");
        assertTrue(none.getAsJsonObject("uptime").get("60d").isJsonNull());
    }

    @Test
    void barColorThresholdsAreConfigurable() throws Exception {
        barFixture();
        dashboard = new DashboardConfig("Status", 99.5, 98.0, 90.0);
        WebServer server = start(UnknownHostsPolicy.DEFAULT);
        List<String> expected = expectedColors(Map.of(
                "2026-10-01", "yellow", "2026-10-02", "orange", "2026-10-03", "orange",
                "2026-10-04", "blue", "2026-10-05", "green"));
        assertEquals(expected, pageColors(get(server, "/", "status.example.com"), "website"));
    }

    @Test
    void pageStructure() throws Exception {
        WebServer server = start(UnknownHostsPolicy.DEFAULT);
        String body = get(server, "/", "status.example.com").body();
        assertTrue(body.contains("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"));
        assertTrue(body.contains("<meta http-equiv=\"refresh\" content=\"60\">"));
        assertTrue(body.contains("<h2>Websites</h2><span class=\"summary summary-ok\">All operational</span>"));
        assertTrue(body.contains("<h2>Minecraft servers</h2><span class=\"summary summary-down\">1 of 2 down</span>"));
        assertTrue(body.contains("60 days ago</span><span>today</span>"));
    }

    // ---- fixture and parsing helpers ----

    private void history(String id, String coverageFrom, String coverageTo, String... events) throws Exception {
        Path dir = dataDir.resolve("endpoints").resolve(id);
        Files.createDirectories(dir.resolve("events"));
        Files.writeString(dir.resolve("coverage.json"),
                "[{\"start\":\"" + coverageFrom + "\",\"end\":\"" + coverageTo + "\"}]\n");
        StringBuilder lines = new StringBuilder();
        for (String event : events) lines.append(event.replace("%ID%", id)).append('\n');
        Files.writeString(dir.resolve("events/2026.jsonl"), lines.toString());
    }

    private static String event(String kind, String start, String end, String message) {
        JsonObject o = new JsonObject();
        o.addProperty("id", java.util.UUID.randomUUID().toString());
        o.addProperty("endpointId", "%ID%");
        o.addProperty("kind", kind);
        o.addProperty("start", start);
        o.addProperty("end", end);
        o.addProperty("firstFailureMessage", message);
        o.addProperty("lastFailureMessage", message);
        o.addProperty("lastFailureAt", end);
        o.add("notes", new JsonArray());
        o.addProperty("endedUnobserved", false);
        o.addProperty("endedByConfigChange", false);
        return o.toString();
    }

    private static String serviceHtml(Response page, String id) {
        int start = page.body().indexOf("data-service=\"" + id + "\"");
        assertTrue(start >= 0, "no row for " + id);
        return page.body().substring(start, page.body().indexOf("</li>", start));
    }

    private static List<String> pageColors(Response page, String id) {
        List<String> colors = new ArrayList<>();
        Matcher m = Pattern.compile("class=\"seg seg-([a-z]+)\"").matcher(serviceHtml(page, id));
        while (m.find()) colors.add(m.group(1));
        return colors;
    }

    private static JsonObject apiEndpoint(Response response, String id) {
        JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
        for (JsonElement category : root.getAsJsonArray("categories")) {
            for (JsonElement service : category.getAsJsonObject().getAsJsonArray("endpoints")) {
                if (service.getAsJsonObject().get("id").getAsString().equals(id)) return service.getAsJsonObject();
            }
        }
        throw new AssertionError("no endpoint " + id);
    }

    @Test
    void otherPathsStillNotFound() throws Exception {
        WebServer server = start(UnknownHostsPolicy.DEFAULT);
        assertEquals(404, get(server, "/nothing-here", "status.example.com").status());
        assertEquals(404, get(server, "/api/status/extra", "status.example.com").status());
    }
}
