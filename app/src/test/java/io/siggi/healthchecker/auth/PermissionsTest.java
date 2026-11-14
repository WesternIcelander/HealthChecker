package io.siggi.healthchecker.auth;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.siggi.healthchecker.api.CheckResult;
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
import io.siggi.healthchecker.web.layout.ResolvedLayout;
import io.siggi.healthchecker.web.page.LoginPage;
import io.siggi.healthchecker.web.page.ServicePage;
import io.siggi.healthchecker.web.page.StatusPage;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Users, roles and permissions end to end: sessions → {@link Permissions} → the real pages.
 */
class PermissionsTest {
    private static final Instant NOW = Instant.parse("2026-11-13T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String PRIVATE_TARGET = "http://10.0.0.5:8080";
    private static final Role ANNOTATOR = new Role("annotator", EnumSet.of(Permission.EVENTS_ANNOTATE));
    private static final Pattern NAV = Pattern.compile("<nav class=\"user-nav\".*?</nav>");
    private static final Pattern LOGOUT_CSRF = Pattern.compile(
            "<form class=\"logout\" method=\"post\" action=\"/logout\"><input type=\"hidden\" name=\"csrf\" value=\"([0-9a-f]{64})\">");

    @TempDir
    Path dataDir;

    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final AtomicReference<UserDirectory> users = new AtomicReference<>();
    private final Map<String, Endpoint> endpoints = new LinkedHashMap<>();
    private final Map<String, EndpointStatus> statuses = new LinkedHashMap<>();
    private WebServer web;
    private SessionManager sessions;
    private HistoryStore history;

    private static UserDirectory directory(boolean carolDisabled, boolean withCarol) {
        List<User> list = new ArrayList<>(List.of(
                new User("root", "Root <Admin>", Role.ADMIN, null, List.of(), false),
                new User("vic", "Vic", Role.VIEWER, null, List.of(), false),
                new User("ann", "Ann", ANNOTATOR, null, List.of(), false)));
        if (withCarol) list.add(new User("carol", "Carol", Role.VIEWER, null, List.of(), carolDisabled));
        return UserDirectory.of(list);
    }

    private void add(String id, Visibility visibility, String target) {
        EndpointDefinition def = new EndpointDefinition(id, "Name " + id, "http", "web", visibility, 60, 5000, 1,
                List.of(), List.of(), 0, new JsonObject());
        endpoints.put(id, new Endpoint(def, List.of(new Target("url", target)), timeout -> CheckResult.ok("ok")));
        CheckRecord pass = new CheckRecord(NOW.minusSeconds(60), Duration.ofMillis(84),
                new CheckResult(true, "status 200", Map.of("statusCode", 200)));
        statuses.put(id, new EndpointStatus(id, State.UP, NOW.minusSeconds(3600), 0, 0, null, pass.message(), pass,
                List.of(pass)));
    }

    @BeforeEach
    void start() throws Exception {
        users.set(directory(false, true));
        add("website", Visibility.PUBLIC, "https://www.example.com");
        add("intranet", Visibility.PRIVATE, PRIVATE_TARGET);
        List<EndpointDefinition> defs = endpoints.values().stream().map(Endpoint::definition).toList();
        List<CategoryConfig> categories = List.of(new CategoryConfig("web", "Websites", null));

        web = new WebServer(new ServerConfig(0, "127.0.0.1", null, List.of(), UnknownHostsPolicy.DEFAULT));
        sessions = new SessionManager(dataDir.resolve("sessions"), Duration.ofDays(7));
        Permissions permissions = new Permissions(sessions, id -> users.get().user(id));
        SiteResolver sites = new SiteResolver(List.of(), "Status", null, UnknownHostsPolicy.DEFAULT,
                web.trustedProxies());
        history = HistoryStore.open(dataDir, CLOCK, System.getLogger(PermissionsTest.class.getName()));
        UptimeCalculator uptime = new UptimeCalculator(history, ZoneOffset.UTC, false, CLOCK);
        DashboardConfig dashboard = new DashboardConfig("Status", 100.0, 99.0, 95.0);

        // Test-only sign-in: starts a session for ?user= without a password.
        web.register("/test-login", request -> {
            sessions.login(request, request.get().get("user"));
            text(request, "ok");
        });
        // A responder guarded by a permission, as dashboard editing will be.
        web.register("/needs-layout", request -> {
            if (!permissions.require(request, sites.resolve(request).orElseThrow(), Permission.LAYOUT_EDIT)) return;
            text(request, "allowed");
        });
        new LoginPage(sites, sessions, permissions,
                new LocalLogin(true, username -> Optional.empty(), new LoginRateLimiter()), web.trustedProxies())
                .register(web);
        new StatusPage(sites, () -> ResolvedLayout.resolve(null, categories, defs),
                id -> Optional.ofNullable(endpoints.get(id)), id -> Optional.ofNullable(statuses.get(id)),
                permissions::viewer, uptime, dashboard).register(web);
        new ServicePage(sites, () -> ResolvedLayout.resolve(null, categories, defs),
                id -> Optional.ofNullable(endpoints.get(id)), id -> Optional.ofNullable(statuses.get(id)),
                permissions::viewer, uptime, history, CLOCK, dashboard).register(web);
        web.start();
    }

    @AfterEach
    void stop() {
        if (web != null) web.close();
        if (history != null) history.close();
    }

    private static void text(io.siggi.http.HTTPRequest request, String body) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        request.response.setHeader("Content-Type", "text/plain; charset=utf-8");
        request.response.setHeader("Content-Length", Integer.toString(bytes.length));
        request.response.write(bytes);
    }

    private HttpResponse<String> get(String path, String cookie) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + web.port() + path))
                .timeout(Duration.ofSeconds(10));
        if (cookie != null) b.header("Cookie", cookie);
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String form, String cookie) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + web.port() + path))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form));
        if (cookie != null) b.header("Cookie", cookie);
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String sessionSetCookie(HttpResponse<?> r) {
        for (String c : r.headers().allValues("Set-Cookie")) {
            if (c.startsWith(sessions.cookieName() + "=")) return c;
        }
        return null;
    }

    /** Signs {@code user} in and returns the {@code Cookie} header value. */
    private String login(String user) throws Exception {
        HttpResponse<String> r = get("/test-login?user=" + user, null);
        assertEquals(200, r.statusCode());
        String c = sessionSetCookie(r);
        assertNotNull(c);
        return c.substring(0, c.indexOf(';'));
    }

    private static JsonObject json(HttpResponse<String> r) {
        return JsonParser.parseString(r.body()).getAsJsonObject();
    }

    private static String withoutNav(String html) {
        return NAV.matcher(html).replaceAll("");
    }

    // ---- views ----

    @Test
    void userWithoutViewPrivateSeesTheAnonymousView() throws Exception {
        String ann = login("ann");

        JsonObject anonymous = json(get("/api/status", null));
        JsonObject annotator = json(get("/api/status", ann));
        assertEquals("anonymous", anonymous.remove("viewer").getAsString());
        assertEquals("Ann", annotator.remove("viewer").getAsString());
        assertEquals(anonymous, annotator);
        assertFalse(annotator.toString().contains("intranet"), annotator.toString());

        JsonObject anonymousService = json(get("/api/service/website", null));
        JsonObject annotatorService = json(get("/api/service/website", ann));
        anonymousService.remove("viewer");
        annotatorService.remove("viewer");
        assertEquals(anonymousService, annotatorService);

        // HTML: the same page apart from the header's user area.
        for (String path : List.of("/", "/service/website")) {
            String anonymousHtml = get(path, null).body();
            String annotatorHtml = get(path, ann).body();
            assertEquals(withoutNav(anonymousHtml), withoutNav(annotatorHtml), path);
            assertFalse(annotatorHtml.contains("Name intranet"), path);
            assertFalse(annotatorHtml.contains("www.example.com"), path);
        }

        // A viewer does see the private endpoint and the targets.
        String vic = login("vic");
        JsonObject viewer = json(get("/api/status", vic));
        assertEquals("Vic", viewer.get("viewer").getAsString());
        assertTrue(viewer.toString().contains("intranet"), viewer.toString());
        assertTrue(get("/", vic).body().contains("Name intranet"));
    }

    @Test
    void privateEndpointIs404WithoutViewPrivate() throws Exception {
        String ann = login("ann");
        for (String cookie : new String[]{null, ann}) {
            for (String path : List.of("/service/intranet", "/api/service/intranet", "/api/service/intranet/events",
                    "/service/intranet?view=calendar")) {
                HttpResponse<String> r = get(path, cookie);
                assertEquals(404, r.statusCode(), path + " as " + cookie);
                assertFalse(r.body().contains("intranet"), path);
            }
        }
        // Same 404 body as an id that doesn't exist, so existence isn't revealed.
        assertEquals(get("/service/nope", ann).body(), get("/service/intranet", ann).body());

        String vic = login("vic");
        HttpResponse<String> page = get("/service/intranet", vic);
        assertEquals(200, page.statusCode());
        assertTrue(page.body().contains(PRIVATE_TARGET), "view.private shows the target");
        assertEquals(200, get("/api/service/intranet", vic).statusCode());
    }

    // ---- disabled users ----

    @Test
    void disabledUserSessionIsEnded() throws Exception {
        String carol = login("carol");
        assertEquals("Carol", json(get("/api/status", carol)).get("viewer").getAsString());

        // The config now says disabled (as after a restart with the changed config).
        users.set(directory(true, true));
        HttpResponse<String> r = get("/api/status", carol);
        assertEquals("anonymous", json(r).get("viewer").getAsString());
        assertFalse(r.body().contains("intranet"));
        String cleared = sessionSetCookie(r);
        assertNotNull(cleared, "the session cookie is cleared");
        assertTrue(cleared.endsWith("; Max-Age=0"), cleared);
        assertEquals(404, get("/service/intranet", carol).statusCode());

        // The session is gone for good: re-enabling the user doesn't bring it back.
        users.set(directory(false, true));
        assertEquals("anonymous", json(get("/api/status", carol)).get("viewer").getAsString());
        assertEquals("Carol", json(get("/api/status", login("carol"))).get("viewer").getAsString());
    }

    @Test
    void removedUserSessionIsEnded() throws Exception {
        String carol = login("carol");
        users.set(directory(false, false));
        HttpResponse<String> r = get("/", carol);
        assertTrue(r.body().contains(">Sign in</a>"));
        assertFalse(r.body().contains("Carol"));
        assertNotNull(sessionSetCookie(r));
        users.set(directory(false, true));
        assertEquals("anonymous", json(get("/api/status", carol)).get("viewer").getAsString());
    }

    @Test
    void loginPageShowsSignedInUserButNotDisabledOne() throws Exception {
        String carol = login("carol");
        assertTrue(get("/login", carol).body().contains("signed in as <strong>Carol</strong> (viewer)"));
        users.set(directory(true, true));
        String body = get("/login", carol).body();
        assertFalse(body.contains("signed in as"), body);
        assertTrue(body.contains("name=\"password\""), body);
    }

    // ---- header ----

    @Test
    void headerShowsUserRoleAndSignOut() throws Exception {
        String anonymous = get("/", null).body();
        assertTrue(anonymous.contains("<nav class=\"user-nav\" aria-label=\"Account\"><a href=\"/login\">Sign in</a></nav>"),
                anonymous);
        assertFalse(anonymous.contains("user-name"));
        assertFalse(anonymous.contains("name=\"csrf\""), "no form, so no session, for anonymous visitors");

        String root = login("root");
        for (String path : List.of("/", "/service/website", "/service/intranet")) {
            String html = get(path, root).body();
            assertTrue(html.contains("<span class=\"user-name\">Root &lt;Admin&gt;</span>"), path);
            assertTrue(html.contains("<span class=\"user-role\">admin</span>"), path);
            assertFalse(html.contains(">Sign in</a>"), path);
            Matcher m = LOGOUT_CSRF.matcher(html);
            assertTrue(m.find(), path + ": " + html);
        }
        // The sign-out button works.
        Matcher m = LOGOUT_CSRF.matcher(get("/", root).body());
        assertTrue(m.find());
        HttpResponse<String> out = post("/logout", "csrf=" + m.group(1), root);
        assertEquals(303, out.statusCode());
        assertEquals("anonymous", json(get("/api/status", root)).get("viewer").getAsString());

        // A user without view.private still gets the header.
        String ann = get("/", login("ann")).body();
        assertTrue(ann.contains("<span class=\"user-name\">Ann</span> <span class=\"user-role\">annotator</span>"), ann);
    }

    // ---- enforcement ----

    @Test
    void requireRedirectsAnonymousAndForbidsMissingPermission() throws Exception {
        HttpResponse<String> anonymous = get("/needs-layout?x=1", null);
        assertEquals(303, anonymous.statusCode());
        assertEquals("/login?next=" + URLEncoder.encode("/needs-layout?x=1", StandardCharsets.UTF_8),
                anonymous.headers().firstValue("Location").orElse(null));
        assertNull(sessionSetCookie(anonymous));

        HttpResponse<String> forbidden = get("/needs-layout", login("ann"));
        assertEquals(403, forbidden.statusCode());
        assertEquals("You don't have permission to do this.\n", forbidden.body());

        assertEquals(403, get("/needs-layout", login("vic")).statusCode());
        HttpResponse<String> allowed = get("/needs-layout", login("root"));
        assertEquals(200, allowed.statusCode());
        assertEquals("allowed", allowed.body());

        // A disabled user is treated as anonymous.
        String carol = login("carol");
        users.set(directory(true, true));
        assertEquals(303, get("/needs-layout", carol).statusCode());
    }
}
