package io.siggi.healthchecker.web.page;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.siggi.healthchecker.audit.AuditLog;
import io.siggi.healthchecker.auth.AuthProviders;
import io.siggi.healthchecker.auth.ExternalLogin;
import io.siggi.healthchecker.auth.LocalLogin;
import io.siggi.healthchecker.auth.LoginRateLimiter;
import io.siggi.healthchecker.auth.OidcException;
import io.siggi.healthchecker.auth.Permissions;
import io.siggi.healthchecker.auth.ProviderConfig;
import io.siggi.healthchecker.auth.SessionManager;
import io.siggi.healthchecker.auth.UserDirectory;
import io.siggi.healthchecker.config.ServerConfig;
import io.siggi.healthchecker.config.UnknownHostsPolicy;
import io.siggi.healthchecker.web.WebServer;
import io.siggi.healthchecker.web.site.SiteResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code /auth/start/…} and {@code /auth/callback/…} with a fake {@link ExternalLogin} (the real flows are tested
 * against fake providers in {@code OidcLoginTest} and {@code OAuth2LoginTest}).
 */
class ProviderLoginPageTest {
    private static final String BASE = "https://status.example.com";
    private static final String AUTH = """
            {
              "users": [
                {"id": "siggi", "displayName": "Siggi", "role": "admin", "identities": ["google:111", "mail:boss@example.com"]},
                {"id": "old", "displayName": "Old Intern", "role": "viewer", "identities": ["google:222"], "disabled": true}
              ],
              "rules": [{"provider": "google", "emailDomain": "example.com", "role": "viewer"}],
              "providers": [
                {"id": "google", "type": "oidc", "label": "Google", "issuer": "https://accounts.example",
                 "clientId": "c"},
                {"id": "mail", "type": "oidc", "label": "Mail", "issuer": "https://mail.example",
                 "clientId": "c", "identifyBy": "email"}
              ]
            }""";

    @TempDir
    Path dataDir;

    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private WebServer web;
    private SessionManager sessions;
    private AuditLog audit;
    private FakeExternalLogin external;

    /** Hands out a fixed authorization URL and returns {@link #claims} for a callback with the right state. */
    private static final class FakeExternalLogin implements ExternalLogin {
        volatile JsonObject claims;
        volatile int starts;

        @Override
        public Started start(ProviderConfig provider, String redirectUri) {
            starts++;
            return new Started(URI.create("https://idp.example/authorize?state=s-" + provider.id()
                    + "&redirect_uri=" + redirectUri), "pending-" + provider.id());
        }

        @Override
        public JsonObject finish(ProviderConfig provider, String pending, Map<String, String> callback)
                throws OidcException {
            if (!("pending-" + provider.id()).equals(pending)) throw new OidcException("no sign-in in progress");
            if (!("s-" + provider.id()).equals(callback.get("state"))) throw new OidcException("state mismatch");
            return claims;
        }
    }

    @AfterEach
    void stop() {
        if (web != null) web.close();
    }

    private void start(boolean localEnabled) throws Exception {
        web = new WebServer(new ServerConfig(0, "127.0.0.1", BASE, List.of("127.0.0.1"), UnknownHostsPolicy.DEFAULT));
        sessions = new SessionManager(dataDir.resolve("sessions"), Duration.ofDays(7), web.trustedProxies());
        SiteResolver sites = new SiteResolver(List.of(), "Status", BASE, UnknownHostsPolicy.DEFAULT,
                web.trustedProxies());
        JsonObject auth = JsonParser.parseString(AUTH).getAsJsonObject();
        UserDirectory users = UserDirectory.parse(auth);
        AuthProviders providers = AuthProviders.parse(auth,
                new ServerConfig(0, "127.0.0.1", BASE, List.of(), UnknownHostsPolicy.DEFAULT), List.of());
        audit = new AuditLog(dataDir.resolve("audit.jsonl"), Clock.systemUTC());
        external = new FakeExternalLogin();
        Permissions permissions = new Permissions(sessions, users);
        new LoginPage(sites, sessions, permissions,
                new LocalLogin(localEnabled, u -> java.util.Optional.empty(),
                        new LoginRateLimiter(3, Duration.ofMinutes(15), Clock.systemUTC())),
                web.trustedProxies()).providers(providers).register(web);
        new ProviderLoginPage(sites, sessions, users, providers, external, web.trustedProxies(), Clock.systemUTC())
                .audit(audit).register(web);
        web.start();
    }

    private HttpResponse<String> get(String path, String cookie) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + web.port() + path))
                .timeout(Duration.ofSeconds(10));
        if (cookie != null) b.header("Cookie", cookie);
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** The session cookie a response set ({@code name=value}), or null. */
    private String cookie(HttpResponse<?> r) {
        for (String c : r.headers().allValues("Set-Cookie")) {
            if (c.startsWith(sessions.cookieName() + "=")) return c.substring(0, c.indexOf(';'));
        }
        return null;
    }

    private boolean clearsCookie(HttpResponse<?> r) {
        return r.headers().allValues("Set-Cookie").stream()
                .anyMatch(c -> c.startsWith(sessions.cookieName() + "=") && c.contains("Max-Age=0"));
    }

    /** Starts a sign-in with {@code provider}, then calls back with {@code claims}; returns the callback response. */
    private HttpResponse<String> signIn(String provider, String claims) throws Exception {
        external.claims = JsonParser.parseString(claims).getAsJsonObject();
        HttpResponse<String> started = get("/auth/start/" + provider + "?next=%2Fservice%2Fweb", null);
        assertEquals(303, started.statusCode());
        assertTrue(started.headers().firstValue("Location").orElseThrow()
                .startsWith("https://idp.example/authorize?state=s-" + provider), started.headers().toString());
        assertTrue(started.headers().firstValue("Location").orElseThrow()
                .endsWith("redirect_uri=" + BASE + "/auth/callback/" + provider));
        String cookie = cookie(started);
        assertNotNull(cookie, "the pending sign-in lives in a session");
        return get("/auth/callback/" + provider + "?code=c&state=s-" + provider, cookie);
    }

    /** Who {@code /login} says is signed in with this cookie, or null. */
    private String signedInAs(String cookie) throws Exception {
        String body = get("/login", cookie).body();
        int i = body.indexOf("You're signed in as <strong>");
        if (i < 0) return null;
        int start = i + "You're signed in as <strong>".length();
        return body.substring(start, body.indexOf("</p>", start));
    }

    // ---- login page ----

    @Test
    void loginPageShowsProviderButtons() throws Exception {
        start(true);
        String body = get("/login?next=%2Fx", null).body();
        assertTrue(body.contains("type=\"password\""));
        assertTrue(body.contains("href=\"" + BASE + "/auth/start/google?next=%2Fx\">Sign in with Google</a>"), body);
        assertTrue(body.contains(">Sign in with Mail</a>"));
    }

    @Test
    void localFormHiddenWhenDisabled() throws Exception {
        start(false);
        HttpResponse<String> r = get("/login", null);
        assertFalse(r.body().contains("type=\"password\""));
        assertFalse(r.body().contains("<form"));
        assertTrue(r.body().contains(">Sign in with Google</a>"));
        assertNull(cookie(r), "provider buttons need no session");
    }

    @Test
    void unknownProviderIs404() throws Exception {
        start(true);
        assertEquals(404, get("/auth/start/nope", null).statusCode());
        assertEquals(404, get("/auth/callback/nope?code=c&state=s", null).statusCode());
        assertEquals(0, external.starts);
    }

    // ---- outcomes ----

    @Test
    void unknownIdentityShowsExactIdentifierAndCreatesNoSession() throws Exception {
        start(true);
        HttpResponse<String> r = signIn("google", """
                {"sub": "x<y>&\\"z'1", "name": "Jane Doe", "email": "jane@elsewhere.example", "email_verified": true}""");
        assertEquals(403, r.statusCode());
        assertTrue(r.body().contains("doesn't have access yet"), r.body());
        assertTrue(r.body().contains("<code id=\"signin-identifier\">google:x&lt;y&gt;&amp;&quot;z&#39;1</code>")
                || r.body().contains("<code id=\"signin-identifier\">google:x&lt;y&gt;&amp;&quot;z&#x27;1</code>"),
                r.body());
        assertTrue(r.body().contains("data-copy=\"signin-identifier\""));
        assertTrue(r.body().contains("<strong>Google</strong> as <em>Jane Doe</em> (jane@elsewhere.example)"));
        assertTrue(r.body().contains("href=\"" + BASE + "/auth/start/google?next=%2Fservice%2Fweb&amp;switch=1\""),
                "different-account link");
        assertTrue(clearsCookie(r), "the pre-login session is deleted");
        // Nothing about users, roles or rules.
        assertFalse(r.body().contains("siggi") || r.body().contains("viewer") || r.body().contains("rule"));
    }

    @Test
    void emailIdentifierIgnoredWhenNotVerified() throws Exception {
        start(true);
        // boss@example.com belongs to siggi via "mail", which identifies by email.
        HttpResponse<String> r = signIn("mail", """
                {"sub": "1", "email": "boss@example.com", "email_verified": false}""");
        assertEquals(400, r.statusCode());
        assertFalse(r.body().contains("signin-identifier"));
        assertNull(signedInAs(cookie(r)));
        HttpResponse<String> ok = signIn("mail", """
                {"sub": "1", "email": "boss@example.com", "email_verified": true}""");
        assertEquals(303, ok.statusCode());
        assertEquals("Siggi</strong> (admin).", signedInAs(cookie(ok)));
    }

    @Test
    void ruleGrantsRole() throws Exception {
        start(true);
        HttpResponse<String> r = signIn("google", """
                {"sub": "333", "name": "Jane Doe", "email": "jane@example.com", "email_verified": true}""");
        assertEquals(303, r.statusCode());
        assertEquals(BASE + "/service/web", r.headers().firstValue("Location").orElseThrow());
        String cookie = cookie(r);
        assertNotNull(cookie);
        assertEquals("Jane Doe</strong> (viewer).", signedInAs(cookie));
    }

    @Test
    void explicitUserBeatsRule() throws Exception {
        start(true);
        HttpResponse<String> r = signIn("google", """
                {"sub": "111", "name": "Someone", "email": "siggi@example.com", "email_verified": true}""");
        assertEquals(303, r.statusCode());
        assertEquals("Siggi</strong> (admin).", signedInAs(cookie(r)));
    }

    @Test
    void disabledUserGetsTheDisabledPage() throws Exception {
        start(true);
        HttpResponse<String> r = signIn("google", """
                {"sub": "222", "email": "old@example.com", "email_verified": true}""");
        assertEquals(403, r.statusCode());
        assertTrue(r.body().contains("access for this account has been disabled"), r.body());
        assertTrue(r.body().contains("<code id=\"signin-identifier\">google:222</code>"));
        assertTrue(clearsCookie(r));
        assertNull(signedInAs(cookie(r)));
    }

    @Test
    void callbackWithoutStartFails() throws Exception {
        start(true);
        external.claims = JsonParser.parseString("{\"sub\": \"111\"}").getAsJsonObject();
        HttpResponse<String> r = get("/auth/callback/google?code=c&state=s-google", null);
        assertEquals(400, r.statusCode());
        assertNull(cookie(r));
        // A started sign-in is single use.
        external.claims = JsonParser.parseString("{\"sub\": \"111\"}").getAsJsonObject();
        HttpResponse<String> started = get("/auth/start/google", null);
        String cookie = cookie(started);
        assertEquals(303, get("/auth/callback/google?code=c&state=s-google", cookie).statusCode());
        assertEquals(400, get("/auth/callback/google?code=c&state=s-google", cookie).statusCode());
    }

    @Test
    void rejectedAttemptAppearsInTheAuditLog() throws Exception {
        start(true);
        signIn("google", """
                {"sub": "999", "name": "Jane Doe", "email": "jane@elsewhere.example", "email_verified": true}""");
        signIn("google", "{\"sub\": \"222\"}");
        List<AuditLog.Entry> entries = audit.page(0, 50).entries();
        assertEquals(2, entries.size());
        AuditLog.Entry disabled = entries.get(0); // newest first
        AuditLog.Entry unknown = entries.get(1);
        assertEquals("login.rejected", unknown.action());
        assertEquals("google:999", unknown.id());
        assertNull(unknown.user());
        JsonObject after = unknown.after().getAsJsonObject();
        assertEquals("google", after.get("provider").getAsString());
        assertEquals("google:999", after.get("identifier").getAsString());
        assertEquals("Jane Doe", after.get("displayName").getAsString());
        assertEquals("127.0.0.1", after.get("ip").getAsString());
        assertEquals("unknown", after.get("reason").getAsString());
        assertEquals("disabled", disabled.after().getAsJsonObject().get("reason").getAsString());
    }

    @Test
    void rejectedAttemptLoggingIsRateLimitedPerIdentifier() throws Exception {
        start(true);
        for (int i = 0; i < ProviderLoginPage.REJECTED_PER_HOUR + 3; i++) {
            assertEquals(403, signIn("google", "{\"sub\": \"999\"}").statusCode(), "the page is always shown");
        }
        signIn("google", "{\"sub\": \"998\"}");
        List<AuditLog.Entry> entries = audit.page(0, 50).entries();
        assertEquals(ProviderLoginPage.REJECTED_PER_HOUR + 1, entries.size());
        assertEquals("google:998", entries.get(0).id());
    }
}
