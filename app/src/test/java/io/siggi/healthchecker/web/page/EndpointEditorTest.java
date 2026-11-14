package io.siggi.healthchecker.web.page;

import com.google.gson.Gson;
import io.siggi.healthchecker.api.CheckPlugin;
import io.siggi.healthchecker.api.CheckResult;
import io.siggi.healthchecker.api.Checker;
import io.siggi.healthchecker.api.ConfigException;
import io.siggi.healthchecker.api.EndpointConfig;
import io.siggi.healthchecker.api.SettingSpec;
import io.siggi.healthchecker.auth.Permission;
import io.siggi.healthchecker.auth.Permissions;
import io.siggi.healthchecker.auth.Role;
import io.siggi.healthchecker.auth.SessionManager;
import io.siggi.healthchecker.auth.User;
import io.siggi.healthchecker.auth.UserDirectory;
import io.siggi.healthchecker.check.http.HttpCheckPlugin;
import io.siggi.healthchecker.config.Config;
import io.siggi.healthchecker.config.ConfigLoader;
import io.siggi.healthchecker.config.EndpointDefinition;
import io.siggi.healthchecker.config.EndpointStore;
import io.siggi.healthchecker.config.ServerConfig;
import io.siggi.healthchecker.config.UnknownHostsPolicy;
import io.siggi.healthchecker.plugin.PluginManager;
import io.siggi.healthchecker.web.WebServer;
import io.siggi.healthchecker.web.site.SiteResolver;
import org.junit.jupiter.api.AfterEach;
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
import java.time.Duration;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The endpoint editor ({@code /admin/endpoints…}), end to end over HTTP. */
class EndpointEditorTest {
    private static final Role MANAGE_ONLY = new Role("manage-only", EnumSet.of(Permission.ENDPOINTS_MANAGE));

    @TempDir
    Path dataDir;

    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private WebServer web;
    private PluginManager plugins;
    private EndpointStore store;

    /** A plugin with one setting of every kind; {@code host = "bad"} is rejected on its field. */
    static final class TestPlugin implements CheckPlugin {
        @Override
        public String type() {
            return "test";
        }

        @Override
        public String displayName() {
            return "Test check";
        }

        @Override
        public List<SettingSpec> settings() {
            return List.of(
                    SettingSpec.string("host", "Host").asRequired().help("The host to test"),
                    SettingSpec.integer("port", "Port").defaultValue(25),
                    SettingSpec.bool("secure", "Secure").defaultValue(false),
                    SettingSpec.choice("mode", "Mode", "fast", "slow"),
                    SettingSpec.stringList("tags", "Tags"));
        }

        @Override
        public Checker create(EndpointConfig config) throws ConfigException {
            String host = config.getString("host");
            config.optInt("port");
            config.optBoolean("secure", false);
            config.optString("mode");
            config.optStringList("tags");
            if (host.equals("bad")) throw new ConfigException("host", "this host is not allowed");
            return timeout -> CheckResult.ok("ok");
        }
    }

    private void start(String endpointsFile) throws Exception {
        if (endpointsFile != null) Files.writeString(dataDir.resolve(EndpointStore.FILE_NAME), endpointsFile);
        Config config = new ConfigLoader(name -> null, ZoneId.of("UTC")).load("""
                { "dataDir": %s,
                  "defaults": { "intervalSeconds": 45, "notify": ["ops"] },
                  "notifications": { "channels": { "ops": {}, "discord": {} } },
                  "categories": [ { "id": "web", "name": "Websites" } ],
                  "endpoints": [ { "id": "website", "name": "Website", "type": "http", "category": "web",
                                   "url": "https://website.example.invalid", "maxClass": 2 } ] }
                """.formatted(new Gson().toJson(dataDir.toString())));
        plugins = PluginManager.load(List.of(new HttpCheckPlugin(), new TestPlugin()), null, getClass().getClassLoader());
        store = EndpointStore.load(config, plugins::validate);

        UserDirectory users = UserDirectory.of(List.of(
                new User("root", "Root", Role.ADMIN, null, List.of(), false),
                new User("vic", "Vic", Role.VIEWER, null, List.of(), false),
                new User("mgr", "Mgr", MANAGE_ONLY, null, List.of(), false)));
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
        new EndpointEditor(sites, permissions, sessions, store, plugins, config).register(web);
        web.start();
    }

    @AfterEach
    void stop() {
        if (web != null) web.close();
        if (plugins != null) plugins.close();
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
        String csrf = get("/test-csrf", new Session(cookie, null)).body();
        assertEquals(64, csrf.length());
        return new Session(cookie, csrf);
    }

    private HttpResponse<String> get(String path, Session session) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + web.port() + path))
                .timeout(Duration.ofSeconds(10));
        if (session != null) b.header("Cookie", session.cookie());
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, Session session, Map<String, String> fields) throws Exception {
        Map<String, String> all = new LinkedHashMap<>();
        if (session != null) all.put("csrf", session.csrf());
        all.putAll(fields);
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

    /** A valid "test" endpoint form, plus {@code extra} fields. */
    private static Map<String, String> testForm(String id, String host, String... extra) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("version", "0");
        f.put("id", id);
        f.put("name", "Name " + id);
        f.put("type", "test");
        f.put("settingsFor", "test");
        f.put("s.host", host);
        f.put("s.port", "25");
        f.put("notifyMode", "default");
        f.put("action", "save");
        for (int i = 0; i < extra.length; i += 2) f.put(extra[i], extra[i + 1]);
        return f;
    }

    /** The HTML of the form field {@code key} (its {@code <div class="field" data-field=…>}). */
    private static String field(String html, String key) {
        String start = "<div class=\"field\" data-field=\"" + key + "\">";
        int from = html.indexOf(start);
        assertTrue(from >= 0, "no field " + key);
        return html.substring(from, html.indexOf("</div>", from));
    }

    /** The page without its {@code <template>}s. */
    private static String withoutTemplates(String html) {
        return html.replaceAll("(?s)<template.*?</template>", "");
    }

    // ---- tests ----

    @Test
    void formIsRenderedFromThePluginSettings() throws Exception {
        start(null);
        Session root = login("root");
        HttpResponse<String> r = get("/admin/endpoints/new?type=test", root);
        assertEquals(200, r.statusCode());
        String html = withoutTemplates(r.body());
        // Type dropdown with display names.
        assertTrue(html.contains("<option value=\"test\" selected>Test check (test)</option>"));
        assertTrue(html.contains("<option value=\"http\">HTTP (http)</option>"));
        assertTrue(html.contains("<legend>Test check settings</legend>"));
        // One field per setting, by kind, with label, required marker, help and default.
        String host = field(html, "host");
        assertTrue(host.contains("Host (required)"));
        assertTrue(host.contains("name=\"s.host\""));
        assertTrue(host.contains(" required"));
        assertTrue(host.contains("The host to test"));
        assertTrue(field(html, "port").contains("name=\"s.port\" value=\"25\""));
        String secure = field(html, "secure");
        assertTrue(secure.contains("<select") && secure.contains("Not set (default: no)"));
        assertTrue(secure.contains("<option value=\"false\" selected>No</option>"));
        String mode = field(html, "mode");
        assertTrue(mode.contains("<option value=\"fast\">fast</option>") && mode.contains("<option value=\"slow\">slow</option>"));
        assertTrue(field(html, "tags").contains("<textarea"));
        // Common fields, with the defaults as placeholders and channel checkboxes.
        assertTrue(field(html, "intervalSeconds").contains("placeholder=\"45\""));
        assertTrue(html.contains("name=\"notify.ops\"") && html.contains("name=\"notify.discord\""));
        assertTrue(html.contains("Use defaults (ops)"));
        assertTrue(html.contains("<option value=\"web\">Websites</option>"));
        // Save is available; the http type's fields are only in its template.
        assertTrue(html.contains("value=\"save\" class=\"button button-primary ee-save\">"));
        assertFalse(html.contains("name=\"s.url\""));
        assertTrue(r.body().contains("<template class=\"ee-settings-template\" data-type=\"http\">"));
        assertTrue(r.body().contains("name=\"s.url\""));
    }

    @Test
    void withoutJsTheFormHasTwoSteps() throws Exception {
        start(null);
        Session root = login("root");
        String first = withoutTemplates(get("/admin/endpoints/new", root).body());
        assertTrue(first.contains("Choose a type…"));
        assertTrue(first.contains("name=\"action\" value=\"type\" formnovalidate>Continue</button>"));
        assertTrue(first.contains("ee-save\" hidden>Save</button>"));
        assertFalse(first.contains("name=\"s."));

        HttpResponse<String> second = post("/admin/endpoints/new", root, Map.of(
                "version", "0", "id", "shop", "name", "Shop", "type", "test", "intervalSeconds", "90",
                "action", "type"));
        assertEquals(200, second.statusCode());
        String html = withoutTemplates(second.body());
        assertTrue(html.contains("name=\"s.host\""));
        assertTrue(field(html, "port").contains("value=\"25\""), "defaults filled in for the new type");
        assertTrue(field(html, "id").contains("value=\"shop\""));
        assertTrue(field(html, "name").contains("value=\"Shop\""));
        assertTrue(field(html, "intervalSeconds").contains("value=\"90\""));
        assertTrue(field(html, "intervalSeconds").contains("overrides default"));
        assertFalse(html.contains("form-error"));
        assertEquals(0, store.version(), "nothing saved");
    }

    @Test
    void saveCreatesTheEndpointAndShowsItForEditing() throws Exception {
        start(null);
        Session root = login("root");
        HttpResponse<String> r = post("/admin/endpoints/new", root, testForm("shop", "shop.example.invalid",
                "category", "web", "s.tags", "a\n\nb", "s.secure", "true", "notifyMode", "channels",
                "notify.discord", "on", "reminders", "10, 20"));
        assertEquals(303, r.statusCode(), r.body());
        assertEquals("/admin/endpoints/shop?saved=1", r.headers().firstValue("Location").orElseThrow());
        EndpointStore.StoredEndpoint stored = store.endpoint("shop").orElseThrow();
        EndpointDefinition d = stored.definition();
        assertNotNull(d);
        assertEquals("web", d.category());
        assertEquals(45, d.intervalSeconds(), "blank means default");
        assertEquals(List.of("discord"), d.notifyChannels());
        assertEquals(List.of(10, 20), d.reminders());
        assertEquals("{\"id\":\"shop\",\"name\":\"Name shop\",\"type\":\"test\",\"category\":\"web\","
                + "\"notify\":[\"discord\"],\"reminders\":[10,20],\"host\":\"shop.example.invalid\",\"port\":25,"
                + "\"secure\":true,\"tags\":[\"a\",\"b\"]}", stored.entry().toString());

        String page = get("/admin/endpoints/shop?saved=1", root).body();
        assertTrue(page.contains("Saved."));
        String html = withoutTemplates(page);
        assertTrue(field(html, "id").contains("disabled"));
        assertTrue(field(html, "host").contains("value=\"shop.example.invalid\""));
        assertTrue(field(html, "tags").contains(">a\nb</textarea>"));
        assertTrue(html.contains("name=\"notify.discord\" value=\"on\" checked"));
        assertTrue(html.contains("value=\"channels\" checked"));

        // Edit: the id comes from the path, not the form.
        r = post("/admin/endpoints/shop", root, testForm("other", "shop.example.invalid", "version", "1", "name", "Shop 2"));
        assertEquals(303, r.statusCode(), r.body());
        assertEquals("Shop 2", store.endpoint("shop").orElseThrow().definition().name());
        assertTrue(store.endpoint("other").isEmpty());
    }

    @Test
    void pluginValidationErrorIsShownOnItsField() throws Exception {
        start(null);
        Session root = login("root");
        HttpResponse<String> r = post("/admin/endpoints/new", root, testForm("shop", "bad"));
        assertEquals(400, r.statusCode());
        String html = withoutTemplates(r.body());
        String host = field(html, "host");
        assertTrue(host.contains("class=\"field-error form-error\""));
        assertTrue(host.contains("this host is not allowed"));
        assertTrue(host.contains("aria-invalid=\"true\""));
        assertTrue(host.contains("value=\"bad\""), "entered values are kept");
        assertFalse(field(html, "port").contains("field-error"));
        assertTrue(html.contains("Nothing was saved."));
        assertEquals(0, store.version());
        assertTrue(store.endpoint("shop").isEmpty());

        // App-side validation, same code as config loading, also lands on the fields.
        r = post("/admin/endpoints/new", root, testForm("shop", "ok.example.invalid", "intervalSeconds", "0",
                "name", "Website"));
        assertEquals(400, r.statusCode());
        html = withoutTemplates(r.body());
        assertTrue(field(html, "intervalSeconds").contains("must be between 1 and 86400"));
        assertTrue(field(html, "name").contains("duplicate endpoint name"));
        // Form-level parsing errors too.
        r = post("/admin/endpoints/new", root, testForm("shop", "ok.example.invalid", "s.port", "abc"));
        assertEquals(400, r.statusCode());
        assertTrue(field(withoutTemplates(r.body()), "port").contains("must be a whole number"));
        // An id in use is rejected on the id field.
        r = post("/admin/endpoints/new", root, testForm("website", "ok.example.invalid"));
        assertEquals(400, r.statusCode());
        assertTrue(field(withoutTemplates(r.body()), "id").contains("is already in use"));
        assertEquals(0, store.version());
    }

    @Test
    void disabledEndpointShowsItsStoredErrorsOnTheFields() throws Exception {
        start("""
                { "version": 3, "endpoints": [
                  { "id": "broken", "name": "Broken", "type": "test", "host": "bad" } ] }""");
        assertFalse(store.endpoint("broken").orElseThrow().enabled());
        Session root = login("root");
        String list = get("/admin/endpoints", root).body();
        assertTrue(list.contains("badge-disabled"));
        String html = withoutTemplates(get("/admin/endpoints/broken", root).body());
        assertTrue(html.contains("This service is disabled"));
        assertTrue(field(html, "host").contains("this host is not allowed"));
    }

    @Test
    void staleVersionGets409AndKeepsTheValues() throws Exception {
        start(null);
        Session root = login("root");
        assertEquals(303, post("/admin/endpoints/new", root, testForm("one", "one.example.invalid")).statusCode());
        HttpResponse<String> r = post("/admin/endpoints/new", root, testForm("two", "two.example.invalid"));
        assertEquals(409, r.statusCode());
        assertTrue(r.body().contains("Someone else changed the services"));
        assertTrue(r.body().contains("name=\"version\" value=\"1\""));
        assertTrue(field(withoutTemplates(r.body()), "host").contains("value=\"two.example.invalid\""));
        assertTrue(store.endpoint("two").isEmpty());
    }

    @Test
    void configDefinedEndpointIsReadOnly() throws Exception {
        start(null);
        Session root = login("root");
        HttpResponse<String> r = get("/admin/endpoints/website", root);
        assertEquals(200, r.statusCode());
        String html = r.body();
        assertTrue(html.contains("<span class=\"badge badge-config\" title=\"Defined in config.json\">config</span>"));
        assertTrue(html.contains("<fieldset class=\"ee-all\" disabled>"));
        assertTrue(field(html, "url").contains("value=\"https://website.example.invalid\""));
        assertFalse(html.contains("class=\"endpoint-form\" method=\"post\""));
        assertFalse(html.contains("value=\"save\""));
        assertTrue(get("/admin/endpoints", root).body().contains("badge-config"));

        Path file = dataDir.resolve(EndpointStore.FILE_NAME);
        r = post("/admin/endpoints/website", root, testForm("website", "evil.example.invalid", "type", "http",
                "s.url", "https://evil.example.invalid", "s.maxClass", "5"));
        assertEquals(403, r.statusCode());
        assertTrue(r.body().contains("defined in config.json"));
        assertEquals(0, store.version());
        assertFalse(Files.exists(file));
        assertEquals("https://website.example.invalid",
                store.endpoint("website").orElseThrow().definition().settings().get("url").getAsString());
    }

    @Test
    void withoutEndpointsManageItIs403() throws Exception {
        start(null);
        // Anonymous visitors are sent to the sign-in page.
        HttpResponse<String> r = get("/admin/endpoints/new", null);
        assertEquals(303, r.statusCode());
        assertTrue(r.headers().firstValue("Location").orElseThrow().startsWith("/login?next="));
        assertEquals(303, post("/admin/endpoints/new", null, testForm("shop", "x.example.invalid")).statusCode());

        Session vic = login("vic");
        for (String path : List.of("/admin/endpoints", "/admin/endpoints/new", "/admin/endpoints/website")) {
            assertEquals(403, get(path, vic).statusCode(), path);
        }
        assertEquals(403, post("/admin/endpoints/new", vic, testForm("shop", "x.example.invalid")).statusCode());
        // endpoints.manage alone isn't enough: the editor shows private services and targets.
        Session mgr = login("mgr");
        assertEquals(403, get("/admin/endpoints/new", mgr).statusCode());
        assertEquals(403, post("/admin/endpoints/new", mgr, testForm("shop", "x.example.invalid")).statusCode());
        assertEquals(0, store.version());
        assertTrue(store.endpoint("shop").isEmpty());

        // A wrong CSRF token is refused too, even with the permission.
        Session root = login("root");
        assertEquals(403, post("/admin/endpoints/new", new Session(root.cookie(), "0".repeat(64)),
                testForm("shop", "x.example.invalid")).statusCode());
        assertEquals(0, store.version());
    }

    @Test
    void unknownIdsAre404() throws Exception {
        start(null);
        Session root = login("root");
        assertEquals(404, get("/admin/endpoints/nope", root).statusCode());
        assertEquals(404, get("/admin/endpoints/Bad_Id", root).statusCode());
        assertEquals(404, get("/admin/endpoints/website/x", root).statusCode());
        assertEquals(404, post("/admin/endpoints/nope", root, testForm("nope", "x.example.invalid")).statusCode());
    }
}
