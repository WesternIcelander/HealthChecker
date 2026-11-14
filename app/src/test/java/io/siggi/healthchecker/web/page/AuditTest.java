package io.siggi.healthchecker.web.page;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.siggi.healthchecker.api.CheckPlugin;
import io.siggi.healthchecker.api.CheckResult;
import io.siggi.healthchecker.api.Checker;
import io.siggi.healthchecker.api.ConfigException;
import io.siggi.healthchecker.api.EndpointConfig;
import io.siggi.healthchecker.api.SettingSpec;
import io.siggi.healthchecker.audit.AuditLog;
import io.siggi.healthchecker.auth.LocalLogin;
import io.siggi.healthchecker.auth.LoginRateLimiter;
import io.siggi.healthchecker.auth.Permission;
import io.siggi.healthchecker.auth.Permissions;
import io.siggi.healthchecker.auth.Role;
import io.siggi.healthchecker.auth.SessionManager;
import io.siggi.healthchecker.auth.User;
import io.siggi.healthchecker.auth.UserDirectory;
import io.siggi.healthchecker.check.http.HttpCheckPlugin;
import io.siggi.healthchecker.config.Config;
import io.siggi.healthchecker.config.ConfigLoader;
import io.siggi.healthchecker.config.DashboardConfig;
import io.siggi.healthchecker.config.EndpointStore;
import io.siggi.healthchecker.config.ServerConfig;
import io.siggi.healthchecker.config.UnknownHostsPolicy;
import io.siggi.healthchecker.history.EventNote;
import io.siggi.healthchecker.history.HistoryStore;
import io.siggi.healthchecker.history.UptimeCalculator;
import io.siggi.healthchecker.plugin.PluginManager;
import io.siggi.healthchecker.schedule.Endpoint;
import io.siggi.healthchecker.web.WebServer;
import io.siggi.healthchecker.web.layout.LayoutStore;
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
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The audit log: an entry for every kind of dashboard edit, masking, and {@code /admin/audit}, end to end over HTTP. */
class AuditTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-13T12:00:00Z"), ZoneOffset.UTC);
    private static final String EVENT = "11111111-2222-3333-4444-555555555555";
    /** May see the audit log, but not private data. */
    private static final Role AUDIT_ONLY = new Role("auditor", EnumSet.of(Permission.AUDIT_VIEW));
    private static final Map<String, String> ENV = Map.of("VAULT_NAME", "Vault", "VAULT_TOKEN", "s3cr3t-env");

    @TempDir
    Path dataDir;

    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private WebServer web;
    private PluginManager plugins;
    private HistoryStore history;
    private EndpointStore store;
    private AuditLog audit;

    /** A check type with a secret setting. */
    static final class SecretPlugin implements CheckPlugin {
        @Override
        public String type() {
            return "secret";
        }

        @Override
        public List<SettingSpec> settings() {
            return List.of(SettingSpec.string("host", "Host").asRequired(),
                    SettingSpec.string("apiToken", "API token").asRequired());
        }

        @Override
        public Checker create(EndpointConfig config) throws ConfigException {
            config.getString("host");
            config.getString("apiToken");
            return timeout -> CheckResult.ok("ok");
        }
    }

    @BeforeEach
    void start() throws Exception {
        Files.writeString(dataDir.resolve(EndpointStore.FILE_NAME), """
                { "version": 3,
                  "categories": [ { "id": "games", "name": "Games" } ],
                  "endpoints": [
                    { "id": "arcade", "name": "Arcade", "type": "http", "category": "games",
                      "url": "https://arcade.example.invalid", "maxClass": 2 } ] }
                """);
        Path events = dataDir.resolve("endpoints/website/events");
        Files.createDirectories(events);
        Files.writeString(dataDir.resolve("endpoints/website/coverage.json"),
                "[{\"start\":\"2026-11-01T00:00:00Z\",\"end\":\"2026-11-13T12:00:00Z\"}]\n");
        Files.writeString(events.resolve("2026.jsonl"), """
                {"id":"%s","endpointId":"website","kind":"outage","start":"2026-11-13T10:00:00Z",\
                "end":"2026-11-13T10:30:00Z","firstFailureMessage":"fail","lastFailureMessage":"fail",\
                "lastFailureAt":"2026-11-13T10:29:00Z","notes":[],"endedUnobserved":false,"endedByConfigChange":false}
                """.formatted(EVENT));
        Config config = new ConfigLoader(ENV::get, ZoneId.of("UTC")).load("""
                { "dataDir": %s,
                  "categories": [ { "id": "web", "name": "Websites" } ],
                  "endpoints": [
                    { "id": "website", "name": "Website", "type": "http", "category": "web",
                      "url": "https://website.example.invalid", "maxClass": 2 },
                    { "id": "vault", "name": "${env:VAULT_NAME}", "type": "secret",
                      "host": "vault.example.invalid", "apiToken": "${env:VAULT_TOKEN}" } ] }
                """.formatted(new Gson().toJson(dataDir.toString())));
        plugins = PluginManager.load(List.of(new HttpCheckPlugin(), new SecretPlugin()), null,
                getClass().getClassLoader());
        store = EndpointStore.load(config, plugins::validate);
        history = HistoryStore.open(dataDir, CLOCK, System.getLogger(AuditTest.class.getName()));
        store.reserveIdsWithHistory(history::hasHistory);
        LayoutStore layout = new LayoutStore(dataDir);
        layout.load();
        audit = AuditLog.open(dataDir);

        UserDirectory users = UserDirectory.of(List.of(
                new User("root", "Root", Role.ADMIN, null, List.of(), false),
                new User("vic", "Vic", Role.VIEWER, null, List.of(), false),
                new User("aud", "Aud", AUDIT_ONLY, null, List.of(), false)));
        web = new WebServer(new ServerConfig(0, "127.0.0.1", null, List.of(), UnknownHostsPolicy.DEFAULT));
        SessionManager sessions = new SessionManager(dataDir.resolve("sessions"), Duration.ofDays(7));
        Permissions permissions = new Permissions(sessions, users::user);
        SiteResolver sites = new SiteResolver(List.of(), "Status", null, UnknownHostsPolicy.DEFAULT, web.trustedProxies());
        web.register("/test-login", request -> {
            sessions.login(request, request.get().get("user"));
            PageSupport.write(request, "200 OK", "text/plain; charset=utf-8", "ok");
        });
        web.register("/test-csrf", request -> PageSupport.write(request, "200 OK", "text/plain; charset=utf-8",
                sessions.csrfToken(request)));
        new LoginPage(sites, sessions, permissions,
                new LocalLogin(true, username -> Optional.empty(), new LoginRateLimiter()), web.trustedProxies())
                .audit(audit).register(web);
        LayoutEditor layoutEditor = new LayoutEditor(sites, permissions, sessions, layout, store::categoryConfigs,
                store::definitions).audit(audit);
        layoutEditor.register(web);
        CategoryEditor categoryEditor = new CategoryEditor(sites, permissions, sessions, store, layout, config)
                .audit(audit);
        categoryEditor.register(web);
        layoutEditor.categoryEditor(categoryEditor);
        EndpointEditor endpointEditor = new EndpointEditor(sites, permissions, sessions, store, plugins, config, history)
                .audit(audit);
        endpointEditor.register(web);
        AuditPage auditPage = new AuditPage(sites, permissions, audit, ZoneId.of("UTC"));
        auditPage.register(web);
        Function<String, Optional<Endpoint>> lookup = id -> store.definitions().stream()
                .filter(d -> d.id().equals(id)).findFirst()
                .map(d -> new Endpoint(d, List.of(), timeout -> CheckResult.ok("ok")));
        UptimeCalculator uptime = new UptimeCalculator(history, ZoneOffset.UTC, false, CLOCK);
        DashboardConfig dashboard = new DashboardConfig("Status", 100.0, 99.0, 95.0);
        StatusPage statusPage = new StatusPage(sites, layoutEditor::layout, lookup, id -> Optional.empty(),
                permissions::viewer, uptime, dashboard, layoutEditor);
        statusPage.auditPage(auditPage);
        statusPage.register(web);
        new ServicePage(sites, layoutEditor::layout, lookup, id -> Optional.empty(), permissions::viewer, uptime,
                history, CLOCK, dashboard, new EventEditor(permissions, sessions, history).audit(audit)).register(web);
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

    private HttpResponse<String> postLayout(Session session, String json) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + web.port() + LayoutEditor.PATH))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("X-CSRF-Token", session.csrf())
                .header("Cookie", session.cookie())
                .POST(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private String version() {
        return Integer.toString(store.version());
    }

    private static void assertRedirect(HttpResponse<String> r) {
        assertEquals(303, r.statusCode(), r.body());
    }

    private AuditLog.Entry last() throws Exception {
        return audit.entries().getLast();
    }

    private static String str(JsonElement e, String key) {
        JsonElement v = e.getAsJsonObject().get(key);
        return v == null || v.isJsonNull() ? null : v.getAsString();
    }

    /** Checks the newest entry's user, action, id and that before/after are present or absent as expected. */
    private AuditLog.Entry assertLast(String action, String id, boolean before, boolean after) throws Exception {
        AuditLog.Entry entry = last();
        assertEquals(action, entry.action());
        assertEquals(id, entry.id());
        assertEquals("root", entry.user());
        assertEquals("Root", entry.userName());
        assertEquals(before, !entry.before().isJsonNull(), action + " before");
        assertEquals(after, !entry.after().isJsonNull(), action + " after");
        return entry;
    }

    // ---- tests ----

    @Test
    void everyEditTypeWritesAnEntryWithBeforeAndAfter() throws Exception {
        Session root = login("root");
        assertTrue(audit.entries().isEmpty());

        // Layout: save and reset.
        assertEquals(200, postLayout(root, "{\"version\":0,\"categories\":[{\"id\":\"games\",\"services\":[\"arcade\"]}]}")
                .statusCode());
        AuditLog.Entry e = assertLast("layout.save", "layout", false, true);
        assertEquals(1, e.after().getAsJsonObject().get("version").getAsInt());
        assertEquals("games", str(e.after().getAsJsonObject().getAsJsonArray("categories").get(0), "id"));
        assertEquals(200, postLayout(root, "{\"version\":1,\"reset\":true}").statusCode());
        e = assertLast("layout.reset", "layout", true, true);
        assertEquals(1, e.before().getAsJsonObject().get("version").getAsInt());
        assertEquals(new JsonArray(), e.after().getAsJsonObject().getAsJsonArray("categories"));

        // Event rename and reset.
        String eventPath = "/service/website/event/" + EVENT;
        assertRedirect(post(eventPath + "/title", root, "title", "Power cut"));
        e = assertLast("event.rename", "website/" + EVENT, true, true);
        assertTrue(e.before().getAsJsonObject().get("title").isJsonNull());
        assertEquals("Power cut", str(e.after(), "title"));
        assertRedirect(post(eventPath + "/title", root, "title", ""));
        e = assertLast("event.rename", "website/" + EVENT, true, true);
        assertEquals("Power cut", str(e.before(), "title"));
        assertTrue(e.after().getAsJsonObject().get("title").isJsonNull());

        // Notes: add, edit, toggle, delete.
        assertRedirect(post(eventPath + "/notes", root, "text", "First note", "visibility", "private"));
        EventNote note = history.event("website", EVENT).orElseThrow().notes().getFirst();
        String noteId = "website/" + EVENT + "/" + note.id();
        e = assertLast("note.add", noteId, false, true);
        assertEquals("First note", str(e.after(), "text"));
        assertEquals("private", str(e.after(), "visibility"));
        assertEquals("Root", str(e.after(), "author"));
        assertRedirect(post(eventPath + "/note/" + note.id(), root, "text", "Edited"));
        e = assertLast("note.edit", noteId, true, true);
        assertEquals("First note", str(e.before(), "text"));
        assertEquals("Edited", str(e.after(), "text"));
        assertRedirect(post(eventPath + "/note/" + note.id(), root, "visibility", "public"));
        e = assertLast("note.edit", noteId, true, true);
        assertEquals("private", str(e.before(), "visibility"));
        assertEquals("public", str(e.after(), "visibility"));
        assertRedirect(post(eventPath + "/note/" + note.id() + "/delete", root));
        e = assertLast("note.delete", noteId, true, false);
        assertEquals("Edited", str(e.before(), "text"));

        // Endpoints: create (with a secret setting), edit, delete.
        assertRedirect(post("/admin/endpoints/new", root, "version", version(), "id", "safe", "name", "Safe",
                "type", "secret", "settingsFor", "secret", "s.host", "safe.example.invalid", "s.apiToken", "hunter2",
                "notifyMode", "default", "action", "save"));
        e = assertLast("endpoint.create", "safe", false, true);
        assertEquals("Safe", str(e.after(), "name"));
        assertEquals("safe.example.invalid", str(e.after(), "host"));
        assertEquals(AuditLog.MASK, str(e.after(), "apiToken"));
        assertRedirect(post("/admin/endpoints/safe", root, "version", version(), "name", "Safe box",
                "type", "secret", "settingsFor", "secret", "s.host", "safe.example.invalid", "s.apiToken", "hunter2",
                "notifyMode", "default", "action", "save"));
        e = assertLast("endpoint.edit", "safe", true, true);
        assertEquals("Safe", str(e.before(), "name"));
        assertEquals("Safe box", str(e.after(), "name"));
        assertEquals(AuditLog.MASK, str(e.before(), "apiToken"));
        assertRedirect(post("/admin/endpoints/safe/delete", root, "version", version(), "confirm", "safe"));
        e = assertLast("endpoint.delete", "safe", true, false);
        assertEquals("Safe box", str(e.before(), "name"));

        // Deleting history too is its own entry.
        history.rememberTargets("arcade", List.of("https://arcade.example.invalid"));
        assertRedirect(post("/admin/endpoints/arcade/delete", root, "version", version(), "confirm", "arcade",
                "deleteHistory", "on"));
        List<AuditLog.Entry> all = audit.entries();
        assertEquals("endpoint.delete", all.get(all.size() - 2).action());
        assertLast("endpoint.history.delete", "arcade", false, false);

        // Categories: create, rename, delete (which also moves services to "Other" in the saved layout).
        assertRedirect(post("/admin/categories/new", root, "version", version(), "id", "tools", "name", "Tools"));
        e = assertLast("category.create", "tools", false, true);
        assertEquals("Tools", str(e.after(), "name"));
        assertRedirect(post("/admin/categories/tools", root, "version", version(), "name", "Toolbox",
                "description", "Handy"));
        e = assertLast("category.edit", "tools", true, true);
        assertEquals("Tools", str(e.before(), "name"));
        assertEquals("Toolbox", str(e.after(), "name"));
        assertEquals("Handy", str(e.after(), "description"));
        assertEquals(200, postLayout(root, "{\"version\":2,\"categories\":[{\"id\":\"games\",\"services\":[]}]}")
                .statusCode());
        assertRedirect(post("/admin/categories/games/delete", root, "version", version()));
        all = audit.entries();
        e = all.get(all.size() - 2);
        assertEquals("category.delete", e.action());
        assertEquals("games", e.id());
        assertEquals("Games", str(e.before(), "name"));
        assertTrue(e.after().isJsonNull());
        e = assertLast("layout.move-to-other", "games", true, true);
        assertEquals(3, e.before().getAsJsonObject().get("version").getAsInt());
        assertEquals(4, e.after().getAsJsonObject().get("version").getAsInt());

        // "Copy to dashboard" and its cancellation: recorded as written in config.json.
        assertRedirect(post("/admin/endpoints/vault/copy", root, "version", version(), "action", "copy"));
        e = assertLast("endpoint.copy", "vault", false, true);
        assertEquals("${env:VAULT_NAME}", str(e.after(), "name"));
        assertEquals(AuditLog.MASK, str(e.after(), "apiToken"));
        assertRedirect(post("/admin/endpoints/vault/copy", root, "version", version(), "action", "cancel"));
        e = assertLast("endpoint.copy.cancel", "vault", true, false);
        assertEquals("${env:VAULT_NAME}", str(e.before(), "name"));
        assertRedirect(post("/admin/categories/web/copy", root, "version", version(), "action", "copy"));
        e = assertLast("category.copy", "web", false, true);
        assertEquals("Websites", str(e.after(), "name"));
        assertRedirect(post("/admin/categories/web/copy", root, "version", version(), "action", "cancel"));
        assertLast("category.copy.cancel", "web", true, false);

        // A refused edit writes nothing.
        int count = audit.entries().size();
        assertEquals(403, post("/admin/categories/new", login("vic"), "version", version(), "id", "x", "name", "X")
                .statusCode());
        assertEquals(409, postLayout(root, "{\"version\":0,\"reset\":true}").statusCode());
        assertEquals(count, audit.entries().size());

        // A failed sign-in: no user, the client address only.
        HttpResponse<String> csrfPage = get("/test-csrf", null);
        String cookie = csrfPage.headers().allValues("Set-Cookie").stream().filter(c -> c.startsWith("SessID="))
                .map(c -> c.substring(0, c.indexOf(';'))).findFirst().orElseThrow();
        Session anonymous = new Session(cookie, csrfPage.body());
        assertEquals(401, post("/login", anonymous, "username", "mallory", "password", "pw-guess").statusCode());
        e = last();
        assertEquals("login.failed", e.action());
        assertEquals("127.0.0.1", e.id());
        assertNull(e.user());

        // No secret value made it to the file.
        String file = Files.readString(audit.file());
        for (String secret : List.of("hunter2", "s3cr3t-env", "mallory", "pw-guess")) {
            assertFalse(file.contains(secret), secret);
        }
    }

    @Test
    void auditPageRequiresAuditView() throws Exception {
        audit.record("root", "Root", "note.add", "website/e/n", null, new Gson().toJsonTree(Map.of("text", "<b>hi</b>")));

        HttpResponse<String> anonymous = get(AuditPage.PATH, null);
        assertEquals(303, anonymous.statusCode());
        assertEquals("/login?next=%2Fadmin%2Faudit", anonymous.headers().firstValue("Location").orElseThrow());
        Session vic = login("vic");
        assertEquals(403, get(AuditPage.PATH, vic).statusCode());
        // audit.view alone isn't enough: entries show private services, targets and notes.
        assertEquals(403, get(AuditPage.PATH, login("aud")).statusCode());

        Session root = login("root");
        HttpResponse<String> page = get(AuditPage.PATH, root);
        assertEquals(200, page.statusCode());
        assertEquals("DENY", page.headers().firstValue("X-Frame-Options").orElseThrow());
        String html = page.body();
        assertTrue(html.contains("<code class=\"audit-action\">note.add</code>"));
        assertTrue(html.contains("Root <span class=\"muted\">(root)</span>"));
        assertTrue(html.contains("&lt;b&gt;hi&lt;/b&gt;"));
        assertFalse(html.contains("<b>hi</b>"));
        assertFalse(html.contains("rel=\"next\""));

        // The main page links to it only for users who may see it.
        assertTrue(get("/", root).body().contains("href=\"/admin/audit\">Audit log</a>"));
        assertFalse(get("/", vic).body().contains("/admin/audit"));
        assertFalse(get("/", null).body().contains("/admin/audit"));
    }

    @Test
    void auditPageIsPagedNewestFirst() throws Exception {
        for (int i = 0; i < AuditPage.PAGE_SIZE + 5; i++) {
            audit.record("root", "Root", "action-" + i, null, null, null);
        }
        Session root = login("root");
        String first = get(AuditPage.PATH, root).body();
        assertTrue(first.indexOf("action-" + (AuditPage.PAGE_SIZE + 4) + "<")
                < first.indexOf("action-" + (AuditPage.PAGE_SIZE + 3) + "<"));
        assertFalse(first.contains(">action-4<"));
        assertTrue(first.contains("href=\"/admin/audit?page=1\">Older →</a>"));
        String second = get(AuditPage.PATH + "?page=1", root).body();
        assertTrue(second.contains(">action-4<"));
        assertTrue(second.contains(">action-0<"));
        assertFalse(second.contains(">action-5<"));
        assertTrue(second.contains("href=\"/admin/audit\">← Newer</a>"));
        assertEquals(404, get(AuditPage.PATH + "?page=2", root).statusCode());
        assertEquals(404, get(AuditPage.PATH + "?page=abc", root).statusCode());
        assertEquals(404, get(AuditPage.PATH + "?page=-1", root).statusCode());
    }
}
