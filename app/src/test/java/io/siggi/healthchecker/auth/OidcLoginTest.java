package io.siggi.healthchecker.auth;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.siggi.http.HTTPRequest;
import io.siggi.http.HTTPServer;
import io.siggi.http.HTTPServerBuilder;
import io.siggi.simplejwt.alg.ES256;
import io.siggi.simplejwt.alg.JWTAlgorithm;
import io.siggi.simplejwt.alg.RS256;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link OidcLogin} against an in-process fake OIDC provider (SiggiHTTP on an ephemeral loopback port). */
class OidcLoginTest {
    private static final String CLIENT_ID = "healthchecker";
    private static final String CLIENT_SECRET = "s3cret&:";
    private static final String REDIRECT = "https://status.example.com/auth/callback/test";
    private static final Instant NOW = Instant.parse("2026-11-14T12:00:00Z");

    private FakeProvider provider;
    private MutableClock clock;
    private OidcLogin login;
    private ProviderConfig config;

    @BeforeEach
    void setUp() throws Exception {
        provider = new FakeProvider();
        clock = new MutableClock(NOW);
        login = new OidcLogin(clock);
        config = config(provider.issuer, CLIENT_SECRET);
    }

    @AfterEach
    void tearDown() throws IOException {
        provider.close();
    }

    private static ProviderConfig config(String issuer, String secret) {
        return new ProviderConfig("test", ProviderConfig.Type.OIDC, "Test", CLIENT_ID, secret,
                List.of("openid", "email", "profile"), List.of(), issuer, ProviderConfig.SUB,
                null, null, null, null, null, true);
    }

    /** Runs the whole flow: start, the "browser" at the provider, then the callback. */
    private JsonObject signIn() throws Exception {
        OidcLogin.Start start = login.start(config, REDIRECT);
        String code = provider.authorize(start.authorizationUrl());
        return login.finish(config, start.pending(), Map.of("code", code, "state", start.pending().state()));
    }

    // ---- success ----

    @Test
    void success() throws Exception {
        OidcLogin.Start start = login.start(config, REDIRECT);
        Map<String, String> query = query(start.authorizationUrl());
        assertTrue(start.authorizationUrl().toString().startsWith(provider.issuer + "/authorize?"));
        assertEquals("code", query.get("response_type"));
        assertEquals(CLIENT_ID, query.get("client_id"));
        assertEquals(REDIRECT, query.get("redirect_uri"));
        assertEquals("openid email profile", query.get("scope"));
        assertEquals("S256", query.get("code_challenge_method"));
        assertEquals(OidcLogin.codeChallenge(start.pending().codeVerifier()), query.get("code_challenge"));
        assertEquals(start.pending().state(), query.get("state"));
        assertEquals(start.pending().nonce(), query.get("nonce"));
        assertNotEquals(start.pending().state(), start.pending().nonce());

        String code = provider.authorize(start.authorizationUrl());
        JsonObject claims = login.finish(config, start.pending(),
                Map.of("code", code, "state", start.pending().state(), "iss", provider.issuer));
        assertEquals("user-1", claims.get("sub").getAsString());
        assertEquals("test:user-1", config.identify(claims).orElseThrow());
        // The fake checked PKCE, redirect_uri and client_secret_basic; one discovery, one JWKS fetch.
        assertEquals(1, provider.tokenRequests.get());
        assertEquals(1, provider.discoveryRequests.get());
        assertEquals(1, provider.jwksRequests.get());

        signIn();
        assertEquals(1, provider.discoveryRequests.get(), "discovery is cached");
        assertEquals(1, provider.jwksRequests.get(), "JWKS is cached");
    }

    @Test
    void publicClientSendsClientIdWithoutSecret() throws Exception {
        config = config(provider.issuer, null);
        provider.expectSecret = null;
        assertEquals("user-1", signIn().get("sub").getAsString());
    }

    @Test
    void clientSecretPostWhenBasicIsNotSupported() throws Exception {
        provider.authMethods = List.of("client_secret_post");
        assertEquals("user-1", signIn().get("sub").getAsString());
    }

    @Test
    void ecKeysWork() throws Exception {
        provider.useEcKey();
        assertEquals("user-1", signIn().get("sub").getAsString());
    }

    // ---- id_token rejections ----

    @Test
    void badSignatureRejected() throws Exception {
        provider.signWith = rsaKey(); // same kid, different key
        assertEquals("id_token signature is invalid", assertThrows(OidcException.class, this::signIn).getMessage());
    }

    @Test
    void tamperedPayloadRejected() throws Exception {
        provider.tamper = true;
        assertEquals("id_token signature is invalid", assertThrows(OidcException.class, this::signIn).getMessage());
    }

    @Test
    void wrongAudienceRejected() throws Exception {
        provider.claims = c -> c.addProperty("aud", "someone-else");
        assertEquals("id_token aud mismatch", assertThrows(OidcException.class, this::signIn).getMessage());
    }

    @Test
    void audienceArrayNeedsAzp() throws Exception {
        provider.claims = c -> {
            JsonArray aud = new JsonArray();
            aud.add(CLIENT_ID);
            aud.add("other");
            c.add("aud", aud);
        };
        assertThrows(OidcException.class, this::signIn);
        provider.claims = c -> {
            JsonArray aud = new JsonArray();
            aud.add(CLIENT_ID);
            aud.add("other");
            c.add("aud", aud);
            c.addProperty("azp", CLIENT_ID);
        };
        assertEquals("user-1", signIn().get("sub").getAsString());
    }

    @Test
    void wrongIssuerRejected() throws Exception {
        provider.claims = c -> c.addProperty("iss", provider.issuer + "/other");
        assertEquals("id_token iss mismatch", assertThrows(OidcException.class, this::signIn).getMessage());
    }

    @Test
    void discoveryIssuerMismatchRejected() {
        config = config(provider.issuer + "/", CLIENT_SECRET); // differs from what the provider says
        assertEquals("discovery issuer mismatch",
                assertThrows(OidcException.class, () -> login.start(config, REDIRECT)).getMessage());
    }

    @Test
    void expiredRejected() throws Exception {
        provider.claims = c -> {
            c.addProperty("iat", NOW.getEpochSecond() - 3600);
            c.addProperty("exp", NOW.getEpochSecond() - 61);
        };
        assertEquals("id_token has expired", assertThrows(OidcException.class, this::signIn).getMessage());
        // Within the clock skew it's still accepted.
        provider.claims = c -> c.addProperty("exp", NOW.getEpochSecond() - 30);
        assertEquals("user-1", signIn().get("sub").getAsString());
    }

    @Test
    void issuedInTheFutureRejected() throws Exception {
        provider.claims = c -> c.addProperty("iat", NOW.getEpochSecond() + 3600);
        assertThrows(OidcException.class, this::signIn);
        provider.claims = c -> c.remove("iat");
        assertEquals("id_token has no iat", assertThrows(OidcException.class, this::signIn).getMessage());
    }

    @Test
    void nonceMismatchRejected() throws Exception {
        provider.claims = c -> c.addProperty("nonce", "not-the-nonce");
        assertEquals("id_token nonce mismatch", assertThrows(OidcException.class, this::signIn).getMessage());
        provider.claims = c -> c.remove("nonce");
        assertEquals("id_token nonce mismatch", assertThrows(OidcException.class, this::signIn).getMessage());
    }

    @Test
    void stateMismatchRejectedBeforeTheCodeIsUsed() throws Exception {
        OidcLogin.Start start = login.start(config, REDIRECT);
        String code = provider.authorize(start.authorizationUrl());
        OidcException e = assertThrows(OidcException.class,
                () -> login.finish(config, start.pending(), Map.of("code", code, "state", "forged")));
        assertEquals("state mismatch", e.getMessage());
        assertThrows(OidcException.class, () -> login.finish(config, start.pending(), Map.of("code", code)));
        assertThrows(OidcException.class, () -> login.finish(config, null,
                Map.of("code", code, "state", start.pending().state())));
        assertEquals(0, provider.tokenRequests.get());
    }

    @Test
    void expiredPendingAndProviderErrorRejected() throws Exception {
        OidcLogin.Start start = login.start(config, REDIRECT);
        String code = provider.authorize(start.authorizationUrl());
        assertThrows(OidcException.class, () -> login.finish(config, start.pending(),
                Map.of("state", start.pending().state(), "error", "access_denied")));
        assertThrows(OidcException.class, () -> login.finish(config, start.pending(),
                Map.of("code", code, "state", start.pending().state(), "iss", "https://evil.example")));
        clock.advance(OidcLogin.PENDING_LIFETIME.plusSeconds(1));
        assertEquals("the sign-in took too long", assertThrows(OidcException.class,
                () -> login.finish(config, start.pending(), Map.of("code", code, "state", start.pending().state())))
                .getMessage());
        assertEquals(0, provider.tokenRequests.get());
    }

    @Test
    void algNoneRejected() throws Exception {
        provider.algNone = "";
        assertThrows(OidcException.class, this::signIn);
        // Even with something in the signature part, "none" is refused by the allow-list.
        provider.algNone = "c2ln";
        assertEquals("id_token algorithm \"none\" is not allowed",
                assertThrows(OidcException.class, this::signIn).getMessage());
    }

    @Test
    void hmacRejected() throws Exception {
        provider.hs256 = true;
        assertEquals("id_token algorithm \"HS256\" is not allowed",
                assertThrows(OidcException.class, this::signIn).getMessage());
    }

    @Test
    void unknownKidTriggersJwksRefresh() throws Exception {
        signIn();
        assertEquals(1, provider.jwksRequests.get());

        // The provider rotates to a new key.
        provider.rotate("key-2");
        clock.advance(OidcLogin.MIN_JWKS_REFRESH);
        assertEquals("user-1", signIn().get("sub").getAsString());
        assertEquals(2, provider.jwksRequests.get(), "unknown kid fetched the JWKS again");

        // The new key is cached now.
        signIn();
        assertEquals(2, provider.jwksRequests.get());
    }

    @Test
    void unknownKidRefreshIsThrottled() throws Exception {
        signIn();
        provider.signKid = "nobody-has-this"; // a kid the JWKS never contains
        assertThrows(OidcException.class, this::signIn);
        assertEquals(1, provider.jwksRequests.get(), "the JWKS was just fetched");
        clock.advance(OidcLogin.MIN_JWKS_REFRESH);
        assertThrows(OidcException.class, this::signIn);
        assertEquals(2, provider.jwksRequests.get());
        assertThrows(OidcException.class, this::signIn);
        assertEquals(2, provider.jwksRequests.get(), "no second refresh within MIN_JWKS_REFRESH");
    }

    // ---- JWK parsing ----

    @Test
    void weakRsaKeysAndOtherUsesIgnored() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(1024);
        JsonObject weak = rsaJwk("weak", (RSAPublicKey) gen.generateKeyPair().getPublic());
        JsonObject enc = rsaJwk("enc", (RSAPublicKey) rsaKey().getPublic());
        enc.addProperty("use", "enc");
        JsonObject good = rsaJwk("good", (RSAPublicKey) rsaKey().getPublic());
        JsonObject garbage = new JsonObject();
        garbage.addProperty("kty", "RSA");
        garbage.addProperty("n", "!!!");
        JsonArray keys = new JsonArray();
        keys.add(weak);
        keys.add(enc);
        keys.add(good);
        keys.add(garbage);
        JsonObject doc = new JsonObject();
        doc.add("keys", keys);
        Jwks jwks = Jwks.parse(doc);
        assertEquals(List.of("good"), jwks.keys().stream().map(Jwks.Key::kid).toList());
    }

    // ---- fake provider ----

    private static KeyPair rsaKey() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        return gen.generateKeyPair();
    }

    private static JsonObject rsaJwk(String kid, RSAPublicKey key) {
        JsonObject jwk = new JsonObject();
        jwk.addProperty("kty", "RSA");
        jwk.addProperty("kid", kid);
        jwk.addProperty("use", "sig");
        jwk.addProperty("alg", "RS256");
        jwk.addProperty("n", b64(unsigned(key.getModulus())));
        jwk.addProperty("e", b64(unsigned(key.getPublicExponent())));
        return jwk;
    }

    private static byte[] unsigned(BigInteger value) {
        byte[] bytes = value.toByteArray();
        return bytes[0] == 0 ? Arrays.copyOfRange(bytes, 1, bytes.length) : bytes;
    }

    private static byte[] fixed(BigInteger value, int size) {
        byte[] bytes = unsigned(value);
        byte[] out = new byte[size];
        System.arraycopy(bytes, 0, out, size - bytes.length, bytes.length);
        return out;
    }

    private static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String b64(String s) {
        return b64(s.getBytes(StandardCharsets.UTF_8));
    }

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
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /** A minimal OIDC provider: discovery, JWKS and a token endpoint that checks PKCE and client auth. */
    private static final class FakeProvider implements AutoCloseable {
        final String issuer;
        final AtomicInteger discoveryRequests = new AtomicInteger();
        final AtomicInteger jwksRequests = new AtomicInteger();
        final AtomicInteger tokenRequests = new AtomicInteger();
        private final ServerSocket socket;
        private final Map<String, Grant> grants = new ConcurrentHashMap<>();

        volatile List<String> authMethods = List.of("client_secret_basic", "client_secret_post");
        volatile String expectSecret = CLIENT_SECRET;
        /** The published key and kid. */
        volatile JsonObject publishedJwk;
        volatile String publishedAlg = "RS256";
        /** The key and kid tokens are signed with. */
        volatile KeyPair signWith;
        volatile String signKid = "key-1";
        volatile boolean ec;
        volatile Consumer<JsonObject> claims = c -> {
        };
        volatile String algNone;
        volatile boolean hs256;
        volatile boolean tamper;

        private record Grant(String clientId, String redirectUri, String challenge, String nonce) {
        }

        FakeProvider() throws Exception {
            socket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            issuer = "http://127.0.0.1:" + socket.getLocalPort();
            rotate("key-1");
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

        void rotate(String kid) throws Exception {
            KeyPair pair = rsaKey();
            signWith = pair;
            signKid = kid;
            ec = false;
            publishedAlg = "RS256";
            publishedJwk = rsaJwk(kid, (RSAPublicKey) pair.getPublic());
        }

        void useEcKey() throws Exception {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
            gen.initialize(new ECGenParameterSpec("secp256r1"));
            KeyPair pair = gen.generateKeyPair();
            ECPublicKey pub = (ECPublicKey) pair.getPublic();
            JsonObject jwk = new JsonObject();
            jwk.addProperty("kty", "EC");
            jwk.addProperty("kid", "ec-1");
            jwk.addProperty("crv", "P-256");
            jwk.addProperty("x", b64(fixed(pub.getW().getAffineX(), 32)));
            jwk.addProperty("y", b64(fixed(pub.getW().getAffineY(), 32)));
            signWith = pair;
            signKid = "ec-1";
            ec = true;
            publishedJwk = jwk;
        }

        /** What the browser does at the authorization endpoint: the provider remembers the request, returns a code. */
        String authorize(URI url) {
            Map<String, String> q = query(url);
            String code = "code-" + grants.size() + "-" + System.nanoTime();
            grants.put(code, new Grant(q.get("client_id"), q.get("redirect_uri"), q.get("code_challenge"),
                    q.get("nonce")));
            return code;
        }

        private void handle(HTTPRequest request) throws Exception {
            switch (request.path()) {
                case "/.well-known/openid-configuration" -> {
                    discoveryRequests.incrementAndGet();
                    JsonObject doc = new JsonObject();
                    doc.addProperty("issuer", issuer);
                    doc.addProperty("authorization_endpoint", issuer + "/authorize");
                    doc.addProperty("token_endpoint", issuer + "/token");
                    doc.addProperty("jwks_uri", issuer + "/jwks");
                    JsonArray methods = new JsonArray();
                    authMethods.forEach(methods::add);
                    doc.add("token_endpoint_auth_methods_supported", methods);
                    json(request, "200 OK", doc);
                }
                case "/jwks" -> {
                    jwksRequests.incrementAndGet();
                    JsonArray keys = new JsonArray();
                    keys.add(publishedJwk);
                    JsonObject doc = new JsonObject();
                    doc.add("keys", keys);
                    json(request, "200 OK", doc);
                }
                case "/token" -> token(request);
                default -> {
                }
            }
        }

        private void token(HTTPRequest request) throws Exception {
            tokenRequests.incrementAndGet();
            Map<String, String> post = request.post();
            Grant grant = grants.remove(post.getOrDefault("code", ""));
            String error = null;
            if (grant == null) error = "invalid_grant";
            else if (!"authorization_code".equals(post.get("grant_type"))) error = "unsupported_grant_type";
            else if (!grant.redirectUri().equals(post.get("redirect_uri"))) error = "invalid_grant";
            else if (post.get("code_verifier") == null
                    || !OidcLogin.codeChallenge(post.get("code_verifier")).equals(grant.challenge())) {
                error = "invalid_grant";
            } else if (!clientAuthenticated(request, post, grant)) error = "invalid_client";
            if (error != null) {
                JsonObject body = new JsonObject();
                body.addProperty("error", error);
                json(request, "400 Bad Request", body);
                return;
            }
            JsonObject response = new JsonObject();
            response.addProperty("access_token", "at");
            response.addProperty("token_type", "Bearer");
            response.addProperty("id_token", idToken(grant.nonce()));
            json(request, "200 OK", response);
        }

        private boolean clientAuthenticated(HTTPRequest request, Map<String, String> post, Grant grant) {
            String auth = request.getHeader("Authorization");
            if (expectSecret == null) {
                return auth == null && grant.clientId().equals(post.get("client_id"))
                        && post.get("client_secret") == null;
            }
            if (auth != null) {
                if (!authMethods.contains("client_secret_basic")) return false;
                String decoded = new String(Base64.getDecoder().decode(auth.substring("Basic ".length())),
                        StandardCharsets.UTF_8);
                int colon = decoded.indexOf(':');
                return URLDecoder.decode(decoded.substring(0, colon), StandardCharsets.UTF_8).equals(CLIENT_ID)
                        && URLDecoder.decode(decoded.substring(colon + 1), StandardCharsets.UTF_8)
                        .equals(expectSecret);
            }
            return authMethods.contains("client_secret_post") && CLIENT_ID.equals(post.get("client_id"))
                    && expectSecret.equals(post.get("client_secret"));
        }

        private String idToken(String nonce) throws Exception {
            JsonObject c = new JsonObject();
            c.addProperty("iss", issuer);
            c.addProperty("sub", "user-1");
            c.addProperty("aud", CLIENT_ID);
            c.addProperty("iat", NOW.getEpochSecond());
            c.addProperty("exp", NOW.getEpochSecond() + 3600);
            c.addProperty("nonce", nonce);
            c.addProperty("email", "user@example.com");
            c.addProperty("email_verified", true);
            claims.accept(c);
            if (algNone != null) {
                return b64("{\"alg\":\"none\"}") + "." + b64(c.toString()) + "." + algNone;
            }
            if (hs256) {
                JsonObject h = new JsonObject();
                h.addProperty("alg", "HS256");
                h.addProperty("kid", signKid);
                return b64(h.toString()) + "." + b64(c.toString()) + "." + b64("sig");
            }
            JWTAlgorithm alg = ec
                    ? new ES256(fixed(((java.security.interfaces.ECPrivateKey) signWith.getPrivate()).getS(), 32))
                    : new RS256(signWith.getPrivate(), signWith.getPublic());
            alg.setHeaderField("kid", signKid);
            alg.setHeaderField("typ", "JWT");
            String token = alg.sign(c.toString()).toString();
            if (tamper) {
                String[] parts = token.split("\\.");
                c.addProperty("sub", "admin");
                token = parts[0] + "." + b64(c.toString()) + "." + parts[2];
            }
            return token;
        }

        private static void json(HTTPRequest request, String status, JsonObject body) throws IOException {
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
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
