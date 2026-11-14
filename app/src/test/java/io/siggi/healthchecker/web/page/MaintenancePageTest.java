package io.siggi.healthchecker.web.page;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.siggi.healthchecker.api.CheckResult;
import io.siggi.healthchecker.audit.AuditLog;
import io.siggi.healthchecker.auth.Permission;
import io.siggi.healthchecker.auth.Permissions;
import io.siggi.healthchecker.auth.Role;
import io.siggi.healthchecker.auth.SessionManager;
import io.siggi.healthchecker.auth.User;
import io.siggi.healthchecker.auth.UserDirectory;
import io.siggi.healthchecker.config.CategoryConfig;
import io.siggi.healthchecker.config.DashboardConfig;
import io.siggi.healthchecker.config.EndpointDefinition;
import io.siggi.healthchecker.config.MaintenanceSettings;
import io.siggi.healthchecker.config.ServerConfig;
import io.siggi.healthchecker.config.UnknownHostsPolicy;
import io.siggi.healthchecker.config.Visibility;
import io.siggi.healthchecker.history.EventNote;
import io.siggi.healthchecker.history.HistoryStore;
import io.siggi.healthchecker.history.UptimeCalculator;
import io.siggi.healthchecker.maintenance.MaintenanceStore;
import io.siggi.healthchecker.maintenance.MaintenanceWindow;
import io.siggi.healthchecker.plugin.JsonEndpointConfig.Target;
import io.siggi.healthchecker.schedule.Endpoint;
import io.siggi.healthchecker.status.EndpointStatus;
import io.siggi.healthchecker.status.State;
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
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Scheduled maintenance in the dashboard and the JSON API, end to end over HTTP: the maintenance pages and
 * forms, the "Upcoming maintenance" box, blue days, the "Planned" badge and filter, and the visibility rules.
 */
class MaintenancePageTest {
    private static final Instant NOW = Instant.parse("2026-11-13T12:00:00Z");
    private static final Pattern CSRF = Pattern.compile("name=\"csrf\" value=\"([0-9a-f]{64})\"");
    private static final MaintenanceStore.Actor ED = new MaintenanceStore.Actor("ed", "Ed");
    private static final Role MANAGE_ONLY = new Role("planner", EnumSet.of(Permission.MAINTENANCE_MANAGE));

    @TempDir
    Path dataDir;

    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private final MutableClock clock = new MutableClock(NOW);
    private final Map<String, Endpoint> endpoints = new LinkedHashMap<>();
    private final Map<String, EndpointStatus> statuses = new HashMap<>();
    private final List<CategoryConfig> categories = List.of(
            new CategoryConfig("web", "Websites", null),
            new CategoryConfig("internal", "Internal", null));
    private WebServer web;
    private HistoryStore history;
    private MaintenanceStore store;

    static final class MutableClock extends Clock {
        private volatile Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void set(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private void add(String id, String category, Visibility visibility) {
        EndpointDefinition def = new EndpointDefinition(id, "Name " + id, "http", category, visibility, 60, 5000, 1,
                List.of(), List.of(), 0, new JsonObject());
        endpoints.put(id, new Endpoint(def, List.of(new Target("url", "https://" + id + ".example.com")),
                timeout -> CheckResult.ok("ok")));
    }

    private void coverage(String id) throws Exception {
        Path dir = dataDir.resolve("endpoints").resolve(id);
        Files.createDirectories(dir.resolve("events"));
        Files.writeString(dir.resolve("coverage.json"),
                "[{\"start\":\"2026-11-01T00:00:00Z\",\"end\":\"2026-12-31T00:00:00Z\"}]\n");
    }

    private static JsonObject event(String id, String endpointId, String kind, String start, String end) {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("endpointId", endpointId);
        o.addProperty("kind", kind);
        o.addProperty("start", start);
        o.addProperty("end", end);
        o.addProperty("firstFailureMessage", "fail");
        o.addProperty("lastFailureMessage", "fail");
        o.addProperty("lastFailureAt", end);
        o.add("notes", new JsonArray());
        o.addProperty("endedUnobserved", false);
        o.addProperty("endedByConfigChange", false);
        return o;
    }

    @BeforeEach
    void start() throws Exception {
        add("website", "web", Visibility.PUBLIC);
        add("survival", "web", Visibility.PUBLIC_STATUS);
        add("intranet", "web", Visibility.PRIVATE);
        add("secret", "internal", Visibility.PRIVATE);
        coverage("website");
        coverage("survival");
        JsonObject outage = event("ev-outage", "website", "outage", "2026-11-10T10:00:00Z", "2026-11-10T10:30:00Z");
        JsonObject planned = event("ev-planned", "website", "maintenance", "2026-11-11T02:00:00Z",
                "2026-11-11T02:10:00Z");
        planned.addProperty("maintenanceId", "11111111-1111-1111-1111-111111111111");
        Files.writeString(dataDir.resolve("endpoints/website/events/2026.jsonl"), outage + "\n" + planned + "\n");

        List<EndpointDefinition> defs = endpoints.values().stream().map(Endpoint::definition).toList();
        UserDirectory users = UserDirectory.of(List.of(
                new User("ed", "Ed", Role.EDITOR, null, List.of(), false),
                new User("vic", "Vic", Role.VIEWER, null, List.of(), false),
                new User("pat", "Pat", MANAGE_ONLY, null, List.of(), false)));
        web = new WebServer(new ServerConfig(0, "127.0.0.1", null, List.of(), UnknownHostsPolicy.DEFAULT));
        SessionManager sessions = new SessionManager(dataDir.resolve("sessions"), Duration.ofDays(7));
        Permissions permissions = new Permissions(sessions, users::user);
        SiteResolver sites = new SiteResolver(List.of(), "Status", null, UnknownHostsPolicy.DEFAULT,
                web.trustedProxies());
        history = HistoryStore.open(dataDir, clock, System.getLogger(MaintenancePageTest.class.getName()));
        UptimeCalculator uptime = new UptimeCalculator(history, ZoneOffset.UTC, false, clock);
        store = new MaintenanceStore(dataDir, new MaintenanceSettings(false, 7, 7), new MaintenanceStore.References() {
            @Override
            public boolean endpointExists(String id) {
                return endpoints.containsKey(id);
            }

            @Override
            public boolean categoryExists(String id) {
                return categories.stream().anyMatch(c -> c.id().equals(id));
            }

            @Override
            public boolean channelExists(String name) {
                return name.equals("discord");
            }
        }, AuditLog.disabled(), clock, ZoneOffset.UTC);
        store.load();
        web.register("/test-login", request -> {
            sessions.login(request, request.get().get("user"));
            PageSupport.write(request, "200 OK", "text/plain; charset=utf-8", "ok");
        });
        java.util.function.Supplier<ResolvedLayout> layout = () -> ResolvedLayout.resolve(null, categories, defs);
        java.util.function.Function<String, Optional<Endpoint>> lookup = id -> Optional.ofNullable(endpoints.get(id));
        java.util.function.Function<String, Optional<EndpointStatus>> status = id -> Optional.ofNullable(statuses.get(id));
        DashboardConfig dashboard = new DashboardConfig("Status", 100.0, 99.0, 95.0);
        MaintenancePage maintenance = new MaintenancePage(sites, permissions, sessions,
                new MaintenanceViews(store, layout, lookup, clock, 7), null, () -> Set.of("discord"));
        maintenance.register(web);
        StatusPage statusPage = new StatusPage(sites, layout, lookup, status, permissions::viewer, uptime, dashboard);
        statusPage.maintenance(maintenance);
        statusPage.register(web);
        ServicePage servicePage = new ServicePage(sites, layout, lookup, status, permissions::viewer, uptime, history,
                clock, dashboard, new EventEditor(permissions, sessions, history));
        servicePage.maintenance(maintenance);
        servicePage.register(web);
        web.start();
    }

    @AfterEach
    void stop() {
        if (web != null) web.close();
        if (history != null) history.close();
    }

    // ---- helpers ----

    record Session(String cookie, String csrf) {
    }

    private HttpResponse<String> get(String path, Session session) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + web.port() + path))
                .timeout(Duration.ofSeconds(10));
        if (session != null) b.header("Cookie", session.cookie());
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, Session session, Map<String, String> fields) throws Exception {
        StringBuilder form = new StringBuilder();
        Map<String, String> all = new LinkedHashMap<>(fields);
        if (session != null && !all.containsKey("csrf")) all.put("csrf", session.csrf());
        for (Map.Entry<String, String> e : all.entrySet()) {
            if (!form.isEmpty()) form.append('&');
            form.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)).append('=')
                    .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        }
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + web.port() + path))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form.toString()));
        if (session != null) b.header("Cookie", session.cookie());
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private Session login(String user) throws Exception {
        HttpResponse<String> r = get("/test-login?user=" + user, null);
        assertEquals(200, r.statusCode());
        String cookie = null;
        for (String c : r.headers().allValues("Set-Cookie")) {
            if (c.startsWith("SessID=")) cookie = c.substring(0, c.indexOf(';'));
        }
        assertNotNull(cookie);
        Matcher m = CSRF.matcher(get("/", new Session(cookie, null)).body());
        assertTrue(m.find());
        return new Session(cookie, m.group(1));
    }

    private MaintenanceWindow create(String title, Instant start, Instant end, List<String> endpointIds,
                                     List<String> categoryIds) throws Exception {
        return store.create(new MaintenanceStore.Draft(title, null, start, end, endpointIds, categoryIds, List.of()),
                ED);
    }

    private static JsonObject json(HttpResponse<String> r) {
        return JsonParser.parseString(r.body()).getAsJsonObject();
    }

    private static List<String> ids(JsonArray array) {
        List<String> out = new ArrayList<>();
        for (JsonElement e : array) out.add(e.getAsJsonObject().get("id").getAsString());
        return out;
    }

    // ---- visibility ----

    @Test
    void anonymousSeesWindowsOnlyForVisibleServicesWithAFilteredAffectedList() throws Exception {
        Instant start = NOW.plus(Duration.ofDays(1));
        Instant end = start.plus(Duration.ofHours(2));
        Duration m = Duration.ofMinutes(1);
        MaintenanceWindow mixed = create("Mixed reboot", start, end, List.of("website", "intranet"), List.of());
        MaintenanceWindow hidden = create("Hidden reboot", start.plus(m), end, List.of("intranet"), List.of());
        MaintenanceWindow internal = create("Internal reboot", start.plus(m.multipliedBy(2)), end, List.of(),
                List.of("internal"));
        MaintenanceWindow byCategory = create("Web category reboot", start.plus(m.multipliedBy(3)), end, List.of(),
                List.of("web"));
        store.addNote(mixed.id(), "public words", EventNote.Visibility.PUBLIC, ED);
        store.addNote(mixed.id(), "private words", EventNote.Visibility.PRIVATE, ED);

        // JSON list: only windows with a visible service; the affected list is filtered.
        JsonObject list = json(get("/api/maintenance", null));
        JsonArray windows = list.getAsJsonArray("windows");
        assertEquals(List.of(mixed.id(), byCategory.id()), ids(windows));
        assertEquals(List.of("website"), ids(windows.get(0).getAsJsonObject().getAsJsonArray("affected")));
        assertEquals(List.of("website", "survival"),
                ids(windows.get(1).getAsJsonObject().getAsJsonArray("affected")));
        assertFalse(windows.get(0).getAsJsonObject().has("endpoints"), "raw ids are for view.private only");
        String listBody = get("/api/maintenance", null).body();
        for (String leak : List.of("intranet", "secret", "Hidden reboot", "Internal reboot", "example.com")) {
            assertFalse(listBody.contains(leak), leak);
        }

        // One window: hidden ones are 404, visible ones have filtered services and public notes only.
        assertEquals(404, get("/api/maintenance/" + hidden.id(), null).statusCode());
        assertEquals(404, get("/api/maintenance/" + internal.id(), null).statusCode());
        assertEquals(404, get("/maintenance/" + hidden.id(), null).statusCode());
        assertEquals(404, get("/maintenance/" + internal.id(), null).statusCode());
        JsonObject one = json(get("/api/maintenance/" + mixed.id(), null));
        assertEquals(List.of("website"), ids(one.getAsJsonArray("affected")));
        assertEquals(1, one.getAsJsonArray("notes").size());
        assertEquals("public words", one.getAsJsonArray("notes").get(0).getAsJsonObject().get("text").getAsString());
        String page = get("/maintenance/" + mixed.id(), null).body();
        assertTrue(page.contains("Mixed reboot"));
        assertTrue(page.contains("Name website"));
        assertTrue(page.contains("public words"));
        assertFalse(page.contains("Name intranet"));
        assertFalse(page.contains("private words"));

        // Main page and service pages: the box shows only visible windows and services.
        String main = get("/", null).body();
        assertTrue(main.contains("Upcoming maintenance"));
        assertTrue(main.contains("Mixed reboot"));
        assertTrue(main.contains("Web category reboot"));
        assertFalse(main.contains("Hidden reboot"));
        assertFalse(main.contains("Internal reboot"));
        assertFalse(main.contains("Name intranet"));
        assertFalse(main.contains("Schedule maintenance"), "no form link for anonymous visitors");
        JsonObject status = json(get("/api/status", null));
        assertEquals(List.of(mixed.id(), byCategory.id()), ids(status.getAsJsonArray("maintenance")));
        String survival = get("/service/survival", null).body();
        assertTrue(survival.contains("Web category reboot"));
        assertFalse(survival.contains("Mixed reboot"), "only windows affecting this service");

        // view.private sees everything, including the raw ids and private notes.
        Session vic = login("vic");
        JsonObject privileged = json(get("/api/maintenance", vic));
        assertEquals(List.of(mixed.id(), hidden.id(), internal.id(), byCategory.id()),
                ids(privileged.getAsJsonArray("windows")));
        assertEquals(List.of("website", "intranet"),
                ids(privileged.getAsJsonArray("windows").get(0).getAsJsonObject().getAsJsonArray("affected")));
        assertEquals(2, json(get("/api/maintenance/" + mixed.id(), vic)).getAsJsonArray("notes").size());
        assertEquals(200, get("/maintenance/" + internal.id(), vic).statusCode());
    }

    @Test
    void cancelledWindowsAndUnknownIdsAreNotShown() throws Exception {
        Instant start = NOW.plus(Duration.ofDays(1));
        MaintenanceWindow w = create("Gone", start, start.plus(Duration.ofHours(1)), List.of("website"), List.of());
        store.cancel(w.id(), w.version(), ED);
        assertEquals(0, json(get("/api/maintenance", null)).getAsJsonArray("windows").size());
        assertFalse(get("/", null).body().contains("Upcoming maintenance"));
        assertEquals(404, get("/api/maintenance/nope", null).statusCode());
        assertEquals(404, get("/maintenance/not..valid", null).statusCode());
        // Bad ranges.
        assertEquals(400, get("/api/maintenance?from=2026-13-01", null).statusCode());
        assertEquals(400, get("/api/maintenance?from=2026-11-20&to=2026-11-10", null).statusCode());
        assertEquals(400, get("/api/maintenance?from=2026-01-01&to=2027-12-31", null).statusCode());
        assertEquals(200, get("/api/maintenance?from=2026-11-01&to=2026-11-30", null).statusCode());
    }

    // ---- permissions ----

    @Test
    void withoutMaintenanceManageEditsAre403() throws Exception {
        Instant start = NOW.plus(Duration.ofDays(1));
        MaintenanceWindow w = create("Reboot", start, start.plus(Duration.ofHours(1)), List.of("website"), List.of());
        Map<String, String> form = Map.of("title", "New", "start", "2026-11-20T02:00", "end", "2026-11-20T03:00",
                "endpoint.website", "on", "repeat", "none");

        Session vic = login("vic"); // view.private, but not maintenance.manage
        assertEquals(403, get("/maintenance/new", vic).statusCode());
        assertEquals(403, post("/maintenance/new", vic, form).statusCode());
        Map<String, String> edit = new LinkedHashMap<>(form);
        edit.put("version", "1");
        assertEquals(403, post("/maintenance/" + w.id(), vic, edit).statusCode());
        assertEquals(403, post("/maintenance/" + w.id() + "/cancel", vic, Map.of("version", "1")).statusCode());
        assertEquals(403, post("/maintenance/" + w.id() + "/end", vic, Map.of("version", "1")).statusCode());
        assertEquals(403, post("/maintenance/" + w.id() + "/extend", vic,
                Map.of("version", "1", "end", "2026-11-14T15:00")).statusCode());
        assertFalse(get("/", vic).body().contains("Schedule maintenance"));
        assertFalse(get("/maintenance/" + w.id(), vic).body().contains("maintenance-form"));
        assertEquals(1, store.windows().size());
        assertEquals("Reboot", store.window(w.id()).orElseThrow().title());

        // Anonymous visitors are sent to the login page.
        HttpResponse<String> anon = get("/maintenance/new", null);
        assertEquals(303, anon.statusCode());
        assertTrue(anon.headers().firstValue("Location").orElseThrow().startsWith("/login?next="));
        assertEquals(303, post("/maintenance/" + w.id() + "/cancel", null, Map.of("version", "1")).statusCode());

        // Without CSRF token: 403 even with the permission.
        Session ed = login("ed");
        Map<String, String> noCsrf = new LinkedHashMap<>(form);
        noCsrf.put("csrf", "0".repeat(64));
        assertEquals(403, post("/maintenance/new", ed, noCsrf).statusCode());
        assertEquals(1, store.windows().size());
    }

    // ---- create, edit, cancel ----

    @Test
    void scheduleEditAndCancelThroughTheForms() throws Exception {
        Session ed = login("ed");
        assertTrue(get("/", ed).body().contains("Schedule maintenance"));
        String serviceForm = get("/service/website", ed).body();
        assertTrue(serviceForm.contains("/maintenance/new?service=website"));
        String form = get("/maintenance/new?service=website", ed).body();
        assertTrue(form.contains("name=\"endpoint.website\" value=\"on\" checked"));
        assertTrue(form.contains("name=\"endpoint.intranet\""), "view.private users can pick private services");

        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("title", "Upgrade <db>");
        fields.put("description", "Short downtime");
        fields.put("start", "2026-11-15T02:00");
        fields.put("end", "2026-11-15T03:30");
        fields.put("endpoint.website", "on");
        fields.put("channel.discord", "on");
        fields.put("repeat", "none");
        HttpResponse<String> created = post("/maintenance/new", ed, fields);
        assertEquals(303, created.statusCode(), created.body());
        MaintenanceWindow w = store.windows().getFirst();
        assertEquals("/maintenance/" + w.id(), created.headers().firstValue("Location").orElseThrow());
        assertEquals(Instant.parse("2026-11-15T02:00:00Z"), w.start());
        assertEquals(List.of("website"), w.endpoints());
        assertEquals(List.of("discord"), w.notifyChannels());
        String page = get("/maintenance/" + w.id(), ed).body();
        assertTrue(page.contains("Upgrade &lt;db&gt;"));
        assertTrue(page.contains("Cancel"));

        // Invalid input: 400 with the form and its errors, nothing changed.
        Map<String, String> bad = new LinkedHashMap<>(fields);
        bad.put("end", "2026-11-14T01:00");
        HttpResponse<String> badCreate = post("/maintenance/new", ed, bad);
        assertEquals(400, badCreate.statusCode());
        assertTrue(badCreate.body().contains("form-error-list"));
        assertEquals(1, store.windows().size());
        Map<String, String> unknown = new LinkedHashMap<>(fields);
        unknown.put("endpoint.nope", "on");
        assertEquals(400, post("/maintenance/new", ed, unknown).statusCode());

        // Edit with the current version; a stale version is 409.
        Map<String, String> edit = new LinkedHashMap<>(fields);
        edit.put("title", "Upgrade database");
        edit.put("version", "1");
        HttpResponse<String> edited = post("/maintenance/" + w.id(), ed, edit);
        assertEquals(303, edited.statusCode(), edited.body());
        assertEquals("Upgrade database", store.window(w.id()).orElseThrow().title());
        assertEquals(409, post("/maintenance/" + w.id(), ed, edit).statusCode());

        // Cancel from the main page's box returns there; the window is gone from the box and the API.
        assertTrue(get("/", ed).body().contains("Upgrade database"));
        HttpResponse<String> cancelled = post("/maintenance/" + w.id() + "/cancel", ed,
                Map.of("version", "2", "next", "/"));
        assertEquals(303, cancelled.statusCode(), cancelled.body());
        assertEquals("/", cancelled.headers().firstValue("Location").orElseThrow());
        assertEquals(MaintenanceWindow.Status.CANCELLED, store.window(w.id()).orElseThrow().status(clock.instant()));
        assertFalse(get("/", ed).body().contains("Upgrade database"));
        assertEquals("cancelled", json(get("/api/maintenance/" + w.id(), ed)).get("status").getAsString());
    }

    @Test
    void managerWithoutViewPrivateKeepsServicesTheyCantSee() throws Exception {
        Instant start = NOW.plus(Duration.ofDays(1));
        MaintenanceWindow w = create("Reboot", start, start.plus(Duration.ofHours(1)), List.of("website", "intranet"),
                List.of());
        Session pat = login("pat");
        String form = get("/maintenance/" + w.id(), pat).body();
        assertFalse(form.contains("intranet"));
        Map<String, String> edit = new LinkedHashMap<>();
        edit.put("title", "Reboot 2");
        edit.put("start", "2026-11-14T12:00");
        edit.put("end", "2026-11-14T13:00");
        edit.put("endpoint.website", "on");
        edit.put("version", "1");
        assertEquals(303, post("/maintenance/" + w.id(), pat, edit).statusCode());
        assertEquals(List.of("website", "intranet"), store.window(w.id()).orElseThrow().endpoints());
        // Picking a service they can't see is rejected.
        Map<String, String> sneaky = new LinkedHashMap<>(edit);
        sneaky.put("endpoint.secret", "on");
        sneaky.put("version", "2");
        assertEquals(400, post("/maintenance/" + w.id(), pat, sneaky).statusCode());
    }

    @Test
    void recurringSeriesCanBeScheduledAndOccurrencesSkipped() throws Exception {
        Session ed = login("ed");
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("title", "Weekly reboot");
        fields.put("start", "2026-11-15T03:00");
        fields.put("end", "2026-11-15T03:30");
        fields.put("endpoint.website", "on");
        fields.put("repeat", "weekly");
        fields.put("weekday.SUNDAY", "on");
        HttpResponse<String> created = post("/maintenance/new", ed, fields);
        assertEquals(303, created.statusCode(), created.body());
        MaintenanceWindow series = store.windows().getFirst();
        assertTrue(series.isSeries());
        String page = get("/maintenance/" + series.id(), ed).body();
        assertTrue(page.contains("Weekly on Sunday"));
        assertTrue(page.contains(series.id() + "_2026-11-22"));
        HttpResponse<String> skipped = post("/maintenance/" + series.id() + "/occurrence/2026-11-22/skip", ed,
                Map.of("version", "1"));
        assertEquals(303, skipped.statusCode(), skipped.body());
        assertTrue(store.window(series.id()).orElseThrow().exception(java.time.LocalDate.parse("2026-11-22"))
                .orElseThrow().skipped());
        assertEquals(404, get("/maintenance/" + series.id() + "_2026-11-22", null).statusCode());
        assertEquals(200, get("/maintenance/" + series.id() + "_2026-11-29", null).statusCode());
    }

    // ---- display ----

    @Test
    void inProgressWindowsShowAsMaintenanceInTheStatusApiAndBanner() throws Exception {
        Instant start = NOW.plus(Duration.ofHours(1));
        MaintenanceWindow w = create("Reboot", start, start.plus(Duration.ofHours(2)), List.of("website"), List.of());
        clock.set(start.plus(Duration.ofMinutes(10)));
        statuses.put("website", new EndpointStatus("website", State.MAINTENANCE, start, 1, 0, null, null, null,
                List.of(), State.DOWN, w.id()));
        HttpResponse<String> r = get("/api/status", null);
        assertEquals(200, r.statusCode(), "maintenance isn't an outage");
        JsonObject status = json(r);
        assertEquals("MAINTENANCE", status.get("overall").getAsString());
        JsonObject web = status.getAsJsonArray("categories").get(0).getAsJsonObject();
        assertEquals(1, web.getAsJsonObject("summary").get("maintenance").getAsInt());
        JsonObject website = web.getAsJsonArray("endpoints").get(0).getAsJsonObject();
        assertEquals("MAINTENANCE", website.get("state").getAsString());
        assertEquals(w.id(), website.getAsJsonObject("maintenance").get("id").getAsString());
        assertEquals("in_progress", status.getAsJsonArray("maintenance").get(0).getAsJsonObject()
                .get("status").getAsString());
        String main = get("/", null).body();
        assertTrue(main.contains("Maintenance in progress"));
        assertTrue(main.contains("dot state-maintenance"));
        assertTrue(main.contains("In progress"));

        // End now: the window ends at once.
        Session ed = login("ed");
        String page = get("/maintenance/" + w.id(), ed).body();
        assertTrue(page.contains("End now"));
        assertTrue(page.contains("Extend"));
        assertEquals(303, post("/maintenance/" + w.id() + "/extend", ed,
                Map.of("version", "1", "end", "2026-11-13T16:00")).statusCode());
        assertEquals(Instant.parse("2026-11-13T16:00:00Z"), store.window(w.id()).orElseThrow().end());
        assertEquals(303, post("/maintenance/" + w.id() + "/end", ed, Map.of("version", "2")).statusCode());
        assertEquals(clock.instant(), store.window(w.id()).orElseThrow().end());
    }

    @Test
    void daysWithMaintenanceAreBlueAndFutureOnesOutlined() throws Exception {
        // A completed window yesterday (no downtime recorded) and one planned in two days.
        create("Past", Instant.parse("2026-11-13T13:00:00Z"), Instant.parse("2026-11-13T14:00:00Z"),
                List.of("website"), List.of());
        MaintenanceWindow future = create("Future", Instant.parse("2026-11-16T01:00:00Z"),
                Instant.parse("2026-11-16T02:00:00Z"), List.of("website"), List.of());
        clock.set(Instant.parse("2026-11-14T12:00:00Z"));

        JsonObject status = json(get("/api/status", null));
        JsonObject website = status.getAsJsonArray("categories").get(0).getAsJsonObject()
                .getAsJsonArray("endpoints").get(0).getAsJsonObject();
        JsonArray days = website.getAsJsonArray("days");
        JsonObject yesterday = days.get(days.size() - 2).getAsJsonObject();
        assertEquals("2026-11-13", yesterday.get("date").getAsString());
        assertEquals("blue", yesterday.get("color").getAsString());
        assertTrue(yesterday.get("maintenance").getAsBoolean());
        // Survival isn't affected: green.
        JsonObject survival = status.getAsJsonArray("categories").get(0).getAsJsonObject()
                .getAsJsonArray("endpoints").get(1).getAsJsonObject();
        JsonArray sDays = survival.getAsJsonArray("days");
        assertEquals("green", sDays.get(sDays.size() - 2).getAsJsonObject().get("color").getAsString());

        String calendar = get("/service/website?view=calendar", null).body();
        assertTrue(calendar.contains("class=\"cal-day cal-blue\" href=\"/service/website/day/2026-11-13\""), calendar);
        assertTrue(calendar.contains("cal-future cal-future-maintenance\" href=\"/service/website/day/2026-11-16\""));
        assertTrue(calendar.contains("<span class=\"cal-day cal-future\" data-date=\"2026-11-17\""));

        // The future day's page lists the window; a future day without one is still 404.
        String day = get("/service/website/day/2026-11-16", null).body();
        assertTrue(day.contains("Planned maintenance"));
        assertTrue(day.contains("/maintenance/" + future.id()));
        assertEquals(404, get("/service/website/day/2026-11-17", null).statusCode());
        // The past day lists the window apart from the events.
        String past = get("/service/website/day/2026-11-13", null).body();
        assertTrue(past.contains("day-maintenance"));
        assertTrue(past.contains("Past"));
    }

    @Test
    void plannedBadgeAndEventFilter() throws Exception {
        String all = get("/service/website", null).body();
        assertTrue(all.contains("badge-planned\">Planned</span>"));
        assertTrue(all.contains("ev-outage"));
        assertTrue(all.contains("ev-planned"));
        assertTrue(all.contains("aria-current=\"page\">All</a>"));

        String outages = get("/service/website?view=list&filter=outages", null).body();
        assertTrue(outages.contains("ev-outage"));
        assertFalse(outages.contains("ev-planned"));
        assertFalse(outages.contains("badge-planned\">Planned"));

        String maintenance = get("/service/website?view=list&filter=maintenance", null).body();
        assertFalse(maintenance.contains("event-ev-outage"));
        assertTrue(maintenance.contains("ev-planned"));

        JsonObject api = json(get("/api/service/website/events?filter=maintenance", null));
        assertEquals(1, api.get("total").getAsInt());
        assertEquals("maintenance", api.getAsJsonArray("events").get(0).getAsJsonObject().get("kind").getAsString());
    }

    @Test
    void notesOnWindowsNeedEventsAnnotate() throws Exception {
        Instant start = NOW.plus(Duration.ofDays(1));
        MaintenanceWindow w = create("Reboot", start, start.plus(Duration.ofHours(1)), List.of("website"), List.of());
        Session pat = login("pat"); // maintenance.manage only
        assertEquals(403, post("/maintenance/" + w.id() + "/notes", pat, Map.of("text", "hi")).statusCode());
        Session ed = login("ed");
        HttpResponse<String> added = post("/maintenance/" + w.id() + "/notes", ed,
                Map.of("text", "Public <note>", "visibility", "public"));
        assertEquals(303, added.statusCode(), added.body());
        String page = get("/maintenance/" + w.id(), null).body();
        assertTrue(page.contains("Public &lt;note&gt;"));
        String noteId = store.window(w.id()).orElseThrow().notes().getFirst().id();
        assertEquals(303, post("/maintenance/" + w.id() + "/note/" + noteId, ed,
                Map.of("visibility", "private")).statusCode());
        assertFalse(get("/maintenance/" + w.id(), null).body().contains("Public &lt;note&gt;"));
        assertEquals(303, post("/maintenance/" + w.id() + "/note/" + noteId + "/delete", ed, Map.of()).statusCode());
        assertTrue(store.window(w.id()).orElseThrow().notes().isEmpty());
    }
}
