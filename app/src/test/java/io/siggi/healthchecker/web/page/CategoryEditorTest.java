package io.siggi.healthchecker.web.page;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.siggi.healthchecker.api.CheckResult;
import io.siggi.healthchecker.auth.Permission;
import io.siggi.healthchecker.auth.Permissions;
import io.siggi.healthchecker.auth.Role;
import io.siggi.healthchecker.auth.SessionManager;
import io.siggi.healthchecker.auth.User;
import io.siggi.healthchecker.auth.UserDirectory;
import io.siggi.healthchecker.check.http.HttpCheckPlugin;
import io.siggi.healthchecker.config.CategoryConfig;
import io.siggi.healthchecker.config.Config;
import io.siggi.healthchecker.config.ConfigLoader;
import io.siggi.healthchecker.config.DashboardConfig;
import io.siggi.healthchecker.config.EndpointStore;
import io.siggi.healthchecker.config.ServerConfig;
import io.siggi.healthchecker.config.UnknownHostsPolicy;
import io.siggi.healthchecker.history.HistoryStore;
import io.siggi.healthchecker.history.UptimeCalculator;
import io.siggi.healthchecker.plugin.PluginManager;
import io.siggi.healthchecker.schedule.Endpoint;
import io.siggi.healthchecker.web.WebServer;
import io.siggi.healthchecker.web.layout.LayoutStore;
import io.siggi.healthchecker.web.layout.ResolvedLayout;
import io.siggi.healthchecker.web.layout.SavedLayout;
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
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The category editor ({@code /admin/categories…}) and its links in the layout editor, end to end over HTTP. */
class CategoryEditorTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-13T12:00:00Z"), ZoneOffset.UTC);
    /** May edit the layout, but not manage endpoints and categories. */
    private static final Role LAYOUT = new Role("layout", EnumSet.of(Permission.LAYOUT_EDIT, Permission.VIEW_PRIVATE));

    @TempDir
    Path dataDir;

    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private WebServer web;
    private PluginManager plugins;
    private HistoryStore history;
    private Config config;
    private EndpointStore store;
    private LayoutStore layout;
    private LayoutEditor layoutEditor;

    private Config loadConfig(String categories, String endpoints) throws Exception {
        return new ConfigLoader(name -> name.equals("WEB_DESC") ? "Our sites" : null, ZoneId.of("UTC")).load("""
                { "dataDir": %s,
                  "categories": [%s],
                  "endpoints": [%s] }
                """.formatted(new Gson().toJson(dataDir.toString()), categories, endpoints));
    }

    private static final String WEB = "{ \"id\": \"web\", \"name\": \"Websites\", \"description\": \"${env:WEB_DESC}\" }";
    private static final String WEBSITE = """
            { "id": "website", "name": "Website", "type": "http", "category": "web",
              "url": "https://website.example.invalid", "maxClass": 2 }""";

    @BeforeEach
    void start() throws Exception {
        Files.writeString(dataDir.resolve(EndpointStore.FILE_NAME), """
                { "version": 3,
                  "categories": [ { "id": "games", "name": "Games" } ],
                  "endpoints": [
                    { "id": "arcade", "name": "Arcade", "type": "http", "category": "games",
                      "url": "https://arcade.example.invalid", "maxClass": 2 },
                    { "id": "shop", "name": "Shop", "type": "http",
                      "url": "https://shop.example.invalid", "maxClass": 2 } ] }
                """);
        config = loadConfig(WEB, WEBSITE);
        plugins = PluginManager.load(List.of(new HttpCheckPlugin()), null, getClass().getClassLoader());
        store = EndpointStore.load(config, plugins::validate);
        history = HistoryStore.open(dataDir, CLOCK, System.getLogger(CategoryEditorTest.class.getName()));
        layout = new LayoutStore(dataDir);
        layout.load();

        UserDirectory users = UserDirectory.of(List.of(
                new User("root", "Root", Role.ADMIN, null, List.of(), false),
                new User("lay", "Lay", LAYOUT, null, List.of(), false),
                new User("vic", "Vic", Role.VIEWER, null, List.of(), false)));
        web = new WebServer(new ServerConfig(0, "127.0.0.1", null, List.of(), UnknownHostsPolicy.DEFAULT));
        SessionManager sessions = new SessionManager(dataDir.resolve("sessions"), Duration.ofDays(7), web.trustedProxies());
        Permissions permissions = new Permissions(sessions, users::user);
        SiteResolver sites = new SiteResolver(List.of(), "Status", null, UnknownHostsPolicy.DEFAULT, web.trustedProxies());
        web.register("/test-login", request -> {
            sessions.login(request, request.get().get("user"));
            PageSupport.write(request, "200 OK", "text/plain; charset=utf-8", "ok");
        });
        web.register("/test-csrf", request -> PageSupport.write(request, "200 OK", "text/plain; charset=utf-8",
                sessions.csrfToken(request)));
        layoutEditor = new LayoutEditor(sites, permissions, sessions, layout, store::categoryConfigs, store::definitions);
        layoutEditor.register(web);
        CategoryEditor categoryEditor = new CategoryEditor(sites, permissions, sessions, store, layout, config);
        categoryEditor.register(web);
        layoutEditor.categoryEditor(categoryEditor);
        new StatusPage(sites, layoutEditor::layout,
                id -> store.definitions().stream().filter(d -> d.id().equals(id)).findFirst()
                        .map(d -> new Endpoint(d, List.of(), timeout -> CheckResult.ok("ok"))),
                id -> Optional.empty(), permissions::viewer, new UptimeCalculator(history, ZoneOffset.UTC, false, CLOCK),
                new DashboardConfig("Status", 100.0, 99.0, 95.0), layoutEditor).register(web);
        web.start();
    }

    @AfterEach
    void stop() {
        if (web != null) web.close();
        if (plugins != null) plugins.close();
        if (history != null) history.close();
    }

    // ---- HTTP helpers ----

    record Session(String cookie, String csrf) {
    }

    private Session login(String user) throws Exception {
        HttpResponse<String> r = get("/test-login?user=" + user, null);
        assertEquals(200, r.statusCode());
        String cookie = null;
        for (String c : r.headers().allValues("Set-Cookie")) {
            if (c.startsWith("SessID=")) cookie = c.substring(0, c.indexOf(';'));
        }
        assertNotNull(cookie);
        return new Session(cookie, get("/test-csrf", new Session(cookie, null)).body());
    }

    private HttpResponse<String> get(String path, Session session) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + web.port() + path))
                .timeout(Duration.ofSeconds(10));
        if (session != null) b.header("Cookie", session.cookie());
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, Session session, String... fields) throws Exception {
        Map<String, String> all = new LinkedHashMap<>();
        if (session != null) all.put("csrf", session.csrf());
        for (int i = 0; i < fields.length; i += 2) all.put(fields[i], fields[i + 1]);
        StringBuilder body = new StringBuilder();
        for (Map.Entry<String, String> e : all.entrySet()) {
            if (!body.isEmpty()) body.append('&');
            body.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)).append('=')
                    .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        }
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + web.port() + path))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        if (session != null) b.header("Cookie", session.cookie());
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String version() {
        return Integer.toString(store.version());
    }

    /** Category id → service ids of the effective layout. */
    private Map<String, List<String>> shape() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (ResolvedLayout.Category c : layoutEditor.layout().categories()) out.put(c.id(), c.endpointIds());
        return out;
    }

    private static void assertRedirectToLayout(HttpResponse<String> r) {
        assertEquals(303, r.statusCode(), r.body());
        assertEquals("/?edit=layout", r.headers().firstValue("Location").orElseThrow());
    }

    // ---- tests ----

    @Test
    void layoutEditorLinksToTheCategoryEditor() throws Exception {
        String html = get("/?edit=layout", login("root")).body();
        assertTrue(html.contains("href=\"/admin/categories/new\">Add category</a>"));
        // Dashboard category: rename and delete.
        assertTrue(html.contains("href=\"/admin/categories/games\""));
        assertTrue(html.contains("href=\"/admin/categories/games/delete\""));
        // Config category: read-only, with a "config" badge and "Copy to dashboard".
        String web = html.substring(html.indexOf("data-category=\"web\""), html.indexOf("data-category=\"games\""));
        assertTrue(web.contains("badge-config"));
        assertTrue(web.contains("href=\"/admin/categories/web/copy\""));
        assertFalse(web.contains("/admin/categories/web/delete"));
        assertFalse(html.contains("href=\"/admin/categories/web\""));

        // Without endpoints.manage the layout editor has no category links, and the editor refuses.
        Session lay = login("lay");
        String plain = get("/?edit=layout", lay).body();
        assertTrue(plain.contains("class=\"layout-editor"));
        assertFalse(plain.contains("/admin/categories"));
        assertEquals(403, get("/admin/categories/new", lay).statusCode());
        assertEquals(403, post("/admin/categories/new", lay, "version", version(), "id", "x", "name", "X").statusCode());
        assertEquals(403, get("/admin/categories/games/delete", login("vic")).statusCode());
        HttpResponse<String> anonymous = get("/admin/categories/games", null);
        assertEquals(303, anonymous.statusCode());
        assertTrue(anonymous.headers().firstValue("Location").orElseThrow().startsWith("/login"));
        assertTrue(store.categoryConfigs().stream().noneMatch(c -> c.id().equals("x")));
    }

    @Test
    void addAndRenameDashboardCategories() throws Exception {
        Session root = login("root");
        assertEquals(200, get("/admin/categories/new", root).statusCode());
        assertRedirectToLayout(post("/admin/categories/new", root, "version", version(), "id", "tools",
                "name", "Tools", "description", "Handy things"));
        assertEquals(new CategoryConfig("tools", "Tools", "Handy things"), store.categoryConfigs().getLast());
        assertEquals(EndpointStore.Source.DASHBOARD, store.categories().getLast().source());
        assertTrue(shape().containsKey("tools"));

        // Ids already in use (either source) and invalid ids are refused; nothing is saved.
        HttpResponse<String> taken = post("/admin/categories/new", root, "version", version(), "id", "web", "name", "W");
        assertEquals(400, taken.statusCode());
        assertTrue(taken.body().contains("is already in use"));
        HttpResponse<String> bad = post("/admin/categories/new", root, "version", version(), "id", "Bad Id", "name", "B");
        assertEquals(400, bad.statusCode());
        assertTrue(bad.body().contains("value=\"Bad Id\""));
        assertEquals("Websites", store.categoryConfigs().getFirst().name());

        // Rename: name and description; the id can't change.
        String form = get("/admin/categories/games", root).body();
        assertTrue(form.contains("value=\"Games\""));
        assertFalse(form.contains("name=\"id\""));
        assertRedirectToLayout(post("/admin/categories/games", root, "version", version(), "id", "other-id",
                "name", "Fun & games", "description", ""));
        CategoryConfig games = store.categoryConfigs().stream().filter(c -> c.id().equals("games")).findFirst().orElseThrow();
        assertEquals(new CategoryConfig("games", "Fun & games", null), games);
        assertTrue(store.categoryConfigs().stream().noneMatch(c -> c.id().equals("other-id")));
        assertEquals("games", store.endpoint("arcade").orElseThrow().definition().category());

        // A stale version is a conflict.
        HttpResponse<String> stale = post("/admin/categories/games", root, "version", "1", "name", "Old");
        assertEquals(409, stale.statusCode());
        assertEquals("Fun & games", store.categoryConfigs().stream().filter(c -> c.id().equals("games"))
                .findFirst().orElseThrow().name());
        // CSRF is required.
        assertEquals(403, post("/admin/categories/games", new Session(root.cookie(), "x".repeat(64)),
                "version", version(), "name", "Nope").statusCode());
    }

    @Test
    void deleteMovesItsServicesToOther() throws Exception {
        // The layout also put the config endpoint "website" into the dashboard category "games".
        layout.save(0, List.of(
                new SavedLayout.Category("games", List.of("website", "arcade")),
                new SavedLayout.Category("_other", List.of("shop")),
                new SavedLayout.Category("web", List.of())));
        assertEquals(List.of("website", "arcade"), shape().get("games"));
        Session root = login("root");

        // The confirmation lists the services that move.
        HttpResponse<String> confirm = get("/admin/categories/games/delete", root);
        assertEquals(200, confirm.statusCode());
        assertTrue(confirm.body().contains("move to \"Other\""));
        assertTrue(confirm.body().contains("data-service=\"website\""));
        assertTrue(confirm.body().contains("data-service=\"arcade\""));
        assertFalse(confirm.body().contains("data-service=\"shop\""));

        // A stale version deletes nothing.
        assertEquals(409, post("/admin/categories/games/delete", root, "version", "0").statusCode());
        assertTrue(shape().containsKey("games"));

        assertRedirectToLayout(post("/admin/categories/games/delete", root, "version", version()));
        assertTrue(store.categoryConfigs().stream().noneMatch(c -> c.id().equals("games")));
        // Dashboard endpoints lose the category; the layout moves the category's services to "Other".
        assertNull(store.endpoint("arcade").orElseThrow().definition().category());
        assertFalse(store.endpoint("arcade").orElseThrow().entry().has("category"));
        assertEquals(2, layout.version());
        Map<String, List<String>> shape = shape();
        assertEquals(List.of("_other", "web"), List.copyOf(shape.keySet()));
        assertEquals(List.of("shop", "website", "arcade"), shape.get("_other"));
        assertFalse(get("/?edit=layout", root).body().contains("data-category=\"games\""));

        // Persisted: after a restart it's still gone.
        EndpointStore reloaded = EndpointStore.load(config, plugins::validate);
        assertTrue(reloaded.categoryConfigs().stream().noneMatch(c -> c.id().equals("games")));
        assertEquals(404, get("/admin/categories/games/delete", root).statusCode());
    }

    @Test
    void configCategoriesAreReadOnly() throws Exception {
        Session root = login("root");
        HttpResponse<String> view = get("/admin/categories/web", root);
        assertEquals(200, view.statusCode());
        assertTrue(view.body().contains("badge-config"));
        assertFalse(view.body().contains("ce-form"));
        assertTrue(view.body().contains("href=\"/admin/categories/web/copy\""));

        HttpResponse<String> rename = post("/admin/categories/web", root, "version", version(), "name", "Renamed");
        assertEquals(403, rename.statusCode());
        assertEquals(403, get("/admin/categories/web/delete", root).statusCode());
        assertEquals(403, post("/admin/categories/web/delete", root, "version", version()).statusCode());
        assertEquals(new CategoryConfig("web", "Websites", "Our sites"), store.categoryConfigs().getFirst());
        assertEquals(EndpointStore.Source.CONFIG, store.categories().getFirst().source());
        assertEquals(3, store.version());
        // Dashboard categories have no "Copy to dashboard"; unknown ids are 404.
        assertEquals(404, get("/admin/categories/games/copy", root).statusCode());
        assertEquals(404, get("/admin/categories/nope", root).statusCode());
    }

    @Test
    void copyConfigCategoryToDashboard() throws Exception {
        Session root = login("root");
        HttpResponse<String> page = get("/admin/categories/web/copy", root);
        assertEquals(200, page.statusCode());
        assertTrue(page.body().contains("&quot;description&quot;: &quot;Our sites&quot;"), page.body());
        assertTrue(page.body().contains("placeholders"));

        HttpResponse<String> copied = post("/admin/categories/web/copy", root, "version", version(), "action", "copy");
        assertEquals(303, copied.statusCode());
        assertEquals("/admin/categories/web/copy?copied=1", copied.headers().firstValue("Location").orElseThrow());
        assertEquals(Optional.of(new CategoryConfig("web", "Websites", "Our sites")), store.categoryCopy("web"));
        // Still the config category until the restart.
        assertEquals(EndpointStore.Source.CONFIG, store.categories().getFirst().source());

        // The removal instructions: the entry as written in config.json, plus the config services using it.
        String instructions = get("/admin/categories/web/copy?copied=1", root).body();
        assertTrue(instructions.contains("Copied to data/endpoints.json."));
        assertTrue(instructions.contains("Remove this entry from the <code>categories</code> array"));
        assertTrue(instructions.contains("&quot;description&quot;: &quot;${env:WEB_DESC}&quot;"), instructions);
        assertTrue(instructions.contains("href=\"/admin/endpoints/website\"><code>website</code></a>"));
        assertTrue(instructions.contains("Restart HealthChecker"));
        assertTrue(instructions.contains("value=\"cancel\""));
        assertTrue(get("/?edit=layout", root).body().contains("Copied (restart pending)"));

        // The file holds an equivalent entry: after removing the config entries and restarting, it takes over.
        JsonObject file = JsonParser.parseString(Files.readString(store.file())).getAsJsonObject();
        assertEquals(JsonParser.parseString("{\"id\": \"web\", \"name\": \"Websites\", \"description\": \"Our sites\"}"),
                file.getAsJsonArray("categories").get(1));
        assertThrows(Exception.class, () -> EndpointStore.load(config, plugins::validate));
        EndpointStore after = EndpointStore.load(loadConfig("", ""), plugins::validate);
        assertEquals(new EndpointStore.StoredCategory(new CategoryConfig("web", "Websites", "Our sites"),
                EndpointStore.Source.DASHBOARD), after.categories().getLast());

        // "Cancel copy" takes it back.
        assertEquals(303, post("/admin/categories/web/copy", root, "version", version(), "action", "cancel").statusCode());
        assertTrue(store.categoryCopy("web").isEmpty());
        assertTrue(get("/admin/categories/web/copy?cancelled=1", root).body().contains("value=\"copy\""));
        // A stale version copies nothing.
        assertEquals(409, post("/admin/categories/web/copy", root, "version", "0", "action", "copy").statusCode());
        assertTrue(store.categoryCopy("web").isEmpty());
    }
}
