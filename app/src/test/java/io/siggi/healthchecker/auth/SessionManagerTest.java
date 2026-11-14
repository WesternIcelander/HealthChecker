package io.siggi.healthchecker.auth;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.siggi.healthchecker.config.ServerConfig;
import io.siggi.healthchecker.config.UnknownHostsPolicy;
import io.siggi.healthchecker.history.HistoryStore;
import io.siggi.healthchecker.history.UptimeCalculator;
import io.siggi.healthchecker.web.WebServer;
import io.siggi.healthchecker.web.layout.ResolvedLayout;
import io.siggi.healthchecker.web.page.StatusPage;
import io.siggi.healthchecker.web.site.SiteResolver;
import io.siggi.healthchecker.config.DashboardConfig;
import io.siggi.healthchecker.web.view.Viewer;
import org.junit.jupiter.api.AfterEach;
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
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionManagerTest {
    @TempDir
    Path dataDir;

    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final List<WebServer> servers = new ArrayList<>();
    private final List<HistoryStore> stores = new ArrayList<>();

    @AfterEach
    void stop() {
        servers.forEach(WebServer::close);
        stores.forEach(HistoryStore::close);
    }

    private record Server(WebServer web, SessionManager sessions) {
        String url(String path) {
            return "http://127.0.0.1:" + web.port() + path;
        }
    }

    private Server start(Duration lifetime, String... trustedProxies) throws Exception {
        WebServer web = new WebServer(new ServerConfig(0, "127.0.0.1", null, List.of(trustedProxies),
                UnknownHostsPolicy.DEFAULT));
        servers.add(web);
        SessionManager sessions = new SessionManager(dataDir.resolve("sessions"), lifetime);
        // A page with a form: hands out the CSRF token.
        web.register("/form", request -> text(request, sessions.csrfToken(request)));
        // A page that only reads the session.
        web.register("/whoami", request -> text(request, sessions.userId(request).orElse("anonymous")));
        web.register("/login", request -> {
            request.allowedMethods("POST");
            if (!sessions.requireCsrf(request)) return;
            sessions.login(request, request.post().get("user"));
            text(request, "logged in");
        });
        web.register("/logout", request -> {
            request.allowedMethods("POST");
            if (!sessions.requireCsrf(request)) return;
            sessions.logout(request);
            text(request, "logged out");
        });
        web.register("/action", request -> {
            request.allowedMethods("POST");
            if (!sessions.requireCsrf(request)) return;
            text(request, "done");
        });
        // The real main page, with a viewer function that reads the session.
        SiteResolver sites = new SiteResolver(List.of(), "Status", null, UnknownHostsPolicy.DEFAULT,
                web.trustedProxies());
        Clock clock = Clock.systemUTC();
        HistoryStore history = HistoryStore.open(dataDir, clock, System.getLogger(SessionManagerTest.class.getName()));
        stores.add(history);
        new StatusPage(sites, () -> ResolvedLayout.resolve(null, List.of(), List.of()),
                id -> Optional.empty(), id -> Optional.empty(),
                request -> sessions.userId(request).map(u -> new Viewer(u, false)).orElse(Viewer.ANONYMOUS),
                new UptimeCalculator(history, ZoneOffset.UTC, false, clock),
                new DashboardConfig("Status", 100.0, 99.0, 95.0)).register(web);
        web.start();
        return new Server(web, sessions);
    }

    private Server start(String... trustedProxies) throws Exception {
        return start(Duration.ofDays(7), trustedProxies);
    }

    private static void text(io.siggi.http.HTTPRequest request, String body) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        request.response.setHeader("Content-Type", "text/plain; charset=utf-8");
        request.response.setHeader("Content-Length", Integer.toString(bytes.length));
        request.response.write(bytes);
    }

    private HttpResponse<String> get(Server s, String path, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(s.url(path))).timeout(Duration.ofSeconds(5));
        if (headers.length > 0) b.headers(headers);
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(Server s, String path, String form, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(s.url(path))).timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form));
        if (headers.length > 0) b.headers(headers);
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static List<String> setCookies(HttpResponse<?> r) {
        return r.headers().allValues("Set-Cookie");
    }

    /** The single session Set-Cookie header of a response. */
    private static String sessionCookie(Server s, HttpResponse<?> r) {
        List<String> cookies = setCookies(r).stream().filter(c -> c.startsWith(s.sessions().cookieName() + "="))
                .toList();
        assertEquals(1, cookies.size(), "session cookies: " + setCookies(r));
        return cookies.getFirst();
    }

    /** {@code Cookie} header value for the session cookie a response set. */
    private static String cookieHeader(Server s, HttpResponse<?> r) {
        String c = sessionCookie(s, r);
        return c.substring(0, c.indexOf(';'));
    }

    private static String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }

    /** Opens a pre-login session (as the login form does) and logs in as {@code user}. Returns the Cookie value. */
    private String login(Server s, String user) throws Exception {
        HttpResponse<String> form = get(s, "/form");
        String cookie = cookieHeader(s, form);
        HttpResponse<String> r = post(s, "/login", "user=" + enc(user) + "&csrf=" + enc(form.body()),
                "Cookie", cookie);
        assertEquals(200, r.statusCode(), r.body());
        return cookieHeader(s, r);
    }

    private String csrf(Server s, String cookie) throws Exception {
        HttpResponse<String> r = get(s, "/form", "Cookie", cookie);
        assertEquals(200, r.statusCode());
        return r.body();
    }

    // ---- cookie flags ----------------------------------------------------------------

    @Test
    void cookieFlagsOverHttp() throws Exception {
        Server s = start();
        String c = sessionCookie(s, get(s, "/form"));
        assertTrue(c.matches(s.sessions().cookieName() + "=[0-9a-f]{64}; Path=/; HttpOnly; SameSite=Lax; Max-Age=604800"), c);
        assertFalse(c.contains("Secure"), c);
    }

    @Test
    void secureOverHttpsFromTrustedProxy() throws Exception {
        Server s = start("127.0.0.1");
        String c = sessionCookie(s, get(s, "/form", "X-Forwarded-Proto", "https"));
        assertTrue(c.contains("; Path=/; HttpOnly; Secure; SameSite=Lax; Max-Age=604800"), c);
        String loggedIn = login(s, "alice");
        // Without the proxy saying https, no Secure (the client is on plain HTTP).
        assertFalse(sessionCookie(s, get(s, "/whoami", "Cookie", loggedIn)).contains("Secure"));
        assertTrue(sessionCookie(s, get(s, "/whoami", "Cookie", loggedIn, "X-Forwarded-Proto", "https"))
                .contains("; Secure"));
    }

    @Test
    void forwardedProtoFromUntrustedPeerIsIgnored() throws Exception {
        // No trusted proxies: SiggiHTTP must not believe loopback either.
        Server s = start();
        String c = sessionCookie(s, get(s, "/form", "X-Forwarded-Proto", "https"));
        assertFalse(c.contains("Secure"), c);
    }

    // ---- anonymous visitors ------------------------------------------------------------

    @Test
    void anonymousPageViewsSetNoCookie() throws Exception {
        Server s = start();
        for (String path : List.of("/", "/api/status", "/whoami")) {
            HttpResponse<String> r = get(s, path);
            assertEquals(200, r.statusCode(), path);
            assertEquals(List.of(), setCookies(r), path);
            // An unknown or made-up session id doesn't create a session either.
            r = get(s, path, "Cookie", s.sessions().cookieName() + "=" + "ab".repeat(32));
            assertEquals(List.of(), setCookies(r), path);
            r = get(s, path, "Cookie", s.sessions().cookieName() + "=not-hex");
            assertEquals(List.of(), setCookies(r), path);
        }
        assertEquals("anonymous", get(s, "/whoami").body());
    }

    // ---- login, logout, expiry -------------------------------------------------------------

    @Test
    void idChangesOnLogin() throws Exception {
        Server s = start();
        HttpResponse<String> form = get(s, "/form");
        String before = cookieHeader(s, form);
        HttpResponse<String> r = post(s, "/login", "user=alice&csrf=" + enc(form.body()), "Cookie", before);
        assertEquals(200, r.statusCode());
        String after = cookieHeader(s, r);
        assertNotEquals(before, after);
        assertEquals("alice", get(s, "/whoami", "Cookie", after).body());
        // The pre-login session is gone, so a fixated id is useless.
        assertEquals("anonymous", get(s, "/whoami", "Cookie", before).body());
        // The CSRF token changes with the session.
        assertNotEquals(form.body(), csrf(s, after));
    }

    @Test
    void readsRefreshTheCookie() throws Exception {
        Server s = start();
        String cookie = login(s, "alice");
        HttpResponse<String> r = get(s, "/whoami", "Cookie", cookie);
        assertEquals("alice", r.body());
        assertEquals(cookie + "; Path=/; HttpOnly; SameSite=Lax; Max-Age=604800", sessionCookie(s, r));
    }

    @Test
    void slidingExpiry() throws Exception {
        Server s = start(Duration.ofMillis(1500));
        String cookie = login(s, "alice");
        // Used every 500 ms for longer than the lifetime: still logged in.
        for (int i = 0; i < 4; i++) {
            Thread.sleep(500);
            assertEquals("alice", get(s, "/whoami", "Cookie", cookie).body(), "use " + i);
        }
        // Idle for longer than the lifetime: expired, and no new cookie is set.
        Thread.sleep(2000);
        HttpResponse<String> r = get(s, "/whoami", "Cookie", cookie);
        assertEquals("anonymous", r.body());
        assertEquals(List.of(), setCookies(r));
    }

    @Test
    void sessionsSurviveRestart() throws Exception {
        Server s = start();
        String cookie = login(s, "alice");
        s.web().close();
        Server restarted = start();
        assertEquals("alice", get(restarted, "/whoami", "Cookie", cookie).body());
    }

    @Test
    void logoutClearsTheCookie() throws Exception {
        Server s = start();
        String cookie = login(s, "alice");
        HttpResponse<String> r = post(s, "/logout", "csrf=" + enc(csrf(s, cookie)), "Cookie", cookie);
        assertEquals(200, r.statusCode());
        String cleared = sessionCookie(s, r);
        assertTrue(cleared.startsWith(s.sessions().cookieName() + "=; Path=/; HttpOnly; SameSite=Lax"), cleared);
        assertTrue(cleared.endsWith("; Max-Age=0"), cleared);
        assertEquals("anonymous", get(s, "/whoami", "Cookie", cookie).body());
    }

    // ---- CSRF ----------------------------------------------------------------------------

    @Test
    void csrfRejection() throws Exception {
        Server s = start();
        String cookie = login(s, "alice");
        String token = csrf(s, cookie);
        String other = csrf(s, login(s, "bob"));

        assertEquals(403, post(s, "/action", "", "Cookie", cookie).statusCode());
        assertEquals(403, post(s, "/action", "csrf=", "Cookie", cookie).statusCode());
        assertEquals(403, post(s, "/action", "csrf=" + "0".repeat(64), "Cookie", cookie).statusCode());
        assertEquals(403, post(s, "/action", "csrf=" + other, "Cookie", cookie).statusCode());
        assertEquals(403, post(s, "/action", "csrf=" + token).statusCode()); // no session
        HttpResponse<String> rejected = post(s, "/action", "csrf=bad", "Cookie", cookie);
        assertEquals(403, rejected.statusCode());
        assertTrue(rejected.body().contains("CSRF"));

        assertEquals(200, post(s, "/action", "csrf=" + token, "Cookie", cookie).statusCode());
        assertEquals(200, post(s, "/action", "{}", "Cookie", cookie, SessionManager.CSRF_HEADER, token).statusCode());
        assertEquals(403, post(s, "/action", "{}", "Cookie", cookie, SessionManager.CSRF_HEADER, other).statusCode());

        // Logout and login need the token too.
        assertEquals(403, post(s, "/logout", "", "Cookie", cookie).statusCode());
        assertEquals("alice", get(s, "/whoami", "Cookie", cookie).body());
        assertEquals(403, post(s, "/login", "user=mallory").statusCode());
    }

    @Test
    void tokenIsStableWithinASession() throws Exception {
        Server s = start();
        String cookie = login(s, "alice");
        assertEquals(csrf(s, cookie), csrf(s, cookie));
        assertTrue(csrf(s, cookie).matches("[0-9a-f]{64}"));
    }

    // ---- config ----------------------------------------------------------------------------

    @Test
    void sessionDaysSetting() {
        assertEquals(7, SessionManager.sessionDays(null));
        assertEquals(7, SessionManager.sessionDays(new JsonObject()));
        assertEquals(30, SessionManager.sessionDays(JsonParser.parseString("{\"sessionDays\": 30}").getAsJsonObject()));
        assertEquals(1, SessionManager.sessionDays(JsonParser.parseString("{\"sessionDays\": 1}").getAsJsonObject()));
        for (String bad : List.of("0", "366", "1.5", "\"7\"", "true", "[]")) {
            JsonObject auth = JsonParser.parseString("{\"sessionDays\": " + bad + "}").getAsJsonObject();
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> SessionManager.sessionDays(auth), bad);
            assertTrue(e.getMessage().contains("auth.sessionDays"));
        }
    }
}
