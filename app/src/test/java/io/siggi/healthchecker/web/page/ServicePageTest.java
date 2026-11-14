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
import io.siggi.healthchecker.status.StateChange;
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
import java.time.YearMonth;
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
 * The service page (header, list view, event permalink page) and its 404 rules, over real HTTP
 * (requests written by hand for the {@code Host} header).
 */
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
    private HistoryStore history;

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
        history = HistoryStore.open(dataDir, CLOCK, System.getLogger(ServicePageTest.class.getName()));
        stores.add(history);
        UptimeCalculator uptime = new UptimeCalculator(history, ZoneOffset.UTC, false, CLOCK);
        new ServicePage(sites, () -> ResolvedLayout.resolve(null, categories, defs),
                id -> Optional.ofNullable(endpoints.get(id)), id -> Optional.ofNullable(statuses.get(id)),
                request -> viewer, uptime, history, CLOCK, COLORS).register(server);
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

    /** The fact keys of the header (or of the event page), not those in the event list. */
    private static List<String> facts(String body) {
        List<String> keys = new ArrayList<>();
        int list = body.indexOf("<section class=\"events\"");
        Matcher m = Pattern.compile("class=\"fact fact-([a-z0-9-]+)\"").matcher(list < 0 ? body : body.substring(0, list));
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

    // ---- list view ----

    private static List<String> eventIds(String body) {
        List<String> ids = new ArrayList<>();
        Matcher m = Pattern.compile("<li class=\"event[^\"]*\" id=\"event-([^\"]+)\"").matcher(body);
        while (m.find()) ids.add(m.group(1));
        return ids;
    }

    private static String[] numberedEvents(int count) {
        String[] events = new String[count];
        Instant first = Instant.parse("2026-01-01T00:00:00Z");
        for (int i = 0; i < count; i++) {
            Instant start = first.plus(Duration.ofDays(i));
            events[i] = event(String.format("ev-%02d", i), start.toString(), start.plusSeconds(600).toString());
        }
        return events;
    }

    @Test
    void listPaging() throws Exception {
        history("website", "2026-01-01T00:00:00Z", NOW.toString(), numberedEvents(30));
        WebServer server = start(UnknownHostsPolicy.DEFAULT);

        String page0 = get(server, "/service/website", "status.example.com").body();
        List<String> ids0 = eventIds(page0);
        assertEquals(25, ids0.size());
        assertEquals("ev-29", ids0.getFirst()); // newest first
        assertEquals("ev-05", ids0.getLast());
        assertTrue(page0.contains("<a rel=\"next\" href=\"/service/website?view=list&amp;page=1#events\">Older →</a>"));
        assertFalse(page0.contains("rel=\"prev\""));
        assertTrue(page0.contains("Page 1 of 2"));
        // ?view=list is the same as the default.
        assertEquals(ids0, eventIds(get(server, "/service/website?view=list&page=0", "status.example.com").body()));

        String page1 = get(server, "/service/website?view=list&page=1", "status.example.com").body();
        assertEquals(List.of("ev-04", "ev-03", "ev-02", "ev-01", "ev-00"), eventIds(page1));
        assertTrue(page1.contains("<a rel=\"prev\" href=\"/service/website?view=list&amp;page=0#events\">← Newer</a>"));
        assertFalse(page1.contains("rel=\"next\""));
        assertTrue(page1.contains("Page 2 of 2"));

        // Past the last page: the last page. Invalid or negative: the first.
        assertEquals(eventIds(page1), eventIds(get(server, "/service/website?page=99", "status.example.com").body()));
        assertEquals(ids0, eventIds(get(server, "/service/website?page=abc", "status.example.com").body()));
        assertEquals(ids0, eventIds(get(server, "/service/website?page=-1", "status.example.com").body()));
        assertEquals(ids0, eventIds(get(server, "/service/website?page=99999999999", "status.example.com").body()));
    }

    @Test
    void listWithoutPagingOrEvents() throws Exception {
        history("website", "2026-01-01T00:00:00Z", NOW.toString(), numberedEvents(25));
        WebServer server = start(UnknownHostsPolicy.DEFAULT);
        String body = get(server, "/service/website", "status.example.com").body();
        assertEquals(25, eventIds(body).size());
        assertFalse(body.contains("class=\"pager\""));
        String empty = get(server, "/service/survival", "status.example.com").body();
        assertTrue(empty.contains("No downtime recorded."));
        assertEquals(List.of(), eventIds(empty));
    }

    @Test
    void listDurations() throws Exception {
        history("website", "2026-11-01T00:00:00Z", NOW.toString(),
                event("e-45s", "2026-11-02T00:00:00Z", "2026-11-02T00:00:45Z"),
                event("e-12m", "2026-11-03T00:00:00Z", "2026-11-03T00:12:30Z"),
                event("e-1h12m", "2026-11-04T00:00:00Z", "2026-11-04T01:12:59Z"),
                event("e-2d3h", "2026-11-05T00:00:00Z", "2026-11-07T03:30:00Z"));
        WebServer server = start(UnknownHostsPolicy.DEFAULT);
        // An ongoing outage, opened through the store as the scheduler would; its duration runs until now.
        Instant failed = NOW.minus(Duration.ofMinutes(90));
        CheckRecord fail = new CheckRecord(failed, Duration.ofMillis(10), new CheckResult(false, "status 503", Map.of()));
        history.record("website", fail, new StateChange("website", State.UP, State.DOWN, fail, failed, "status 503", 1));

        String body = get(server, "/service/website", "status.example.com").body();
        assertEquals(5, eventIds(body).size());
        assertEquals(List.of("ongoing", "2d 3h", "1h 12m", "12m", "45s"), durations(body).stream()
                .map(d -> d.end() == null ? "ongoing" : d.duration()).toList());
        assertEquals("1h 30m", durations(body).getFirst().duration());
        assertTrue(body.contains("<li class=\"event event-ongoing\""));
        assertTrue(body.contains("<span class=\"event-when\"><time datetime=\"2026-11-04T00:00:00Z\">2026-11-04 00:00:00</time>"
                + " – <span class=\"event-end\"><time datetime=\"2026-11-04T01:12:59Z\">2026-11-04 01:12:59</time></span></span>"));
        assertTrue(body.contains("– <span class=\"event-end\">ongoing</span>"));
        assertTrue(body.contains("<span class=\"event-note-count\">0 notes</span>"));
    }

    record Shown(String end, String duration) {
    }

    private static List<Shown> durations(String body) {
        List<Shown> shown = new ArrayList<>();
        Matcher m = Pattern.compile("<span class=\"event-end\">(.*?)</span></span><span class=\"event-duration\">"
                + "<span class=\"visually-hidden\">Duration: </span>([^<]*)</span>").matcher(body);
        while (m.find()) shown.add(new Shown(m.group(1).equals("ongoing") ? null : m.group(1), m.group(2)));
        return shown;
    }

    private static final String PUBLIC_NOTE = "Public <b>note</b>\nsecond line";
    private static final String PRIVATE_NOTE = "Secret private remark";

    private void notedHistory(String id) throws Exception {
        history(id, "2026-11-01T00:00:00Z", NOW.toString(),
                event("ev-" + id, "2026-11-10T08:00:00Z", "2026-11-10T09:12:00Z",
                        note("n-pub", "alice", PUBLIC_NOTE, "public", "2026-11-10T10:00:00Z"),
                        note("n-priv", "alice", PRIVATE_NOTE, "private", null)));
    }

    @Test
    void anonymousSeesOnlyPublicNotes() throws Exception {
        notedHistory("website");
        notedHistory("survival");
        notedHistory("intranet");
        WebServer server = start(UnknownHostsPolicy.DEFAULT);
        for (Viewer v : List.of(Viewer.ANONYMOUS, new Viewer("bob", false))) {
            viewer = v;
            for (String path : List.of("/service/website", "/service/website/event/ev-website",
                    "/service/survival", "/service/survival/event/ev-survival")) {
                String body = get(server, path, "status.example.com").body();
                assertTrue(body.contains("<p class=\"note-text\">Public &lt;b&gt;note&lt;/b&gt;\nsecond line</p>"), path);
                assertTrue(body.contains("<span class=\"note-author\">alice</span>"), path);
                assertTrue(body.contains("(edited <time datetime=\"2026-11-10T10:00:00Z\">"), path);
                assertFalse(body.contains(PRIVATE_NOTE), path);
                assertFalse(body.contains("n-priv"), path);
                assertFalse(body.contains("class=\"badge"), path); // note visibility isn't shown either
                if (!path.contains("/event/")) {
                    // The private note isn't counted either.
                    assertTrue(body.contains("<span class=\"event-note-count\">1 note</span>"), path);
                }
                assertNoTargets(body);
            }
            assertEquals(404, get(server, "/service/intranet/event/ev-intranet", "status.example.com").status());
        }

        viewer = new Viewer("alice", true);
        String body = get(server, "/service/website", "status.example.com").body();
        assertTrue(body.contains(PRIVATE_NOTE));
        assertTrue(body.contains("<span class=\"event-note-count\">2 notes</span>"));
        assertTrue(body.contains("<span class=\"badge badge-private\">private</span>"));
        assertTrue(body.contains("<span class=\"badge badge-public\">public</span>"));
        assertTrue(get(server, "/service/intranet/event/ev-intranet", "status.example.com").body().contains(PRIVATE_NOTE));
    }

    @Test
    void failureMessagesPerVisibility() throws Exception {
        String leaking = event("ev-1", "2026-11-10T08:00:00Z", "2026-11-10T09:00:00Z")
                .replace("\"firstFailureMessage\":\"fail\"", "\"firstFailureMessage\":\"can't reach https://www.example.com\"")
                .replace("\"lastFailureMessage\":\"fail\"", "\"lastFailureMessage\":\"status 503\"");
        history("website", "2026-11-01T00:00:00Z", NOW.toString(), leaking);
        history("survival", "2026-11-01T00:00:00Z", NOW.toString(),
                event("ev-2", "2026-11-10T08:00:00Z", "2026-11-10T09:00:00Z"));
        WebServer server = start(UnknownHostsPolicy.DEFAULT);
        String body = get(server, "/service/website", "status.example.com").body();
        assertFalse(body.contains("First failure"));
        assertTrue(body.contains("<dt>Last failure</dt><dd>status 503</dd>"));
        assertNoTargets(body);
        String status = get(server, "/service/survival/event/ev-2", "status.example.com").body();
        assertFalse(status.contains("failure</dt>"));

        viewer = new Viewer("alice", true);
        assertTrue(get(server, "/service/website/event/ev-1", "status.example.com").body()
                .contains("<dt>First failure</dt><dd>can&#39;t reach https://www.example.com</dd>"));
    }

    @Test
    void eventPermalinkPage() throws Exception {
        history("website", "2026-11-01T00:00:00Z", NOW.toString(),
                event("ev-1", "2026-11-10T08:00:00Z", "2026-11-10T09:12:00Z"),
                event("ev-2", "2026-11-11T08:00:00Z", "2026-11-11T08:05:00Z")
                        .replace("\"kind\"", "\"title\":\"Database <migration>\",\"kind\"")
                        .replace("\"endedUnobserved\":false", "\"endedUnobserved\":true"));
        history("survival", "2026-11-01T00:00:00Z", NOW.toString(),
                event("ev-3", "2026-11-10T08:00:00Z", "2026-11-10T09:00:00Z"));
        WebServer server = start(UnknownHostsPolicy.DEFAULT);

        // The list links each event to its permalink.
        String list = get(server, "/service/website", "status.example.com").body();
        assertTrue(list.contains("<a href=\"/service/website/event/ev-1\">Permalink</a>"));
        assertTrue(list.contains("<span class=\"event-title\">Name website outage</span>"));
        assertTrue(list.contains("<span class=\"event-title\">Database &lt;migration&gt;</span>"));

        Response page = get(server, "/service/website/event/ev-1", "status.example.com");
        assertEquals(200, page.status());
        assertTrue(page.body().contains("<title>Name website outage – Status</title>"));
        assertTrue(page.body().contains("<h2 class=\"event-page-title\">Name website outage</h2>"));
        assertEquals(List.of("service", "start", "end", "duration", "first-failure", "last-failure"), facts(page.body()));
        assertTrue(page.body().contains("<dt>Start</dt><dd>2026-11-10 08:00:00</dd>"));
        assertTrue(page.body().contains("<dt>Duration</dt><dd>1h 12m</dd>"));
        assertTrue(page.body().contains("<a href=\"/service/website\">Name website</a>"));
        assertTrue(page.body().contains("No notes."));
        // How it ended is for privileged viewers only.
        assertFalse(get(server, "/service/website/event/ev-2", "status.example.com").body().contains("event-flag"));
        viewer = new Viewer("alice", true);
        assertTrue(get(server, "/service/website/event/ev-2", "status.example.com").body()
                .contains("Recovery not observed"));
        viewer = Viewer.ANONYMOUS;

        // 404: unknown event, another endpoint's event, bad ids, extra segments, out of scope.
        assertEquals(404, get(server, "/service/website/event/nope", "status.example.com").status());
        assertEquals(404, get(server, "/service/website/event/ev-3", "status.example.com").status());
        assertEquals(200, get(server, "/service/survival/event/ev-3", "status.example.com").status());
        assertEquals(404, get(server, "/service/website/event/", "status.example.com").status());
        assertEquals(404, get(server, "/service/website/event/ev.1", "status.example.com").status());
        assertEquals(404, get(server, "/service/website/event/ev-1/x", "status.example.com").status());
        assertEquals(404, get(server, "/service/website/events/ev-1", "status.example.com").status());
        assertEquals(404, get(server, "/service/nope/event/ev-1", "status.example.com").status());
        assertEquals(404, get(server, "/service/website/event/ev-1", "mc-status.example.com").status());
        assertEquals(200, get(server, "/service/survival/event/ev-3", "mc-status.example.com").status());
    }

    // ---- calendar view ----

    private static List<String> months(String body) {
        List<String> months = new ArrayList<>();
        Matcher m = Pattern.compile("<div class=\"cal-month\" data-month=\"([0-9-]+)\">").matcher(body);
        while (m.find()) months.add(m.group(1));
        return months;
    }

    /** Colour class of each day block of one month, in order. */
    private static List<String> dayColors(String body, String month) {
        int start = body.indexOf("data-month=\"" + month + "\"");
        assertTrue(start >= 0, month);
        int end = body.indexOf("</div>\n</div>", start);
        List<String> colors = new ArrayList<>();
        Matcher m = Pattern.compile("<(?:a|span) class=\"cal-day cal-([a-z]+)\"(?: href=\"[^\"]*\")? data-date=\"" + month
                + "-[0-9]{2}\"[^>]*></(?:a|span)>").matcher(body.substring(start, end));
        while (m.find()) colors.add(m.group(1));
        return colors;
    }

    private static int blanks(String body, String month) {
        int start = body.indexOf("data-month=\"" + month + "\"");
        int end = body.indexOf("class=\"cal-day ", start);
        return count(body.substring(start, end), "class=\"cal-blank\"");
    }

    @Test
    void calendarMonthRanges() {
        YearMonth nov = YearMonth.of(2026, 11);
        assertEquals(List.of(YearMonth.of(2026, 9), YearMonth.of(2026, 10), nov), ServicePage.calendarMonths(nov, 0));
        assertEquals(List.of(YearMonth.of(2026, 6), YearMonth.of(2026, 7), YearMonth.of(2026, 8)),
                ServicePage.calendarMonths(nov, 1));
        assertEquals(List.of(YearMonth.of(2026, 3), YearMonth.of(2026, 4), YearMonth.of(2026, 5)),
                ServicePage.calendarMonths(nov, 2));
        // Year rollover inside page 0 and between pages.
        YearMonth jan = YearMonth.of(2027, 1);
        assertEquals(List.of(YearMonth.of(2026, 11), YearMonth.of(2026, 12), jan), ServicePage.calendarMonths(jan, 0));
        assertEquals(List.of(YearMonth.of(2026, 8), YearMonth.of(2026, 9), YearMonth.of(2026, 10)),
                ServicePage.calendarMonths(jan, 1));
        assertEquals(List.of(YearMonth.of(2026, 1), YearMonth.of(2026, 2), YearMonth.of(2026, 3)),
                ServicePage.calendarMonths(YearMonth.of(2026, 3), 0));
        assertEquals(List.of(YearMonth.of(2025, 10), YearMonth.of(2025, 11), YearMonth.of(2025, 12)),
                ServicePage.calendarMonths(YearMonth.of(2026, 3), 1));
    }

    @Test
    void calendarPaging() throws Exception {
        WebServer server = start(UnknownHostsPolicy.DEFAULT);
        String page0 = get(server, "/service/website?view=calendar", "status.example.com").body();
        assertEquals(List.of("2026-09", "2026-10", "2026-11"), months(page0));
        assertTrue(page0.contains("<h4 class=\"cal-month-title\">September 2026</h4>"));
        assertTrue(page0.contains("<a rel=\"next\" href=\"/service/website?view=calendar&amp;page=1#calendar\">← Earlier</a>"));
        assertFalse(page0.contains("Later"));
        assertEquals(page0, get(server, "/service/website?view=calendar&page=0", "status.example.com").body());

        String page1 = get(server, "/service/website?view=calendar&page=1", "status.example.com").body();
        assertEquals(List.of("2026-06", "2026-07", "2026-08"), months(page1));
        assertTrue(page1.contains("<a rel=\"prev\" href=\"/service/website?view=calendar&amp;page=0#calendar\">Later →</a>"));
        assertTrue(page1.contains("<a rel=\"next\" href=\"/service/website?view=calendar&amp;page=2#calendar\">← Earlier</a>"));

        String page2 = get(server, "/service/website?view=calendar&page=2", "status.example.com").body();
        assertEquals(List.of("2026-03", "2026-04", "2026-05"), months(page2));
        assertTrue(page2.contains("href=\"/service/website?view=calendar&amp;page=1#calendar\">Later →</a>"));

        // Year rollover: page 3 spans Dec 2025 – Feb 2026, page 4 is Sep – Nov 2025.
        assertEquals(List.of("2025-12", "2026-01", "2026-02"),
                months(get(server, "/service/website?view=calendar&page=3", "status.example.com").body()));
        assertEquals(List.of("2025-09", "2025-10", "2025-11"),
                months(get(server, "/service/website?view=calendar&page=4", "status.example.com").body()));

        // Invalid page numbers show page 0; huge ones the last page, without "Earlier".
        assertEquals(months(page0), months(get(server, "/service/website?view=calendar&page=-1", "status.example.com").body()));
        String last = get(server, "/service/website?view=calendar&page=999999999", "status.example.com").body();
        assertEquals(List.of("1026-09", "1026-10", "1026-11"), months(last));
        assertFalse(last.contains("Earlier"));
        assertTrue(last.contains("page=3999#calendar\">Later →</a>"));
    }

    @Test
    void calendarTabs() throws Exception {
        WebServer server = start(UnknownHostsPolicy.DEFAULT);
        String list = get(server, "/service/website", "status.example.com").body();
        assertTrue(list.contains("<a class=\"tab tab-current\" href=\"/service/website?view=list\" aria-current=\"page\">List</a>"
                + "<a class=\"tab\" href=\"/service/website?view=calendar\">Calendar</a>"));
        assertFalse(list.contains("class=\"calendar\""));
        String calendar = get(server, "/service/website?view=calendar", "status.example.com").body();
        assertTrue(calendar.contains("<a class=\"tab\" href=\"/service/website?view=list\">List</a>"
                + "<a class=\"tab tab-current\" href=\"/service/website?view=calendar\" aria-current=\"page\">Calendar</a>"));
        assertFalse(calendar.contains("class=\"events\""));
        // Unknown views show the list.
        assertTrue(get(server, "/service/website?view=nope", "status.example.com").body().contains("class=\"events\""));
        // The 404 rules apply to the calendar too.
        assertEquals(404, get(server, "/service/intranet?view=calendar", "status.example.com").status());
        assertEquals(404, get(server, "/service/website?view=calendar", "mc-status.example.com").status());
    }

    @Test
    void calendarDays() throws Exception {
        history("website", "2026-10-15T00:00:00Z", NOW.toString(),
                event("2026-11-02T00:00:00Z", "2026-11-02T00:10:00Z"),   // 99.3 % → yellow
                event("2026-11-03T00:00:00Z", "2026-11-03T02:00:00Z"),   // 91.6 % → red
                event("2026-11-04T00:00:00Z", "2026-11-04T01:00:00Z"));  // 95.8 % → orange
        WebServer server = start(UnknownHostsPolicy.DEFAULT);
        String body = get(server, "/service/website?view=calendar", "status.example.com").body();

        // September: entirely before the first coverage, so all grey.
        List<String> sep = dayColors(body, "2026-09");
        assertEquals(30, sep.size());
        assertTrue(sep.stream().allMatch("grey"::equals), sep.toString());
        // October: grey until the 14th, green from the 15th when coverage starts.
        List<String> oct = dayColors(body, "2026-10");
        assertEquals(31, oct.size());
        assertTrue(oct.subList(0, 14).stream().allMatch("grey"::equals), oct.toString());
        assertTrue(oct.subList(14, 31).stream().allMatch("green"::equals), oct.toString());
        // November: coloured up to today (the 13th), future days afterwards.
        List<String> nov = dayColors(body, "2026-11");
        assertEquals(30, nov.size());
        assertEquals(List.of("green", "yellow", "red", "orange", "green"), nov.subList(0, 5));
        assertEquals("green", nov.get(12));
        assertEquals(17, nov.subList(13, 30).size());
        assertTrue(nov.subList(13, 30).stream().allMatch("future"::equals), nov.toString());
        // Days up to today link to their day page; future days aren't links.
        assertTrue(body.contains("<a class=\"cal-day cal-yellow\" href=\"/service/website/day/2026-11-02\" data-date=\"2026-11-02\""
                + " title=\"2026-11-02: 99.3% uptime, 10m down, 1 outage\""));
        assertTrue(body.contains("href=\"/service/website/day/2026-09-10\""));
        assertFalse(body.contains("/day/2026-11-14"));
        assertTrue(body.contains("data-date=\"2026-11-20\" role=\"img\" title=\"2026-11-20: upcoming\""));
        assertTrue(body.contains("title=\"2026-09-10: no data\""));

        // Monday-first grid: Sep 2026 starts on a Tuesday, Oct on a Thursday, Nov on a Sunday.
        assertTrue(body.contains("<span class=\"cal-weekday\" aria-hidden=\"true\">Mo</span>"
                + "<span class=\"cal-weekday\" aria-hidden=\"true\">Tu</span>"));
        assertTrue(body.contains("aria-hidden=\"true\">Su</span><span class=\"cal-blank\""));
        assertEquals(1, blanks(body, "2026-09"));
        assertEquals(3, blanks(body, "2026-10"));
        assertEquals(6, blanks(body, "2026-11"));
        // Colour only: no text inside any day block.
        assertEquals(30 + 31 + 30, count(body, "class=\"cal-day "));
        Matcher empty = Pattern.compile("<(a|span) class=\"cal-day [^>]*></\\1>").matcher(body);
        assertEquals(30 + 31 + 30, empty.results().count());

        // A page entirely before the first coverage still renders, all grey.
        String old = get(server, "/service/website?view=calendar&page=5", "status.example.com").body();
        assertEquals(List.of("2025-06", "2025-07", "2025-08"), months(old));
        for (String month : months(old)) {
            assertTrue(dayColors(old, month).stream().allMatch("grey"::equals), month);
        }
        // An endpoint without any coverage: everything up to today grey.
        String none = get(server, "/service/survival?view=calendar", "status.example.com").body();
        assertTrue(dayColors(none, "2026-11").subList(0, 13).stream().allMatch("grey"::equals));
        assertNoTargets(body);
        assertNoTargets(none);
    }

    // ---- day page, ?day= and popover content ----

    /** The section with a day's details, from the day page or the ?day= panel. */
    private static String dayDetail(String body) {
        int start = body.indexOf("<section class=\"day-detail\"");
        assertTrue(start >= 0, "no day detail");
        return body.substring(start, body.indexOf("</section>", start));
    }

    private static List<String> dayEventIds(String detail) {
        List<String> ids = new ArrayList<>();
        Matcher m = Pattern.compile("<li class=\"day-event[^\"]*\" data-event=\"([^\"]+)\"").matcher(detail);
        while (m.find()) ids.add(m.group(1));
        return ids;
    }

    @Test
    void eventSpanningTwoDaysIsClippedOnBoth() throws Exception {
        history("website", "2026-11-01T00:00:00Z", NOW.toString(),
                event("ev-span", "2026-11-10T22:00:00Z", "2026-11-11T01:30:00Z"),
                event("ev-other", "2026-11-11T08:00:00Z", "2026-11-11T08:10:00Z"),
                event("ev-earlier", "2026-11-09T08:00:00Z", "2026-11-09T08:10:00Z"));
        WebServer server = start(UnknownHostsPolicy.DEFAULT);

        Response first = get(server, "/service/website/day/2026-11-10", "status.example.com");
        assertEquals(200, first.status());
        String day10 = dayDetail(first.body());
        assertEquals(List.of("ev-span"), dayEventIds(day10));
        assertTrue(day10.contains("<span class=\"day-event-when\"><time datetime=\"2026-11-10T22:00:00Z\">22:00:00</time>"
                + " – <span class=\"event-end\"><time datetime=\"2026-11-11T00:00:00Z\">24:00:00</time></span></span>"), day10);
        assertTrue(day10.contains("Duration on this day: </span>2h</span>"), day10);
        assertTrue(day10.contains("Whole outage: <time datetime=\"2026-11-10T22:00:00Z\">2026-11-10 22:00:00</time> – "
                + "<time datetime=\"2026-11-11T01:30:00Z\">2026-11-11 01:30:00</time>, 3h 30m"), day10);
        assertTrue(day10.contains("<dt>Uptime</dt><dd>91.66%</dd>"), day10);
        assertTrue(day10.contains("<dt>Downtime</dt><dd>2h</dd>"), day10);
        assertTrue(day10.contains("<dt>Outages</dt><dd>1</dd>"), day10);
        assertTrue(day10.contains("<a href=\"/service/website/event/ev-span\">Name website outage</a>"), day10);
        assertTrue(day10.contains("Tuesday, 10 November 2026"), day10);
        assertTrue(first.body().contains("<a href=\"/service/website?view=calendar&amp;page=0#calendar\">Calendar</a>"));

        String day11 = dayDetail(get(server, "/service/website/day/2026-11-11", "status.example.com").body());
        assertEquals(List.of("ev-span", "ev-other"), dayEventIds(day11)); // oldest first
        assertTrue(day11.contains("<span class=\"day-event-when\"><time datetime=\"2026-11-11T00:00:00Z\">00:00:00</time>"
                + " – <span class=\"event-end\"><time datetime=\"2026-11-11T01:30:00Z\">01:30:00</time></span></span>"), day11);
        assertTrue(day11.contains("Duration on this day: </span>1h 30m</span>"), day11);
        assertTrue(day11.contains("<dt>Downtime</dt><dd>1h 40m</dd>"), day11);
        assertTrue(day11.contains("<dt>Outages</dt><dd>2</dd>"), day11);
        // The unclipped event has no "whole outage" line.
        assertEquals(1, count(day11, "Whole outage"));

        String day12 = dayDetail(get(server, "/service/website/day/2026-11-12", "status.example.com").body());
        assertEquals(List.of(), dayEventIds(day12));
        assertTrue(day12.contains("No downtime on this day."));
        assertTrue(day12.contains("<dt>Uptime</dt><dd>100%</dd>"));
        assertTrue(day12.contains("<dt>Downtime</dt><dd>none</dd>"));
        // Before the first coverage: no data.
        String before = dayDetail(get(server, "/service/website/day/2026-10-01", "status.example.com").body());
        assertTrue(before.contains("<dt>Uptime</dt><dd>—</dd>"));
        assertNoTargets(first.body());
    }

    @Test
    void ongoingEventOnTodayAndPastDays() throws Exception {
        history("website", "2026-11-01T00:00:00Z", NOW.toString());
        WebServer server = start(UnknownHostsPolicy.DEFAULT);
        Instant failed = Instant.parse("2026-11-12T20:00:00Z");
        CheckRecord fail = new CheckRecord(failed, Duration.ofMillis(10), new CheckResult(false, "status 503", Map.of()));
        history.record("website", fail, new StateChange("website", State.UP, State.DOWN, fail, failed, "status 503", 1));

        String yesterday = dayDetail(get(server, "/service/website/day/2026-11-12", "status.example.com").body());
        assertTrue(yesterday.contains("<time datetime=\"2026-11-12T20:00:00Z\">20:00:00</time> – <span class=\"event-end\">"
                + "<time datetime=\"2026-11-13T00:00:00Z\">24:00:00</time></span>"), yesterday);
        assertTrue(yesterday.contains("Duration on this day: </span>4h</span>"));
        assertTrue(yesterday.contains("– ongoing, 16h"), yesterday);
        String today = dayDetail(get(server, "/service/website/day/2026-11-13", "status.example.com").body());
        assertTrue(today.contains("<li class=\"day-event event-ongoing\""), today);
        assertTrue(today.contains("<time datetime=\"2026-11-13T00:00:00Z\">00:00:00</time> – <span class=\"event-end\">ongoing</span>"), today);
        assertTrue(today.contains("Duration on this day: </span>12h</span>"), today);
    }

    @Test
    void dayPageNotFoundCases() throws Exception {
        WebServer server = start(UnknownHostsPolicy.DEFAULT);
        assertEquals(200, get(server, "/service/website/day/2026-11-13", "status.example.com").status());
        assertEquals(200, get(server, "/service/survival/day/2026-11-13", "status.example.com").status());
        // Malformed or impossible dates, the future, extra segments.
        for (String date : List.of("2026-11-1", "2026-13-01", "2026-02-30", "20261113", "+2026-11-13", "2026-11-14", "9999-12-31")) {
            assertEquals(404, get(server, "/service/website/day/" + date, "status.example.com").status(), date);
        }
        assertEquals(404, get(server, "/service/website/day/", "status.example.com").status());
        assertEquals(404, get(server, "/service/website/day/2026-11-13/x", "status.example.com").status());
        // Private (anonymous), out of scope, unknown id.
        assertEquals(404, get(server, "/service/intranet/day/2026-11-13", "status.example.com").status());
        assertEquals(404, get(server, "/service/website/day/2026-11-13", "mc-status.example.com").status());
        assertEquals(404, get(server, "/service/nope/day/2026-11-13", "status.example.com").status());
        viewer = new Viewer("alice", true);
        assertEquals(200, get(server, "/service/intranet/day/2026-11-13", "status.example.com").status());
    }

    @Test
    void dayShowsOnlyVisibleNotes() throws Exception {
        notedHistory("website");
        WebServer server = start(UnknownHostsPolicy.DEFAULT);
        for (String path : List.of("/service/website/day/2026-11-10", "/service/website?day=2026-11-10")) {
            String detail = dayDetail(get(server, path, "status.example.com").body());
            assertTrue(detail.contains("<p class=\"note-text\">Public &lt;b&gt;note&lt;/b&gt;\nsecond line</p>"), path);
            assertFalse(detail.contains(PRIVATE_NOTE), path);
        }
        viewer = new Viewer("alice", true);
        assertTrue(dayDetail(get(server, "/service/website/day/2026-11-10", "status.example.com").body()).contains(PRIVATE_NOTE));
    }

    @Test
    void dayParameterOpensTheRightCalendarPage() throws Exception {
        history("website", "2026-06-01T00:00:00Z", NOW.toString(),
                event("ev-aug", "2026-08-15T10:00:00Z", "2026-08-15T10:30:00Z"),
                event("ev-span", "2026-11-10T22:00:00Z", "2026-11-11T01:30:00Z"));
        WebServer server = start(UnknownHostsPolicy.DEFAULT);

        // The main-page bar's link: page 0 holds Sep–Nov, with the day's details open.
        String nov = get(server, "/service/website?view=calendar&day=2026-11-11", "status.example.com").body();
        assertEquals(List.of("2026-09", "2026-10", "2026-11"), months(nov));
        assertTrue(nov.contains("<a class=\"tab tab-current\" href=\"/service/website?view=calendar\" aria-current=\"page\">Calendar</a>"));
        assertTrue(nov.contains("class=\"cal-day cal-red cal-selected\" href=\"/service/website/day/2026-11-11\" data-date=\"2026-11-11\""
                + " aria-current=\"date\""), nov);
        assertEquals(1, count(nov, "cal-selected"));
        String detail = dayDetail(nov);
        assertTrue(detail.contains("data-date=\"2026-11-11\""));
        assertEquals(List.of("ev-span"), dayEventIds(detail));
        assertTrue(detail.contains("<time datetime=\"2026-11-11T00:00:00Z\">00:00:00</time>"), detail);
        assertTrue(nov.contains("<p class=\"day-close\"><a href=\"/service/website?view=calendar&amp;page=0#calendar\">Close</a></p>"));
        // It matches the day page's content.
        assertEquals(dayDetail(get(server, "/service/website/day/2026-11-11", "status.example.com").body())
                .replace("<h2 ", "<h3 ").replace("</h2>", "</h3>"), detail);

        // An older day: the page containing it (page 1 = Jun–Aug), regardless of ?page=.
        String aug = get(server, "/service/website?view=calendar&page=3&day=2026-08-15", "status.example.com").body();
        assertEquals(List.of("2026-06", "2026-07", "2026-08"), months(aug));
        assertEquals(List.of("ev-aug"), dayEventIds(dayDetail(aug)));
        assertTrue(aug.contains("data-date=\"2026-08-15\" aria-current=\"date\""));
        // ?day= alone opens the calendar too.
        assertEquals(List.of("2026-03", "2026-04", "2026-05"),
                months(get(server, "/service/website?day=2026-03-01", "status.example.com").body()));

        // Invalid or future days are ignored: no details, the requested (or first) page.
        for (String query : List.of("?view=calendar&day=nope", "?view=calendar&day=2026-11-14", "?view=calendar&day=2026-02-30")) {
            String body = get(server, "/service/website" + query, "status.example.com").body();
            assertEquals(List.of("2026-09", "2026-10", "2026-11"), months(body), query);
            assertFalse(body.contains("class=\"day-detail\""), query);
            assertFalse(body.contains("cal-selected"), query);
        }
        assertEquals(List.of("2026-06", "2026-07", "2026-08"),
                months(get(server, "/service/website?view=calendar&page=1&day=2027-01-01", "status.example.com").body()));
        // A list view without ?day= has no details; ?day= on a private service is still 404.
        assertFalse(get(server, "/service/website", "status.example.com").body().contains("day-detail"));
        assertEquals(404, get(server, "/service/intranet?day=2026-11-11", "status.example.com").status());
        assertNoTargets(nov);
    }

    @Test
    void calendarPageForDay() {
        YearMonth nov = YearMonth.of(2026, 11);
        assertEquals(0, ServicePage.calendarPageFor(nov, java.time.LocalDate.of(2026, 11, 13)));
        assertEquals(0, ServicePage.calendarPageFor(nov, java.time.LocalDate.of(2026, 9, 1)));
        assertEquals(1, ServicePage.calendarPageFor(nov, java.time.LocalDate.of(2026, 8, 31)));
        assertEquals(1, ServicePage.calendarPageFor(nov, java.time.LocalDate.of(2026, 6, 1)));
        assertEquals(2, ServicePage.calendarPageFor(nov, java.time.LocalDate.of(2026, 5, 31)));
        assertEquals(4, ServicePage.calendarPageFor(nov, java.time.LocalDate.of(2025, 9, 1)));
        assertEquals(ServicePage.MAX_CALENDAR_PAGE, ServicePage.calendarPageFor(nov, java.time.LocalDate.of(1, 1, 1)));
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
        return event(java.util.UUID.randomUUID().toString(), start, end);
    }

    private static String event(String id, String start, String end, JsonObject... notes) {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("endpointId", "%ID%");
        o.addProperty("kind", "outage");
        o.addProperty("start", start);
        o.addProperty("end", end);
        o.addProperty("firstFailureMessage", "fail");
        o.addProperty("lastFailureMessage", "fail");
        o.addProperty("lastFailureAt", end);
        JsonArray array = new JsonArray();
        for (JsonObject note : notes) array.add(note);
        o.add("notes", array);
        o.addProperty("endedUnobserved", false);
        o.addProperty("endedByConfigChange", false);
        return o.toString();
    }

    private static JsonObject note(String id, String author, String text, String visibility, String editedAt) {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("author", author);
        o.addProperty("createdAt", "2026-11-10T09:30:00Z");
        if (editedAt != null) o.addProperty("editedAt", editedAt);
        o.addProperty("text", text);
        o.addProperty("visibility", visibility);
        return o;
    }
}
