package io.siggi.healthchecker.auth;

import com.google.gson.JsonObject;
import io.siggi.http.HTTPRequest;
import io.siggi.http.HTTPServer;
import io.siggi.http.HTTPServerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link OAuth2Login} against an in-process fake OAuth 2.0 provider (SiggiHTTP on an ephemeral loopback port). */
class OAuth2LoginTest {
    private static final String CLIENT_ID = "healthchecker";
    private static final String CLIENT_SECRET = "s3cret&:";
    private static final String REDIRECT = "https://status.example.com/auth/callback/gh";
    private static final Instant NOW = Instant.parse("2026-11-14T12:00:00Z");

    private FakeProvider provider;
    private MutableClock clock;
    private OAuth2Login login;
    private ProviderConfig config;

    @BeforeEach
    void setUp() throws Exception {
        provider = new FakeProvider();
        clock = new MutableClock(NOW);
        login = new OAuth2Login(clock);
        config = config(CLIENT_SECRET, true, "login");
    }

    @AfterEach
    void tearDown() throws IOException {
        provider.close();
    }

    private ProviderConfig config(String secret, boolean pkce, String nameField) {
        return new ProviderConfig("gh", ProviderConfig.Type.OAUTH2, "GitHub", CLIENT_ID, secret,
                List.of("read:user"), List.of(), null, null,
                provider.base + "/login/oauth/authorize?allow_signup=false", provider.base + "/token",
                provider.base + "/user", "id", nameField, pkce);
    }

    private OAuth2Login.Result signIn() throws Exception {
        OAuth2Login.Start start = login.start(config, REDIRECT);
        String code = provider.authorize(start.authorizationUrl());
        return login.finish(config, start.pending(), Map.of("code", code, "state", start.pending().state()));
    }

    // ---- success ----

    @Test
    void success() throws Exception {
        OAuth2Login.Start start = login.start(config, REDIRECT);
        URI url = start.authorizationUrl();
        assertTrue(url.toString().startsWith(provider.base + "/login/oauth/authorize?allow_signup=false&"));
        Map<String, String> query = query(url);
        assertEquals("code", query.get("response_type"));
        assertEquals(CLIENT_ID, query.get("client_id"));
        assertEquals(REDIRECT, query.get("redirect_uri"));
        assertEquals("read:user", query.get("scope"));
        assertEquals(start.pending().state(), query.get("state"));
        assertEquals("S256", query.get("code_challenge_method"));
        assertEquals(OidcLogin.codeChallenge(start.pending().codeVerifier()), query.get("code_challenge"));
        assertFalse(query.containsKey("nonce"));

        String code = provider.authorize(url);
        OAuth2Login.Result result = login.finish(config, start.pending(),
                Map.of("code", code, "state", start.pending().state()));
        // GitHub's numeric id becomes the identifier value.
        assertEquals("gh:123456", result.identifier());
        assertEquals(Optional.of("octocat"), result.displayName());
        assertEquals("octocat", result.userinfo().get("login").getAsString());
        assertEquals(1, provider.tokenRequests.get());
        assertEquals(1, provider.userinfoRequests.get());
    }

    @Test
    void withoutPkce() throws Exception {
        config = config(CLIENT_SECRET, false, "login");
        OAuth2Login.Start start = login.start(config, REDIRECT);
        assertNull(start.pending().codeVerifier());
        Map<String, String> query = query(start.authorizationUrl());
        assertFalse(query.containsKey("code_challenge"));
        assertFalse(query.containsKey("code_challenge_method"));
        String code = provider.authorize(start.authorizationUrl());
        assertEquals("gh:123456", login.finish(config, start.pending(),
                Map.of("code", code, "state", start.pending().state())).identifier());
        assertNull(provider.lastVerifier, "no code_verifier sent");
    }

    @Test
    void publicClientSendsOnlyClientId() throws Exception {
        config = config(null, true, "login");
        provider.expectSecret = null;
        assertEquals("gh:123456", signIn().identifier());
    }

    @Test
    void stringIdAndNoNameField() throws Exception {
        config = config(CLIENT_SECRET, true, null);
        provider.userinfo = u -> u.addProperty("id", "80351110224678912"); // Discord sends a string
        OAuth2Login.Result result = signIn();
        assertEquals("gh:80351110224678912", result.identifier());
        assertEquals(Optional.empty(), result.displayName());
    }

    @Test
    void missingNameIsNotAnError() throws Exception {
        provider.userinfo = u -> u.remove("login");
        OAuth2Login.Result result = signIn();
        assertEquals("gh:123456", result.identifier());
        assertEquals(Optional.empty(), result.displayName());
    }

    // ---- failures ----

    @Test
    void userinfoMissingIdFieldFailsCleanly() {
        provider.userinfo = u -> u.remove("id");
        assertEquals("userinfo has no usable \"id\"",
                assertThrows(OidcException.class, this::signIn).getMessage());
        // Unusable values fail the same way.
        provider.userinfo = u -> u.addProperty("id", 1.5);
        assertThrows(OidcException.class, this::signIn);
        provider.userinfo = u -> u.add("id", new JsonObject());
        assertThrows(OidcException.class, this::signIn);
        provider.userinfo = u -> u.addProperty("id", "");
        assertThrows(OidcException.class, this::signIn);
    }

    @Test
    void userinfoErrorAndNonObjectFail() {
        provider.userinfoStatus = "401 Unauthorized";
        assertEquals("userinfo endpoint returned HTTP 401",
                assertThrows(OidcException.class, this::signIn).getMessage());
        provider.userinfoStatus = "200 OK";
        provider.userinfoBody = "[1, 2]";
        assertEquals("userinfo endpoint response is not a JSON object",
                assertThrows(OidcException.class, this::signIn).getMessage());
    }

    @Test
    void tokenErrorWithHttp200Fails() {
        provider.tokenOverride = r -> r.addProperty("error", "bad_verification_code");
        assertEquals("token endpoint returned error \"bad_verification_code\"",
                assertThrows(OidcException.class, this::signIn).getMessage());
        assertEquals(0, provider.userinfoRequests.get());
    }

    @Test
    void tokenResponseChecked() {
        provider.tokenOverride = r -> r.remove("access_token");
        assertEquals("token response has no access_token",
                assertThrows(OidcException.class, this::signIn).getMessage());
        provider.tokenOverride = r -> r.addProperty("token_type", "mac");
        assertThrows(OidcException.class, this::signIn);
        provider.tokenOverride = r -> r.addProperty("access_token", "abc\r\nX-Evil: 1");
        assertEquals("access_token is malformed", assertThrows(OidcException.class, this::signIn).getMessage());
        assertEquals(0, provider.userinfoRequests.get());
    }

    @Test
    void wrongSecretOrVerifierRejectedByProvider() throws Exception {
        provider.expectSecret = "other";
        assertEquals("token endpoint returned HTTP 400", assertThrows(OidcException.class, this::signIn).getMessage());

        provider.expectSecret = CLIENT_SECRET;
        OAuth2Login.Start start = login.start(config, REDIRECT);
        String code = provider.authorize(start.authorizationUrl());
        OAuth2Login.Pending forged = new OAuth2Login.Pending(config.id(), start.pending().state(), "wrong-verifier",
                REDIRECT, start.pending().started());
        assertThrows(OidcException.class, () -> login.finish(config, forged,
                Map.of("code", code, "state", start.pending().state())));
    }

    @Test
    void stateMismatchRejectedBeforeTheCodeIsUsed() throws Exception {
        OAuth2Login.Start start = login.start(config, REDIRECT);
        String code = provider.authorize(start.authorizationUrl());
        assertEquals("state mismatch", assertThrows(OidcException.class,
                () -> login.finish(config, start.pending(), Map.of("code", code, "state", "forged"))).getMessage());
        assertThrows(OidcException.class, () -> login.finish(config, start.pending(), Map.of("code", code)));
        assertThrows(OidcException.class, () -> login.finish(config, null,
                Map.of("code", code, "state", start.pending().state())));
        assertThrows(OidcException.class, () -> login.finish(config, start.pending(),
                Map.of("state", start.pending().state(), "error", "access_denied")));
        clock.advance(OAuth2Login.PENDING_LIFETIME.plusSeconds(1));
        assertEquals("the sign-in took too long", assertThrows(OidcException.class,
                () -> login.finish(config, start.pending(), Map.of("code", code, "state", start.pending().state())))
                .getMessage());
        assertEquals(0, provider.tokenRequests.get());
    }

    @Test
    void oidcProviderRefused() {
        ProviderConfig oidc = new ProviderConfig("g", ProviderConfig.Type.OIDC, "G", CLIENT_ID, null,
                List.of("openid"), List.of(), "https://issuer.example", ProviderConfig.SUB,
                null, null, null, null, null, true);
        assertThrows(IllegalArgumentException.class, () -> login.start(oidc, REDIRECT));
    }

    // ---- helpers ----

    private static Map<String, String> query(URI uri) {
        Map<String, String> out = new HashMap<>();
        for (String pair : uri.getRawQuery().split("&")) {
            int eq = pair.indexOf('=');
            out.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return out;
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneOffset getZone() {
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

    /** A minimal GitHub-like OAuth 2.0 provider: a token endpoint (client_secret_post, PKCE) and a userinfo API. */
    private static final class FakeProvider implements AutoCloseable {
        final String base;
        final AtomicInteger tokenRequests = new AtomicInteger();
        final AtomicInteger userinfoRequests = new AtomicInteger();
        private final ServerSocket socket;
        private final Map<String, Grant> grants = new ConcurrentHashMap<>();
        private final Map<String, Boolean> tokens = new ConcurrentHashMap<>();

        volatile String expectSecret = CLIENT_SECRET;
        volatile String lastVerifier;
        volatile Consumer<JsonObject> tokenOverride = r -> {
        };
        volatile Consumer<JsonObject> userinfo = u -> {
        };
        volatile String userinfoStatus = "200 OK";
        volatile String userinfoBody;

        private record Grant(String clientId, String redirectUri, String challenge) {
        }

        FakeProvider() throws Exception {
            socket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            base = "http://127.0.0.1:" + socket.getLocalPort();
            HTTPServer server = new HTTPServerBuilder().setExecutor(Executors.newVirtualThreadPerTaskExecutor())
                    .build();
            server.responderRegistry.register("/", this::handle);
            Thread.ofVirtual().start(() -> {
                while (!socket.isClosed()) {
                    try {
                        Socket s = socket.accept();
                        server.handle(s);
                    } catch (IOException e) {
                        return;
                    }
                }
            });
        }

        /** What the browser does at the authorization endpoint: the provider remembers the request, returns a code. */
        String authorize(URI url) {
            Map<String, String> q = query(url);
            assertEquals("false", q.get("allow_signup"), "the configured query is kept");
            String code = "code-" + grants.size() + "-" + System.nanoTime();
            grants.put(code, new Grant(q.get("client_id"), q.get("redirect_uri"), q.get("code_challenge")));
            return code;
        }

        private void handle(HTTPRequest request) throws Exception {
            switch (request.path()) {
                case "/token" -> token(request);
                case "/user" -> user(request);
                default -> {
                }
            }
        }

        private void token(HTTPRequest request) throws Exception {
            tokenRequests.incrementAndGet();
            Map<String, String> post = request.post();
            lastVerifier = post.get("code_verifier");
            Grant grant = grants.remove(post.getOrDefault("code", ""));
            String error = null;
            if (grant == null) error = "invalid_grant";
            else if (!"authorization_code".equals(post.get("grant_type"))) error = "unsupported_grant_type";
            else if (!grant.redirectUri().equals(post.get("redirect_uri"))) error = "invalid_grant";
            else if (grant.challenge() != null && (lastVerifier == null
                    || !OidcLogin.codeChallenge(lastVerifier).equals(grant.challenge()))) {
                error = "invalid_grant";
            } else if (grant.challenge() == null && lastVerifier != null) error = "invalid_request";
            else if (!grant.clientId().equals(post.get("client_id"))
                    || request.getHeader("Authorization") != null
                    || !java.util.Objects.equals(expectSecret, post.get("client_secret"))) {
                error = "invalid_client";
            }
            if (error != null) {
                JsonObject body = new JsonObject();
                body.addProperty("error", error);
                json(request, "400 Bad Request", body.toString());
                return;
            }
            String accessToken = "gho_" + System.nanoTime();
            tokens.put(accessToken, true);
            JsonObject response = new JsonObject();
            response.addProperty("access_token", accessToken);
            response.addProperty("token_type", "bearer");
            response.addProperty("scope", "read:user");
            tokenOverride.accept(response);
            json(request, "200 OK", response.toString());
        }

        private void user(HTTPRequest request) throws Exception {
            userinfoRequests.incrementAndGet();
            String auth = request.getHeader("Authorization");
            if (auth == null || !auth.startsWith("Bearer ") || tokens.remove(auth.substring(7)) == null) {
                json(request, "401 Unauthorized", "{\"message\":\"Bad credentials\"}");
                return;
            }
            if (!"200 OK".equals(userinfoStatus)) {
                json(request, userinfoStatus, "{\"message\":\"nope\"}");
                return;
            }
            if (userinfoBody != null) {
                json(request, "200 OK", userinfoBody);
                return;
            }
            JsonObject u = new JsonObject();
            u.addProperty("login", "octocat");
            u.addProperty("id", 123456);
            u.addProperty("name", "The Octocat");
            userinfo.accept(u);
            json(request, "200 OK", u.toString());
        }

        private static void json(HTTPRequest request, String status, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            request.response.setHeader(status);
            request.response.setHeader("Content-Type", "application/json");
            request.response.setHeader("Content-Length", Integer.toString(bytes.length));
            request.response.write(bytes);
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
