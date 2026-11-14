package io.siggi.healthchecker.web.page;

import io.siggi.healthchecker.auth.LocalLogin;
import io.siggi.healthchecker.auth.LoginRateLimiter;
import io.siggi.healthchecker.auth.PasswordHasher;
import io.siggi.healthchecker.auth.Permissions;
import io.siggi.healthchecker.auth.Role;
import io.siggi.healthchecker.auth.User;
import io.siggi.healthchecker.auth.UserDirectory;
import io.siggi.healthchecker.auth.SessionManager;
import io.siggi.healthchecker.config.ServerConfig;
import io.siggi.healthchecker.config.UnknownHostsPolicy;
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
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoginPageTest {
    private static final String ALICE_HASH = new PasswordHasher().hash("correct horse".toCharArray());
    private static final Pattern CSRF = Pattern.compile("name=\"csrf\" value=\"([0-9a-f]{64})\"");

    @TempDir
    Path dataDir;

    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private WebServer web;
    private SessionManager sessions;

    @AfterEach
    void stop() {
        if (web != null) web.close();
    }

    /** A server trusting loopback as a proxy, so tests pick the client IP with {@code X-Forwarded-For}. */
    private void start(boolean localEnabled, String publicBaseUrl) throws Exception {
        web = new WebServer(new ServerConfig(0, "127.0.0.1", publicBaseUrl, List.of("127.0.0.1"),
                UnknownHostsPolicy.DEFAULT));
        sessions = new SessionManager(dataDir.resolve("sessions"), Duration.ofDays(7), web.trustedProxies());
        SiteResolver sites = new SiteResolver(List.of(), "Status", publicBaseUrl, UnknownHostsPolicy.DEFAULT,
                web.trustedProxies());
        LoginRateLimiter limiter = new LoginRateLimiter(3, Duration.ofMinutes(15), Clock.systemUTC());
        LocalLogin local = new LocalLogin(localEnabled,
                username -> Optional.ofNullable(Map.of("alice", ALICE_HASH, "bob", ALICE_HASH).get(username)),
                limiter);
        UserDirectory users = UserDirectory.of(List.of(
                new User("alice", "alice", Role.ADMIN, ALICE_HASH, List.of(), false),
                new User("bob", "bob", Role.VIEWER, ALICE_HASH, List.of(), false)));
        new LoginPage(sites, sessions, new Permissions(sessions, users::user), local, web.trustedProxies())
                .register(web);
        web.start();
    }

    private void start() throws Exception {
        start(true, null);
    }

    private String url(String path) {
        return "http://127.0.0.1:" + web.port() + path;
    }

    private HttpResponse<String> get(String path, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url(path))).timeout(Duration.ofSeconds(10));
        if (headers.length > 0) b.headers(headers);
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String form, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url(path))).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form));
        if (headers.length > 0) b.headers(headers);
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }

    /** The {@code Cookie} header value for the session cookie a response set, or null. */
    private String cookie(HttpResponse<?> r) {
        for (String c : r.headers().allValues("Set-Cookie")) {
            if (c.startsWith(sessions.cookieName() + "=")) return c.substring(0, c.indexOf(';'));
        }
        return null;
    }

    private static String csrf(HttpResponse<String> page) {
        Matcher m = CSRF.matcher(page.body());
        assertTrue(m.find(), page.body());
        return m.group(1);
    }

    /** A pre-login session: cookie and CSRF token from the login form. */
    private record Form(String cookie, String csrf) {
    }

    private Form form() throws Exception {
        HttpResponse<String> r = get("/login");
        assertEquals(200, r.statusCode());
        return new Form(cookie(r), csrf(r));
    }

    private HttpResponse<String> attempt(Form f, String user, String password, String ip) throws Exception {
        return post("/login", "csrf=" + f.csrf() + "&username=" + enc(user) + "&password=" + enc(password),
                "Cookie", f.cookie(), "X-Forwarded-For", ip);
    }

    private String signedInAs(String cookie) throws Exception {
        String body = get("/login", "Cookie", cookie).body();
        Matcher m = Pattern.compile("signed in as <strong>([^<]*)</strong>").matcher(body);
        return m.find() ? m.group(1) : null;
    }

    // ---- form ----

    @Test
    void formHasCsrfTokenAndFields() throws Exception {
        start();
        HttpResponse<String> r = get("/login?next=/service/web");
        assertEquals(200, r.statusCode());
        assertEquals("no-store", r.headers().firstValue("Cache-Control").orElse(null));
        assertEquals("DENY", r.headers().firstValue("X-Frame-Options").orElse(null));
        assertTrue(r.body().contains("name=\"username\""));
        assertTrue(r.body().contains("type=\"password\""));
        assertTrue(r.body().contains("name=\"next\" value=\"/service/web\""), r.body());
        assertTrue(cookie(r) != null, "the form starts a pre-login session");
        assertEquals(405, client.send(HttpRequest.newBuilder(URI.create(url("/login")))
                .PUT(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(404, get("/login/x").statusCode());
    }

    // ---- sign-in ----

    @Test
    void success() throws Exception {
        start();
        Form f = form();
        HttpResponse<String> r = post("/login", "csrf=" + f.csrf() + "&username=alice&password="
                + enc("correct horse") + "&next=" + enc("/service/web?view=calendar"), "Cookie", f.cookie());
        assertEquals(303, r.statusCode(), r.body());
        assertEquals("/service/web?view=calendar", r.headers().firstValue("Location").orElse(null));
        String session = cookie(r);
        assertNotEquals(f.cookie(), session, "new session id at login");
        assertEquals("alice", signedInAs(session));
        assertEquals(null, signedInAs(f.cookie()));
    }

    @Test
    void successRedirectUsesTheBaseUrlAndOnlyLocalPaths() throws Exception {
        start(true, "https://status.example.com");
        Form f = form();
        HttpResponse<String> r = post("/login", "csrf=" + f.csrf() + "&username=alice&password="
                + enc("correct horse") + "&next=" + enc("//evil.example/x"), "Cookie", f.cookie());
        assertEquals(303, r.statusCode());
        assertEquals("https://status.example.com/", r.headers().firstValue("Location").orElse(null));
    }

    @Test
    void wrongPassword() throws Exception {
        start();
        Form f = form();
        HttpResponse<String> r = attempt(f, "alice", "wrong", "10.0.0.1");
        assertEquals(401, r.statusCode());
        assertTrue(r.body().contains("Wrong username or password."), r.body());
        assertTrue(r.body().contains("value=\"alice\""), "username kept in the form");
        assertFalse(r.body().contains("wrong"), "password not echoed");
        assertEquals(null, signedInAs(f.cookie()));
        // The form can be submitted again with the same token.
        assertEquals(303, attempt(f, "alice", "correct horse", "10.0.0.1").statusCode());
    }

    @Test
    void unknownUser() throws Exception {
        start();
        Form f = form();
        HttpResponse<String> unknown = attempt(f, "<mallory>", "correct horse", "10.0.0.1");
        HttpResponse<String> wrong = attempt(f, "alice", "nope", "10.0.0.2");
        assertEquals(401, unknown.statusCode());
        // Same message as a wrong password, and the username is escaped.
        assertTrue(unknown.body().contains("Wrong username or password."));
        assertTrue(unknown.body().contains("value=\"&lt;mallory&gt;\""), unknown.body());
        assertEquals(wrong.body().replace("value=\"alice\"", "value=\"&lt;mallory&gt;\""), unknown.body());
        assertEquals(null, signedInAs(f.cookie()));
    }

    @Test
    void loginRequiresCsrf() throws Exception {
        start();
        Form f = form();
        assertEquals(403, post("/login", "username=alice&password=" + enc("correct horse"),
                "Cookie", f.cookie()).statusCode());
        assertEquals(403, post("/login", "csrf=" + f.csrf() + "&username=alice&password=" + enc("correct horse"))
                .statusCode());
    }

    // ---- rate limiting ----

    @Test
    void rateLimitPerIp() throws Exception {
        start();
        Form f = form();
        for (String user : List.of("u1", "u2", "u3")) {
            assertEquals(401, attempt(f, user, "x", "10.0.0.1").statusCode());
        }
        HttpResponse<String> limited = attempt(f, "alice", "correct horse", "10.0.0.1");
        assertEquals(429, limited.statusCode());
        long retryAfter = Long.parseLong(limited.headers().firstValue("Retry-After").orElseThrow());
        assertTrue(retryAfter > 800 && retryAfter <= 900, "Retry-After " + retryAfter);
        assertTrue(limited.body().contains("Too many failed sign-in attempts"), limited.body());
        assertEquals(null, signedInAs(f.cookie()));
        // Another client IP isn't affected.
        assertEquals(303, attempt(f, "alice", "correct horse", "10.0.0.2").statusCode());
    }

    @Test
    void rateLimitPerUsername() throws Exception {
        start();
        Form f = form();
        for (String ip : List.of("10.0.0.1", "10.0.0.2", "10.0.0.3")) {
            assertEquals(401, attempt(f, "alice", "x", ip).statusCode());
        }
        assertEquals(429, attempt(f, "alice", "correct horse", "10.0.0.4").statusCode());
        assertEquals(429, attempt(f, "ALICE", "correct horse", "10.0.0.4").statusCode());
        // Other usernames from the same IP still work.
        assertEquals(303, attempt(f, "bob", "correct horse", "10.0.0.4").statusCode());
    }

    // ---- logout ----

    @Test
    void logoutRequiresCsrf() throws Exception {
        start();
        Form f = form();
        String session = cookie(attempt(f, "alice", "correct horse", "10.0.0.1"));
        String token = csrf(get("/login", "Cookie", session));
        assertNotEquals(f.csrf(), token, "new token after login");

        assertEquals(403, post("/logout", "", "Cookie", session).statusCode());
        assertEquals(403, post("/logout", "csrf=" + f.csrf(), "Cookie", session).statusCode());
        assertEquals(405, get("/logout", "Cookie", session).statusCode());
        assertEquals("alice", signedInAs(session));

        HttpResponse<String> r = post("/logout", "csrf=" + token, "Cookie", session);
        assertEquals(303, r.statusCode());
        assertEquals("/", r.headers().firstValue("Location").orElse(null));
        assertTrue(r.headers().allValues("Set-Cookie").stream().anyMatch(c -> c.endsWith("Max-Age=0")));
        assertEquals(null, signedInAs(session));
    }

    // ---- disabled ----

    @Test
    void disabledLocalLogin() throws Exception {
        start(false, null);
        HttpResponse<String> r = get("/login");
        assertEquals(200, r.statusCode());
        assertTrue(r.body().contains("Password sign-in is disabled."));
        assertFalse(r.body().contains("type=\"password\""));
        assertEquals(null, cookie(r), "no form, no session");
        assertEquals(404, post("/login", "username=alice&password=x").statusCode());
    }

    @Test
    void safeNext() {
        assertEquals("/", LoginPage.safeNext(null));
        assertEquals("/", LoginPage.safeNext(""));
        assertEquals("/", LoginPage.safeNext("https://evil.example/"));
        assertEquals("/", LoginPage.safeNext("//evil.example/"));
        assertEquals("/", LoginPage.safeNext("/\\evil.example/"));
        assertEquals("/", LoginPage.safeNext("/a b"));
        assertEquals("/", LoginPage.safeNext("/a\nLocation: x"));
        assertEquals("/", LoginPage.safeNext("/" + "a".repeat(2048)));
        assertEquals("/service/web?view=calendar&day=2026-11-14", LoginPage.safeNext("/service/web?view=calendar&day=2026-11-14"));
    }
}
