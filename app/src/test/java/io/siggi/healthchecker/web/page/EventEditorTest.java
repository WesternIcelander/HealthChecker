package io.siggi.healthchecker.web.page;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.siggi.healthchecker.api.CheckResult;
import io.siggi.healthchecker.auth.Permission;
import io.siggi.healthchecker.auth.Permissions;
import io.siggi.healthchecker.auth.Role;
import io.siggi.healthchecker.auth.SessionManager;
import io.siggi.healthchecker.auth.User;
import io.siggi.healthchecker.auth.UserDirectory;
import io.siggi.healthchecker.config.CategoryConfig;
import io.siggi.healthchecker.config.DashboardConfig;
import io.siggi.healthchecker.config.EndpointDefinition;
import io.siggi.healthchecker.config.ServerConfig;
import io.siggi.healthchecker.config.UnknownHostsPolicy;
import io.siggi.healthchecker.config.Visibility;
import io.siggi.healthchecker.history.HistoryStore;
import io.siggi.healthchecker.history.UptimeCalculator;
import io.siggi.healthchecker.plugin.JsonEndpointConfig.Target;
import io.siggi.healthchecker.schedule.Endpoint;
import io.siggi.healthchecker.web.WebServer;
import io.siggi.healthchecker.web.layout.ResolvedLayout;
import io.siggi.healthchecker.web.site.SiteResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Renaming events ({@code POST /service/{id}/event/{eventId}/title}), end to end over HTTP. */
class EventEditorTest {
    private static final Instant NOW = Instant.parse("2026-11-13T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final Pattern LOGOUT_CSRF = Pattern.compile("name=\"csrf\" value=\"([0-9a-f]{64})\"");
    private static final String EVENT = "11111111-2222-3333-4444-555555555555";
    private static final String PRIVATE_EVENT = "66666666-7777-8888-9999-000000000000";
    private static final Role ANNOTATE_ONLY = new Role("annotator", EnumSet.of(Permission.EVENTS_ANNOTATE));

    @TempDir
    Path dataDir;

    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private final Map<String, Endpoint> endpoints = new LinkedHashMap<>();
    private final List<CategoryConfig> categories = List.of(new CategoryConfig("web", "Websites", null));
    private WebServer web;
    private HistoryStore history;

    private void add(String id, Visibility visibility) {
        EndpointDefinition def = new EndpointDefinition(id, "Name " + id, "http", "web", visibility, 60, 5000, 1,
                List.of(), List.of(), 0, new JsonObject());
        endpoints.put(id, new Endpoint(def, List.of(new Target("url", "https://" + id + ".example.com")),
                timeout -> CheckResult.ok("ok")));
    }

    private void writeEvent(String endpointId, String eventId) throws Exception {
        Path dir = dataDir.resolve("endpoints").resolve(endpointId);
        Files.createDirectories(dir.resolve("events"));
        Files.writeString(dir.resolve("coverage.json"),
                "[{\"start\":\"2026-11-01T00:00:00Z\",\"end\":\"" + NOW + "\"}]\n");
        JsonObject o = new JsonObject();
        o.addProperty("id", eventId);
        o.addProperty("endpointId", endpointId);
        o.addProperty("kind", "outage");
        o.addProperty("start", "2026-11-13T10:00:00Z");
        o.addProperty("end", "2026-11-13T10:30:00Z");
        o.addProperty("firstFailureMessage", "fail");
        o.addProperty("lastFailureMessage", "fail");
        o.addProperty("lastFailureAt", "2026-11-13T10:29:00Z");
        o.add("notes", new JsonArray());
        o.addProperty("endedUnobserved", false);
        o.addProperty("endedByConfigChange", false);
        Files.writeString(dir.resolve("events/2026.jsonl"), o + "\n");
    }

    @BeforeEach
    void start() throws Exception {
        add("website", Visibility.PUBLIC);
        add("intranet", Visibility.PRIVATE);
        writeEvent("website", EVENT);
        writeEvent("intranet", PRIVATE_EVENT);
        startServer();
    }

    private void startServer() throws Exception {
        List<EndpointDefinition> defs = endpoints.values().stream().map(Endpoint::definition).toList();
        UserDirectory users = UserDirectory.of(List.of(
                new User("ed", "Ed", Role.EDITOR, null, List.of(), false),
                new User("vic", "Vic", Role.VIEWER, null, List.of(), false),
                new User("ann", "Ann", ANNOTATE_ONLY, null, List.of(), false)));
        web = new WebServer(new ServerConfig(0, "127.0.0.1", null, List.of(), UnknownHostsPolicy.DEFAULT));
        SessionManager sessions = new SessionManager(dataDir.resolve("sessions"), Duration.ofDays(7));
        Permissions permissions = new Permissions(sessions, users::user);
        SiteResolver sites = new SiteResolver(List.of(), "Status", null, UnknownHostsPolicy.DEFAULT,
                web.trustedProxies());
        history = HistoryStore.open(dataDir, CLOCK, System.getLogger(EventEditorTest.class.getName()));
        UptimeCalculator uptime = new UptimeCalculator(history, ZoneOffset.UTC, false, CLOCK);
        web.register("/test-login", request -> {
            sessions.login(request, request.get().get("user"));
            PageSupport.write(request, "200 OK", "text/plain; charset=utf-8", "ok");
        });
        new ServicePage(sites, () -> ResolvedLayout.resolve(null, categories, defs),
                id -> Optional.ofNullable(endpoints.get(id)), id -> Optional.empty(),
                permissions::viewer, uptime, history, CLOCK, new DashboardConfig("Status", 100.0, 99.0, 95.0),
                new EventEditor(permissions, sessions, history)).register(web);
        web.start();
    }

    private void restart() throws Exception {
        web.close();
        history.close();
        startServer();
    }

    @AfterEach
    void stop() {
        if (web != null) web.close();
        if (history != null) history.close();
    }

    // ---- HTTP helpers ----

    record Session(String cookie, String csrf) {
    }

    private HttpResponse<String> get(String path, Session session) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + web.port() + path))
                .timeout(Duration.ofSeconds(10));
        if (session != null) b.header("Cookie", session.cookie());
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> rename(String service, String event, String title, String next, Session session,
                                        String csrf) throws Exception {
        StringBuilder form = new StringBuilder("title=").append(URLEncoder.encode(title, StandardCharsets.UTF_8));
        if (next != null) form.append("&next=").append(URLEncoder.encode(next, StandardCharsets.UTF_8));
        if (csrf != null) form.append("&csrf=").append(csrf);
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + web.port()
                        + "/service/" + service + "/event/" + event + "/title"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form.toString()));
        if (session != null) b.header("Cookie", session.cookie());
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> rename(String title, Session session) throws Exception {
        return rename("website", EVENT, title, null, session, session.csrf());
    }

    private Session login(String user) throws Exception {
        HttpResponse<String> r = get("/test-login?user=" + user, null);
        assertEquals(200, r.statusCode());
        String cookie = null;
        for (String c : r.headers().allValues("Set-Cookie")) {
            if (c.startsWith("SessID=")) cookie = c.substring(0, c.indexOf(';'));
        }
        assertNotNull(cookie);
        Matcher m = LOGOUT_CSRF.matcher(get("/service/website", new Session(cookie, null)).body());
        assertTrue(m.find());
        return new Session(cookie, m.group(1));
    }

    private String apiTitle(String event) throws Exception {
        HttpResponse<String> r = get("/api/service/website/event/" + event, null);
        assertEquals(200, r.statusCode());
        return JsonParser.parseString(r.body()).getAsJsonObject().get("title").getAsString();
    }

    private String storedTitle() throws Exception {
        String line = Files.readString(dataDir.resolve("endpoints/website/events/2026.jsonl")).strip();
        JsonObject o = JsonParser.parseString(line).getAsJsonObject();
        return o.has("title") && !o.get("title").isJsonNull() ? o.get("title").getAsString() : null;
    }

    // ---- tests ----

    @Test
    void renameIsVisiblePubliclyAndPersisted() throws Exception {
        Session ed = login("ed");
        HttpResponse<String> r = rename("website", EVENT, "  DNS <outage>  ", "/service/website?view=list&page=0#event-" + EVENT,
                ed, ed.csrf());
        assertEquals(303, r.statusCode());
        assertEquals("/service/website?view=list&page=0#event-" + EVENT, r.headers().firstValue("Location").orElseThrow());
        // Written before answering.
        assertEquals("DNS <outage>", storedTitle());

        // Anonymous visitors see the new title (escaped) in the list, on the permalink and in the JSON.
        String list = get("/service/website", null).body();
        assertTrue(list.contains("<span class=\"event-title\">DNS &lt;outage&gt;</span>"));
        assertFalse(list.contains("Name website outage"));
        assertFalse(list.contains("rename-form"), "anonymous visitors get no rename form");
        String page = get("/service/website/event/" + EVENT, null).body();
        assertTrue(page.contains("<h2 class=\"event-page-title\">DNS &lt;outage&gt;</h2>"));
        assertEquals("DNS <outage>", apiTitle(EVENT));

        // Survives a restart.
        restart();
        assertEquals("DNS <outage>", apiTitle(EVENT));
    }

    @Test
    void emptyTitleResetsToDefault() throws Exception {
        Session ed = login("ed");
        assertEquals(303, rename("Custom", ed).statusCode());
        assertEquals("Custom", apiTitle(EVENT));
        // Without next: back to the permalink.
        HttpResponse<String> r = rename("   ", ed);
        assertEquals(303, r.statusCode());
        assertEquals("/service/website/event/" + EVENT, r.headers().firstValue("Location").orElseThrow());
        assertEquals("Name website outage", apiTitle(EVENT));
        assertEquals(null, storedTitle());
        assertTrue(get("/service/website", null).body().contains("<span class=\"event-title\">Name website outage</span>"));
    }

    @Test
    void formsShownOnlyWithPermission() throws Exception {
        Session ed = login("ed");
        assertEquals(303, rename("Custom", ed).statusCode());
        String list = get("/service/website", ed).body();
        assertTrue(list.contains("class=\"rename-form\""));
        assertTrue(list.contains("action=\"/service/website/event/" + EVENT + "/title\""));
        assertTrue(list.contains("name=\"title\" maxlength=\"200\" value=\"Custom\" placeholder=\"Name website outage\""));
        assertTrue(list.contains("name=\"csrf\" value=\"" + ed.csrf() + "\""));
        assertTrue(get("/service/website/event/" + EVENT, ed).body().contains("class=\"rename-form\""));
        assertFalse(get("/service/website", login("vic")).body().contains("rename-form"));
        // Without view.private the stored custom title still prefills the form.
        assertTrue(get("/service/website", login("ann")).body().contains("value=\"Custom\""));
    }

    @Test
    void withoutPermissionForbidden() throws Exception {
        Session vic = login("vic");
        assertEquals(403, rename("Nope", vic).statusCode());
        assertEquals("Name website outage", apiTitle(EVENT));
        // Anonymous: sent to the sign-in page.
        HttpResponse<String> anon = rename("website", EVENT, "Nope", null, null, null);
        assertEquals(303, anon.statusCode());
        assertTrue(anon.headers().firstValue("Location").orElseThrow().startsWith("/login?next="));
        assertEquals("Name website outage", apiTitle(EVENT));
    }

    @Test
    void csrfAndValidation() throws Exception {
        Session ed = login("ed");
        assertEquals(403, rename("website", EVENT, "Nope", null, ed, null).statusCode());
        assertEquals(403, rename("website", EVENT, "Nope", null, ed, "0".repeat(64)).statusCode());
        assertEquals(400, rename("x".repeat(201), ed).statusCode());
        assertEquals(400, rename("a\nb", ed).statusCode());
        assertEquals("Name website outage", apiTitle(EVENT));
        assertEquals(303, rename("x".repeat(200), ed).statusCode());
        // GET is not allowed.
        assertEquals(405, get("/service/website/event/" + EVENT + "/title", ed).statusCode());
    }

    @Test
    void hiddenAndUnknownEventsAreNotFound() throws Exception {
        Session ann = login("ann"); // events.annotate without view.private
        assertEquals(404, rename("intranet", PRIVATE_EVENT, "Nope", null, ann, ann.csrf()).statusCode());
        assertEquals(404, rename("website", PRIVATE_EVENT, "Nope", null, ann, ann.csrf()).statusCode());
        assertEquals(404, rename("website", "nope", "Nope", null, ann, ann.csrf()).statusCode());
        assertEquals(303, rename("Fine", ann).statusCode());
        Session ed = login("ed");
        assertEquals(303, rename("intranet", PRIVATE_EVENT, "Private", null, ed, ed.csrf()).statusCode());
    }

    @Test
    void nextMustStayOnSite() throws Exception {
        Session ed = login("ed");
        HttpResponse<String> r = rename("website", EVENT, "X", "//evil.example.com/", ed, ed.csrf());
        assertEquals(303, r.statusCode());
        assertEquals("/", r.headers().firstValue("Location").orElseThrow());
    }
}
