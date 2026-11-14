package io.siggi.healthchecker.web.page;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.siggi.healthchecker.api.CheckPlugin;
import io.siggi.healthchecker.api.CheckResult;
import io.siggi.healthchecker.api.Checker;
import io.siggi.healthchecker.api.ConfigException;
import io.siggi.healthchecker.api.EndpointConfig;
import io.siggi.healthchecker.api.SettingSpec;
import io.siggi.healthchecker.auth.ActionRateLimiter;
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
import io.siggi.healthchecker.history.HistoryStore;
import io.siggi.healthchecker.status.CheckRecord;
import io.siggi.healthchecker.status.StateChange;
import io.siggi.healthchecker.status.StatusRegistry;
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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The endpoint editor ({@code /admin/endpoints…}), end to end over HTTP. */
class EndpointEditorTest {
    private static final Role MANAGE_ONLY = new Role("manage-only", EnumSet.of(Permission.ENDPOINTS_MANAGE));
    /** May manage endpoints but not delete history. */
    private static final Role MANAGER = new Role("manager",
            EnumSet.of(Permission.ENDPOINTS_MANAGE, Permission.VIEW_PRIVATE));

    @TempDir
    Path dataDir;

    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private WebServer web;
    private PluginManager plugins;
    private EndpointStore store;
    private HistoryStore history;
    /** Replaces the default "Test now" limiter when set before {@link #start}. */
    private ActionRateLimiter testLimiter;

    /** Checks run and checkers closed by {@link TestPlugin}. */
    static final AtomicInteger CHECKS = new AtomicInteger();
    static final AtomicInteger CLOSED = new AtomicInteger();

    /** A clock tests can move forward. */
    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");

        @Override
        public java.time.ZoneId getZone() {
            return java.time.ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /**
     * A plugin with one setting of every kind; {@code host = "bad"} is rejected on its field. Its check
     * passes with the host as a detail, or fails ("too slow") with {@code mode = "slow"}.
     */
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
            boolean slow = config.optString("mode").filter("slow"::equals).isPresent();
            return new Checker() {
                @Override
                public CheckResult check(Duration timeout) {
                    CHECKS.incrementAndGet();
                    return slow ? CheckResult.fail("too slow").with("rtt", 5000) : CheckResult.ok("ok").with("checked", host);
                }

                @Override
                public void close() {
                    CLOSED.incrementAndGet();
                }
            };
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
        history = HistoryStore.open(dataDir);
        store.reserveIdsWithHistory(history::hasHistory);

        UserDirectory users = UserDirectory.of(List.of(
                new User("root", "Root", Role.ADMIN, null, List.of(), false),
                new User("vic", "Vic", Role.VIEWER, null, List.of(), false),
                new User("mgr", "Mgr", MANAGE_ONLY, null, List.of(), false),
                new User("ada", "Ada", Role.ADMIN, null, List.of(), false),
                new User("max", "Max", MANAGER, null, List.of(), false)));
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
        CHECKS.set(0);
        CLOSED.set(0);
        (testLimiter == null ? new EndpointEditor(sites, permissions, sessions, store, plugins, config, history)
                : new EndpointEditor(sites, permissions, sessions, store, plugins, config, history, testLimiter))
                .register(web);
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
    void testNowReturnsTheResultWithoutPersisting() throws Exception {
        start(null);
        Session root = login("root");
        Path file = dataDir.resolve(EndpointStore.FILE_NAME);
        HttpResponse<String> r = post("/admin/endpoints/new", root, testForm("probe", "probe.example.invalid",
                "action", "test", "s.mode", "slow"));
        assertEquals(200, r.statusCode(), r.body());
        String html = withoutTemplates(r.body());
        assertTrue(html.contains("class=\"ee-test-result ee-test-fail\""));
        assertTrue(html.contains("Test result: failed"));
        assertTrue(html.contains("<dt>Latency</dt><dd>"));
        assertTrue(html.contains("<dt>Message</dt><dd>too slow</dd>"));
        assertTrue(html.contains("<li>rtt: 5000</li>"));
        assertTrue(html.contains("Nothing was saved."));
        assertTrue(field(html, "host").contains("value=\"probe.example.invalid\""), "the form keeps its values");
        assertEquals(1, CHECKS.get());
        assertEquals(CHECKS.get() + 1, CLOSED.get(), "the validation checker and the test checker are both closed");
        assertEquals(0, store.version());
        assertTrue(store.endpoint("probe").isEmpty());
        assertFalse(Files.exists(file));

        // Testing changed values of a saved endpoint leaves the saved entry alone.
        assertEquals(303, post("/admin/endpoints/new", root, testForm("probe", "probe.example.invalid")).statusCode());
        String saved = store.endpoint("probe").orElseThrow().entry().toString();
        String before = Files.readString(file);
        r = post("/admin/endpoints/probe", root, testForm("probe", "new.example.invalid", "version", "1",
                "action", "test"));
        assertEquals(200, r.statusCode(), r.body());
        html = withoutTemplates(r.body());
        assertTrue(html.contains("Test result: passed"));
        assertTrue(html.contains("<li>checked: new.example.invalid</li>"));
        assertEquals(1, store.version());
        assertEquals(saved, store.endpoint("probe").orElseThrow().entry().toString());
        assertEquals(before, Files.readString(file));
    }

    @Test
    void testNowValidatesLikeSaveAndRunsNothingWhenInvalid() throws Exception {
        start(null);
        Session root = login("root");
        HttpResponse<String> r = post("/admin/endpoints/new", root, testForm("probe", "bad", "action", "test"));
        assertEquals(400, r.statusCode());
        String html = withoutTemplates(r.body());
        assertTrue(field(html, "host").contains("this host is not allowed"));
        assertFalse(html.contains("ee-test-result"));
        assertEquals(0, CHECKS.get());
        // The button is part of the form once a type is chosen.
        assertTrue(html.contains("name=\"action\" value=\"test\""));
        // Config-defined endpoints can't be tested through their (read-only) form, nor without the permission.
        assertEquals(403, post("/admin/endpoints/website", root, testForm("website", "x.example.invalid",
                "action", "test")).statusCode());
        assertEquals(403, post("/admin/endpoints/new", login("vic"), testForm("probe", "x.example.invalid",
                "action", "test")).statusCode());
        assertEquals(403, post("/admin/endpoints/new", login("mgr"), testForm("probe", "x.example.invalid",
                "action", "test")).statusCode());
        assertEquals(0, CHECKS.get());
    }

    @Test
    void testNowIsRateLimitedPerUser() throws Exception {
        MutableClock clock = new MutableClock();
        testLimiter = new ActionRateLimiter(2, Duration.ofMinutes(1), clock);
        start(null);
        Session root = login("root");
        Map<String, String> form = testForm("probe", "probe.example.invalid", "action", "test");
        assertEquals(200, post("/admin/endpoints/new", root, form).statusCode());
        clock.now = clock.now.plusSeconds(10);
        // An invalid form counts too: validation already runs the plugin.
        assertEquals(400, post("/admin/endpoints/new", root,
                testForm("probe", "bad", "action", "test")).statusCode());
        HttpResponse<String> r = post("/admin/endpoints/new", root, form);
        assertEquals(429, r.statusCode());
        assertEquals("50", r.headers().firstValue("Retry-After").orElseThrow());
        assertTrue(r.body().contains("Too many tests. Try again in 50 s."));
        assertTrue(field(withoutTemplates(r.body()), "host").contains("value=\"probe.example.invalid\""));
        assertEquals(1, CHECKS.get());

        // Per user: someone else can still test.
        assertEquals(200, post("/admin/endpoints/new", login("ada"), form).statusCode());
        assertEquals(2, CHECKS.get());
        // Saving isn't limited.
        assertEquals(303, post("/admin/endpoints/new", root, testForm("probe", "probe.example.invalid")).statusCode());
        // Once the first test leaves the window, one more is allowed.
        clock.now = clock.now.plusSeconds(50);
        assertEquals(200, post("/admin/endpoints/probe", root, testForm("probe", "probe.example.invalid",
                "action", "test", "version", "1")).statusCode());
        assertEquals(429, post("/admin/endpoints/probe", root, testForm("probe", "probe.example.invalid",
                "action", "test", "version", "1")).statusCode());
        assertEquals(3, CHECKS.get());
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

    // ---- delete ----

    /** Saves dashboard endpoint {@code id}, records a failed check (an outage) for it and stops monitoring it. */
    private void createWithHistory(String id) throws Exception {
        Session root = login("root");
        assertEquals(303, post("/admin/endpoints/new", root,
                testForm(id, id + ".example.invalid", "version", Integer.toString(store.version()))).statusCode());
        StatusRegistry status = new StatusRegistry();
        status.register(id, 1);
        CheckRecord failed = new CheckRecord(Instant.parse("2026-01-01T00:00:00Z"), Duration.ofMillis(5),
                CheckResult.fail("down"));
        StateChange change = status.record(id, failed).orElseThrow();
        history.record(id, failed, change);
        history.stopMonitoring(id);
        assertTrue(history.hasHistory(id));
        assertEquals(1, history.events(id).size());
    }

    private Map<String, String> deleteForm(String confirm, String... extra) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("version", Integer.toString(store.version()));
        f.put("confirm", confirm);
        for (int i = 0; i < extra.length; i += 2) f.put(extra[i], extra[i + 1]);
        return f;
    }

    @Test
    void deleteAsksForTheIdAndKeepsHistoryByDefault() throws Exception {
        start(null);
        createWithHistory("shop");
        Session root = login("root");
        String edit = get("/admin/endpoints/shop", root).body();
        assertTrue(edit.contains("href=\"/admin/endpoints/shop/delete\""), "edit form links to delete");

        HttpResponse<String> page = get("/admin/endpoints/shop/delete", root);
        assertEquals(200, page.statusCode());
        assertTrue(page.body().contains("name=\"confirm\""));
        assertTrue(page.body().contains("name=\"deleteHistory\""), "admin may delete history");
        assertFalse(page.body().contains("name=\"deleteHistory\" value=\"on\" checked"), "unticked by default");

        // Wrong confirmation: nothing deleted.
        HttpResponse<String> wrong = post("/admin/endpoints/shop/delete", root, deleteForm("shops"));
        assertEquals(400, wrong.statusCode());
        assertTrue(wrong.body().contains("Type the id"));
        assertTrue(store.endpoint("shop").isPresent());
        // No CSRF token: refused.
        assertEquals(403, post("/admin/endpoints/shop/delete", new Session(root.cookie(), "x"), deleteForm("shop"))
                .statusCode());
        assertTrue(store.endpoint("shop").isPresent());

        HttpResponse<String> r = post("/admin/endpoints/shop/delete", root, deleteForm("shop"));
        assertEquals(303, r.statusCode(), r.body());
        assertEquals("/admin/endpoints?deleted=shop", r.headers().firstValue("Location").orElseThrow());
        assertTrue(store.endpoint("shop").isEmpty());
        assertFalse(Files.readString(dataDir.resolve(EndpointStore.FILE_NAME)).contains("\"shop\""));
        // History kept, in memory and on disk.
        assertTrue(history.hasHistory("shop"));
        assertEquals(1, history.events("shop").size());
        assertTrue(Files.isDirectory(dataDir.resolve("endpoints").resolve("shop")));
        assertTrue(get("/admin/endpoints?deleted=shop", root).body().contains("Its history was kept"));
        assertEquals(404, get("/admin/endpoints/shop/delete", root).statusCode());
    }

    @Test
    void historyIsDeletedOnlyWithPermission() throws Exception {
        start(null);
        createWithHistory("shop");
        createWithHistory("blog");
        Path shopDir = dataDir.resolve("endpoints").resolve("shop");
        assertTrue(Files.isDirectory(shopDir));

        // Without history.delete: no checkbox, and ticking it anyway is refused with nothing deleted.
        Session max = login("max");
        String page = get("/admin/endpoints/shop/delete", max).body();
        assertFalse(page.contains("name=\"deleteHistory\""));
        assertTrue(page.contains("history.delete"));
        HttpResponse<String> refused = post("/admin/endpoints/shop/delete", max, deleteForm("shop", "deleteHistory", "on"));
        assertEquals(403, refused.statusCode());
        assertTrue(store.endpoint("shop").isPresent());
        assertTrue(history.hasHistory("shop"));

        // With it: endpoint and history are gone, on disk too.
        Session root = login("root");
        HttpResponse<String> r = post("/admin/endpoints/shop/delete", root, deleteForm("shop", "deleteHistory", "on"));
        assertEquals(303, r.statusCode(), r.body());
        assertEquals("/admin/endpoints?deleted=shop&history=deleted", r.headers().firstValue("Location").orElseThrow());
        assertTrue(store.endpoint("shop").isEmpty());
        assertFalse(history.hasHistory("shop"));
        assertTrue(history.events("shop").isEmpty());
        assertFalse(Files.exists(shopDir));
        try (var files = Files.list(dataDir.resolve("endpoints"))) {
            assertEquals(List.of("blog"), files.map(f -> f.getFileName().toString()).toList(), "nothing left behind");
        }
        // The other endpoint's history is untouched.
        assertEquals(1, history.events("blog").size());
        // A user without history.delete can still delete a service, keeping its history.
        assertEquals(303, post("/admin/endpoints/blog/delete", max, deleteForm("blog")).statusCode());
        assertTrue(history.hasHistory("blog"));

        // After a reload, the deleted history stays deleted.
        history.close();
        history = HistoryStore.open(dataDir);
        assertFalse(history.hasHistory("shop"));
        assertTrue(history.hasHistory("blog"));
    }

    @Test
    void idCantBeReusedWhileItsHistoryExists() throws Exception {
        start(null);
        createWithHistory("shop");
        Session root = login("root");
        assertEquals(303, post("/admin/endpoints/shop/delete", root, deleteForm("shop")).statusCode());

        HttpResponse<String> reuse = post("/admin/endpoints/new", root,
                testForm("shop", "other.example.invalid", "version", Integer.toString(store.version())));
        assertEquals(400, reuse.statusCode());
        assertTrue(field(withoutTemplates(reuse.body()), "id").contains("still has history"), reuse.body());
        assertTrue(store.endpoint("shop").isEmpty());
        // The store itself refuses it too.
        JsonObject entry = new JsonObject();
        entry.addProperty("id", "shop");
        entry.addProperty("name", "Other shop");
        entry.addProperty("type", "test");
        entry.addProperty("host", "other.example.invalid");
        EndpointStore.InvalidEntryException e = org.junit.jupiter.api.Assertions.assertThrows(
                EndpointStore.InvalidEntryException.class, () -> store.putEndpoint(store.version(), entry));
        assertTrue(e.getMessage().contains("still has history"));

        // Once the history is deleted, the id is free again.
        assertTrue(history.deleteHistory("shop"));
        assertEquals(303, post("/admin/endpoints/new", root,
                testForm("shop", "other.example.invalid", "version", Integer.toString(store.version()))).statusCode());
        assertTrue(store.endpoint("shop").isPresent());
        // Editing an existing endpoint isn't affected by its own history.
        history.rememberTargets("shop", List.of("other.example.invalid"));
        assertEquals(303, post("/admin/endpoints/shop", root, testForm("shop", "third.example.invalid",
                "version", Integer.toString(store.version()))).statusCode());
    }

    @Test
    void configDefinedEndpointCantBeDeleted() throws Exception {
        start(null);
        Session root = login("root");
        assertEquals(403, get("/admin/endpoints/website/delete", root).statusCode());
        assertEquals(403, post("/admin/endpoints/website/delete", root, deleteForm("website")).statusCode());
        assertTrue(store.endpoint("website").isPresent());
        assertFalse(get("/admin/endpoints/website", root).body().contains("/delete"));
        // Viewers can't reach the delete page at all.
        assertEquals(403, get("/admin/endpoints/website/delete", login("vic")).statusCode());
    }

    @Test
    void copyConfigEndpointToDashboard() throws Exception {
        start(null);
        Session root = login("root");
        EndpointDefinition original = store.endpoint("website").orElseThrow().definition();
        assertTrue(get("/admin/endpoints/website", root).body().contains("href=\"/admin/endpoints/website/copy\""));

        HttpResponse<String> page = get("/admin/endpoints/website/copy", root);
        assertEquals(200, page.statusCode());
        assertTrue(page.body().contains("&quot;url&quot;: &quot;https://website.example.invalid&quot;"));
        assertTrue(page.body().contains("value=\"copy\""));
        assertFalse(page.body().contains("placeholders"));

        // Needs endpoints.manage and CSRF.
        assertEquals(403, post("/admin/endpoints/website/copy", login("vic"), Map.of("version", "0", "action", "copy"))
                .statusCode());
        assertEquals(403, post("/admin/endpoints/website/copy", new Session(root.cookie(), "x".repeat(64)),
                Map.of("version", "0", "action", "copy")).statusCode());
        assertTrue(store.endpointCopy("website").isEmpty());

        HttpResponse<String> copied = post("/admin/endpoints/website/copy", root, Map.of("version", "0", "action", "copy"));
        assertEquals(303, copied.statusCode());
        assertEquals("/admin/endpoints/website/copy?copied=1", copied.headers().firstValue("Location").orElseThrow());
        // The copy is the config entry as written: equivalent once parsed as a dashboard entry.
        JsonObject copy = store.endpointCopy("website").orElseThrow();
        assertEquals(new Gson().fromJson("""
                { "id": "website", "name": "Website", "type": "http", "category": "web",
                  "url": "https://website.example.invalid", "maxClass": 2 }""", JsonObject.class), copy);
        assertEquals(original, ConfigLoader.parseEndpoint(copy, "", new ConfigLoader(n -> null, ZoneId.of("UTC"))
                .load("{ \"defaults\": { \"intervalSeconds\": 45, \"notify\": [\"ops\"] }, "
                        + "\"notifications\": { \"channels\": { \"ops\": {}, \"discord\": {} } } }").defaults(),
                java.util.Set.of("web"), java.util.Set.of("ops", "discord"), Map.of()).value());
        // Still read-only and checked as the config endpoint until the restart.
        assertEquals(EndpointStore.Source.CONFIG, store.endpoint("website").orElseThrow().source());
        assertEquals(1, store.definitions().size());

        // The removal instructions.
        String instructions = get("/admin/endpoints/website/copy?copied=1", root).body();
        assertTrue(instructions.contains("Copied to data/endpoints.json."));
        assertTrue(instructions.contains("Remove this entry from the <code>endpoints</code> array in <code>config.json</code>"));
        assertTrue(instructions.contains("<pre class=\"cd-json cd-remove\">"));
        assertTrue(instructions.contains("Restart HealthChecker"));
        assertTrue(instructions.contains("value=\"cancel\""));
        assertTrue(get("/admin/endpoints", root).body().contains("copied, restart pending"));
        assertEquals(400, post("/admin/endpoints/website/copy", root,
                Map.of("version", Integer.toString(store.version()), "action", "copy")).statusCode());

        assertEquals(303, post("/admin/endpoints/website/copy", root,
                Map.of("version", Integer.toString(store.version()), "action", "cancel")).statusCode());
        assertTrue(store.endpointCopy("website").isEmpty());

        // Dashboard endpoints have nothing to copy.
        assertEquals(303, post("/admin/endpoints/new", root, testForm("shop", "shop.example.invalid",
                "version", Integer.toString(store.version()))).statusCode());
        assertEquals(404, get("/admin/endpoints/shop/copy", root).statusCode());
    }
}
