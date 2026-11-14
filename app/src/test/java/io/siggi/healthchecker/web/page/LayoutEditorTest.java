package io.siggi.healthchecker.web.page;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.siggi.healthchecker.api.CheckResult;
import io.siggi.healthchecker.auth.LocalLogin;
import io.siggi.healthchecker.auth.LoginRateLimiter;
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
import io.siggi.healthchecker.status.CheckRecord;
import io.siggi.healthchecker.status.EndpointStatus;
import io.siggi.healthchecker.status.State;
import io.siggi.healthchecker.web.WebServer;
import io.siggi.healthchecker.web.layout.LayoutStore;
import io.siggi.healthchecker.web.layout.ResolvedLayout;
import io.siggi.healthchecker.web.site.SiteResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
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
import java.util.ArrayList;
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

/** The layout editor ({@code /?edit=layout}) and {@code POST /admin/layout}, end to end over HTTP. */
class LayoutEditorTest {
    private static final Instant NOW = Instant.parse("2026-11-13T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final Role LAYOUT_ONLY = new Role("layout-only", EnumSet.of(Permission.LAYOUT_EDIT));
    private static final Pattern DATA_CSRF = Pattern.compile("data-csrf=\"([0-9a-f]{64})\"");
    private static final Pattern LOGOUT_CSRF = Pattern.compile("name=\"csrf\" value=\"([0-9a-f]{64})\"");

    @TempDir
    Path dataDir;

    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final Map<String, Endpoint> endpoints = new LinkedHashMap<>();
    private final Map<String, EndpointStatus> statuses = new LinkedHashMap<>();
    private final List<CategoryConfig> categories = List.of(
            new CategoryConfig("web", "Websites", null),
            new CategoryConfig("minecraft", "Minecraft", null));
    private WebServer web;
    private HistoryStore history;
    private LayoutStore store;

    private void add(String id, String category, Visibility visibility) {
        EndpointDefinition def = new EndpointDefinition(id, "Name " + id, "http", category, visibility, 60, 5000, 1,
                List.of(), List.of(), 0, new JsonObject());
        endpoints.put(id, new Endpoint(def, List.of(new Target("url", "https://" + id + ".example.com")),
                timeout -> CheckResult.ok("ok")));
        CheckRecord pass = new CheckRecord(NOW.minusSeconds(60), Duration.ofMillis(10), CheckResult.ok("ok"));
        statuses.put(id, new EndpointStatus(id, State.UP, NOW.minusSeconds(3600), 0, 0, null, pass.message(), pass,
                List.of(pass)));
    }

    @BeforeEach
    void start() throws Exception {
        add("website", "web", Visibility.PUBLIC);
        add("blog", "web", Visibility.PUBLIC_STATUS);
        add("survival", "minecraft", Visibility.PUBLIC);
        add("intranet", "web", Visibility.PRIVATE);
        add("loose", null, Visibility.PUBLIC);
        List<EndpointDefinition> defs = endpoints.values().stream().map(Endpoint::definition).toList();
        UserDirectory users = UserDirectory.of(List.of(
                new User("root", "Root", Role.ADMIN, null, List.of(), false),
                new User("vic", "Vic", Role.VIEWER, null, List.of(), false),
                new User("lay", "Lay", LAYOUT_ONLY, null, List.of(), false)));

        web = new WebServer(new ServerConfig(0, "127.0.0.1", null, List.of(), UnknownHostsPolicy.DEFAULT));
        SessionManager sessions = new SessionManager(dataDir.resolve("sessions"), Duration.ofDays(7));
        Permissions permissions = new Permissions(sessions, users::user);
        SiteResolver sites = new SiteResolver(List.of(), "Status", null, UnknownHostsPolicy.DEFAULT,
                web.trustedProxies());
        history = HistoryStore.open(dataDir, CLOCK, System.getLogger(LayoutEditorTest.class.getName()));
        UptimeCalculator uptime = new UptimeCalculator(history, ZoneOffset.UTC, false, CLOCK);
        store = new LayoutStore(dataDir);
        store.load();

        web.register("/test-login", request -> {
            sessions.login(request, request.get().get("user"));
            PageSupport.write(request, "200 OK", "text/plain; charset=utf-8", "ok");
        });
        new LoginPage(sites, sessions, permissions,
                new LocalLogin(true, username -> Optional.empty(), new LoginRateLimiter()), web.trustedProxies())
                .register(web);
        LayoutEditor editor = new LayoutEditor(sites, permissions, sessions, store, () -> categories, () -> defs);
        editor.register(web);
        new StatusPage(sites, editor::layout, id -> Optional.ofNullable(endpoints.get(id)),
                id -> Optional.ofNullable(statuses.get(id)), permissions::viewer, uptime,
                new DashboardConfig("Status", 100.0, 99.0, 95.0), editor).register(web);
        web.start();
    }

    @AfterEach
    void stop() {
        if (web != null) web.close();
        if (history != null) history.close();
    }

    // ---- HTTP helpers ----

    private HttpResponse<String> get(String path, String cookie) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + web.port() + path))
                .timeout(Duration.ofSeconds(10));
        if (cookie != null) b.header("Cookie", cookie);
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String json, Session session) throws Exception {
        return post(json, session, "application/json");
    }

    private HttpResponse<String> post(String json, Session session, String contentType) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + web.port() + "/admin/layout"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofString(json));
        if (session != null) {
            b.header("Cookie", session.cookie());
            if (session.csrf() != null) b.header("X-CSRF-Token", session.csrf());
        }
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    record Session(String cookie, String csrf) {
        Session withoutCsrf() {
            return new Session(cookie, null);
        }
    }

    private Session login(String user) throws Exception {
        HttpResponse<String> r = get("/test-login?user=" + user, null);
        assertEquals(200, r.statusCode());
        String cookie = null;
        for (String c : r.headers().allValues("Set-Cookie")) {
            if (c.startsWith("SessID=")) cookie = c.substring(0, c.indexOf(';'));
        }
        assertNotNull(cookie);
        Matcher m = LOGOUT_CSRF.matcher(get("/", cookie).body());
        assertTrue(m.find());
        return new Session(cookie, m.group(1));
    }

    private static JsonObject json(HttpResponse<String> r) {
        return JsonParser.parseString(r.body()).getAsJsonObject();
    }

    /** Category name → service ids, in order, from {@code /api/status}. */
    private Map<String, List<String>> apiLayout(Session session) throws Exception {
        JsonObject root = json(get("/api/status", session == null ? null : session.cookie()));
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (JsonElement c : root.getAsJsonArray("categories")) {
            JsonObject category = c.getAsJsonObject();
            List<String> ids = new ArrayList<>();
            for (JsonElement e : category.getAsJsonArray("endpoints")) {
                ids.add(e.getAsJsonObject().get("id").getAsString());
            }
            result.put(category.get("name").getAsString(), ids);
        }
        return result;
    }

    private static String layoutJson(int version, String... categories) {
        // each entry: "id:svc1,svc2"
        JsonObject root = new JsonObject();
        root.addProperty("version", version);
        JsonArray array = new JsonArray();
        for (String spec : categories) {
            int colon = spec.indexOf(':');
            JsonObject c = new JsonObject();
            c.addProperty("id", spec.substring(0, colon));
            JsonArray services = new JsonArray();
            String rest = spec.substring(colon + 1);
            if (!rest.isEmpty()) {
                for (String s : rest.split(",")) services.add(s);
            }
            c.add("services", services);
            array.add(c);
        }
        root.add("categories", array);
        return root.toString();
    }

    private static Map<String, List<String>> expected(Object... pairs) {
        Map<String, List<String>> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            @SuppressWarnings("unchecked") List<String> ids = (List<String>) pairs[i + 1];
            map.put((String) pairs[i], ids);
        }
        return map;
    }

    private static final Map<String, List<String>> CONFIG_ORDER = expected(
            "Websites", List.of("website", "blog", "intranet"),
            "Minecraft", List.of("survival"),
            "Other", List.of("loose"));

    // ---- tests ----

    @Test
    void editorListsEveryServiceWithVisibility() throws Exception {
        Session root = login("root");
        assertTrue(get("/", root.cookie()).body().contains("href=\"/?edit=layout\">Edit layout</a>"));
        assertFalse(get("/", null).body().contains("Edit layout"));
        assertFalse(get("/", login("vic").cookie()).body().contains("Edit layout"));
        assertFalse(get("/", login("lay").cookie()).body().contains("Edit layout"));

        HttpResponse<String> r = get("/?edit=layout", root.cookie());
        assertEquals(200, r.statusCode());
        String html = r.body();
        assertTrue(html.contains("class=\"layout-editor\""));
        assertTrue(html.contains("data-version=\"0\""));
        assertTrue(html.contains("data-action=\"/admin/layout\""));
        Matcher csrf = DATA_CSRF.matcher(html);
        assertTrue(csrf.find());
        assertEquals(root.csrf(), csrf.group(1));
        assertFalse(html.contains("http-equiv=\"refresh\""), "editing must not auto-refresh");
        // Every service, private ones marked, in layout order, with category menus.
        int website = html.indexOf("data-service=\"website\"");
        int blog = html.indexOf("data-service=\"blog\"");
        int intranet = html.indexOf("data-service=\"intranet\"");
        int survival = html.indexOf("data-service=\"survival\"");
        int other = html.indexOf("data-category=\"_other\"");
        int loose = html.indexOf("data-service=\"loose\"");
        assertTrue(website > 0 && website < blog && blog < intranet && intranet < survival && survival < other
                && other < loose);
        assertTrue(html.contains("le-vis-private\" title=\"Visibility\">private</span>"));
        assertTrue(html.contains("le-vis-public-status\" title=\"Visibility\">public-status</span>"));
        assertTrue(html.contains("<select class=\"le-move\" id=\"le-cat-website\">"));
        assertTrue(html.contains("Reset to config order"));
        // No targets in the editor either.
        assertFalse(html.contains("example.com"));
    }

    @Test
    void saveAndReload() throws Exception {
        Session root = login("root");
        HttpResponse<String> r = post(layoutJson(0,
                "web:intranet,blog,website", "minecraft:survival", "_other:loose"), root);
        assertEquals(200, r.statusCode(), r.body());
        assertEquals(1, json(r).get("version").getAsInt());

        assertEquals(expected("Websites", List.of("intranet", "blog", "website"),
                "Minecraft", List.of("survival"), "Other", List.of("loose")), apiLayout(root));
        assertTrue(get("/?edit=layout", root.cookie()).body().contains("data-version=\"1\""));

        // Written to disk: a fresh store reads the same layout.
        LayoutStore reloaded = new LayoutStore(dataDir);
        reloaded.load();
        assertEquals(1, reloaded.version());
        List<String> ids = new ArrayList<>();
        for (ResolvedLayout.Category c : reloaded.resolve(categories,
                endpoints.values().stream().map(Endpoint::definition).toList()).categories()) {
            ids.add(c.id() + "=" + c.endpointIds());
        }
        assertEquals(List.of("web=[intranet, blog, website]", "minecraft=[survival]", "_other=[loose]"), ids);
    }

    @Test
    void moveServiceBetweenCategories() throws Exception {
        Session root = login("root");
        assertEquals(200, post(layoutJson(0,
                "web:blog,intranet", "minecraft:website,survival", "_other:loose"), root).statusCode());
        assertEquals(expected("Websites", List.of("blog"), "Minecraft", List.of("website", "survival"),
                "Other", List.of("loose")), apiLayout(null));
        // Into "Other" and out of it.
        assertEquals(200, post(layoutJson(1,
                "web:blog,intranet,loose", "minecraft:survival", "_other:website"), root).statusCode());
        assertEquals(expected("Websites", List.of("blog", "intranet", "loose"), "Minecraft", List.of("survival"),
                "Other", List.of("website")), apiLayout(root));
        // The editor's menu follows.
        String html = get("/?edit=layout", root.cookie()).body();
        assertTrue(Pattern.compile("id=\"le-cat-website\">.*?<option value=\"_other\" selected>Other</option>")
                .matcher(html).find());
    }

    @Test
    void reorderCategoriesIncludingOther() throws Exception {
        Session root = login("root");
        assertEquals(200, post(layoutJson(0,
                "_other:loose", "minecraft:survival", "web:website,blog,intranet"), root).statusCode());
        assertEquals(List.of("Other", "Minecraft", "Websites"), List.copyOf(apiLayout(root).keySet()));
        assertEquals(List.of("Other", "Minecraft", "Websites"), List.copyOf(apiLayout(null).keySet()));
    }

    @Test
    void omittedIdsAreAppendedAndStaleIdsDropped() throws Exception {
        Files.writeString(dataDir.resolve("layout.json"),
                "{\"version\": 4, \"categories\": [{\"id\": \"gone\", \"services\": [\"removed\"]}]}");
        store.load();
        Session root = login("root");
        assertEquals(200, post(layoutJson(4, "minecraft:survival", "web:blog"), root).statusCode());
        assertEquals(expected("Minecraft", List.of("survival"), "Websites", List.of("blog", "website", "intranet"),
                "Other", List.of("loose")), apiLayout(root));
        String file = Files.readString(dataDir.resolve("layout.json"));
        assertFalse(file.contains("gone"));
        assertFalse(file.contains("removed"));
        assertTrue(file.contains("\"intranet\""));
        assertTrue(file.contains("\"_other\""));
    }

    @Test
    void conflictingSaveGets409() throws Exception {
        Session root = login("root");
        Session other = login("root");
        assertEquals(200, post(layoutJson(0, "minecraft:survival"), root).statusCode());
        String before = Files.readString(dataDir.resolve("layout.json"));

        HttpResponse<String> r = post(layoutJson(0, "_other:loose"), other);
        assertEquals(409, r.statusCode());
        assertEquals(1, json(r).get("version").getAsInt());
        assertTrue(json(r).has("error"));
        assertEquals(before, Files.readString(dataDir.resolve("layout.json")));
        // A reset based on a stale version conflicts too.
        assertEquals(409, post("{\"version\": 0, \"reset\": true}", other).statusCode());
        // After reloading (version 1) the save goes through.
        assertEquals(200, post(layoutJson(1, "_other:loose"), other).statusCode());
    }

    @Test
    void noLayoutEditPermissionGets403() throws Exception {
        Session vic = login("vic");
        assertEquals(403, post(layoutJson(0, "_other:loose"), vic).statusCode());
        assertEquals(403, get("/?edit=layout", vic.cookie()).statusCode());

        // layout.edit without view.private isn't enough: the editor lists private services.
        Session lay = login("lay");
        assertEquals(403, post(layoutJson(0, "_other:loose"), lay).statusCode());
        HttpResponse<String> page = get("/?edit=layout", lay.cookie());
        assertEquals(403, page.statusCode());
        assertFalse(page.body().contains("intranet"));

        // Anonymous visitors are sent to sign in.
        HttpResponse<String> anonymous = get("/?edit=layout", null);
        assertEquals(303, anonymous.statusCode());
        assertEquals("/login?next=%2F%3Fedit%3Dlayout", anonymous.headers().firstValue("Location").orElseThrow());
        assertEquals(303, post(layoutJson(0, "_other:loose"), null).statusCode());

        // Missing CSRF token, and other methods.
        Session root = login("root");
        assertEquals(403, post(layoutJson(0, "_other:loose"), root.withoutCsrf()).statusCode());
        assertEquals(403, post(layoutJson(0, "_other:loose"), new Session(root.cookie(), vic.csrf())).statusCode());
        assertEquals(405, get("/admin/layout", root.cookie()).statusCode());

        assertFalse(Files.exists(dataDir.resolve("layout.json")));
        assertEquals(0, store.version());
    }

    @Test
    void unknownIdsRejected() throws Exception {
        Session root = login("root");
        for (String body : List.of(
                layoutJson(0, "web:website,nope"),
                layoutJson(0, "nope:website"),
                layoutJson(0, "web:website", "minecraft:website"),
                layoutJson(0, "web:website", "web:blog"),
                "{\"version\": 0, \"categories\": [{\"id\": \"web\", \"services\": [\"website\"], \"x\": 1}]}",
                "{\"version\": 0, \"categories\": [{\"id\": \"web\", \"services\": [1]}]}",
                "{\"version\": -1, \"categories\": []}",
                "{\"version\": 0}",
                "{\"version\": 0, \"reset\": true, \"categories\": []}",
                "{\"version\": 0, \"categories\": [], \"extra\": true}",
                "[]",
                "not json")) {
            HttpResponse<String> r = post(body, root);
            assertEquals(400, r.statusCode(), body);
            assertTrue(json(r).has("error"), body);
        }
        assertEquals(415, post(layoutJson(0, "web:website"), root, "text/plain").statusCode());
        assertEquals(413, post("{\"version\": 0, \"categories\": [], \"pad\": \""
                + "x".repeat(LayoutEditor.MAX_BODY_BYTES) + "\"}", root).statusCode());
        assertFalse(Files.exists(dataDir.resolve("layout.json")));
        assertEquals(CONFIG_ORDER, apiLayout(root));
    }

    @Test
    void resetRestoresConfigOrder() throws Exception {
        Session root = login("root");
        assertEquals(CONFIG_ORDER, apiLayout(root));
        assertEquals(200, post(layoutJson(0,
                "_other:website,loose", "minecraft:survival,blog", "web:intranet"), root).statusCode());
        assertFalse(CONFIG_ORDER.equals(apiLayout(root)));

        HttpResponse<String> r = post("{\"version\": 1, \"reset\": true}", root);
        assertEquals(200, r.statusCode(), r.body());
        assertEquals(2, json(r).get("version").getAsInt());
        assertEquals(CONFIG_ORDER, apiLayout(root));
        assertTrue(get("/?edit=layout", root.cookie()).body().contains("data-version=\"2\""));

        LayoutStore reloaded = new LayoutStore(dataDir);
        reloaded.load();
        assertEquals(2, reloaded.version());
        assertTrue(reloaded.saved().orElseThrow().categories().isEmpty());
        assertEquals(new String(Files.readAllBytes(dataDir.resolve("layout.json")), StandardCharsets.UTF_8),
                "{\n  \"version\": 2,\n  \"categories\": []\n}\n");
    }

    @Test
    void parseRejectsCategoriesInAFlatLayout() {
        List<EndpointDefinition> defs = endpoints.values().stream().map(Endpoint::definition).toList();
        assertTrue(assertThrowsInvalid(layoutJson(0, "web:website"), List.of(), defs).contains("unknown category"));
    }

    private static String assertThrowsInvalid(String json, List<CategoryConfig> categories,
                                              List<EndpointDefinition> defs) {
        try {
            LayoutEditor.parse(json, categories, defs);
        } catch (LayoutEditor.InvalidLayoutException e) {
            return e.getMessage();
        }
        throw new AssertionError("accepted: " + json);
    }
}
