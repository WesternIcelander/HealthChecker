package io.siggi.healthchecker.auth;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import io.siggi.simplejwt.JWTToken;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The OIDC authorization code flow for {@code oidc} providers.
 * <ol>
 *   <li>{@link #start}: discovery ({@code {issuer}/.well-known/openid-configuration}, cached), then a fresh
 *       {@code state}, {@code nonce} and PKCE (S256) verifier, and the URL to send the browser to. The caller keeps
 *       the returned {@link Pending} (in the visitor's session) until the callback.</li>
 *   <li>{@link #finish}: on the callback, checks {@code state} (and RFC 9207 {@code iss} when sent), exchanges
 *       the code at the token endpoint (with the PKCE verifier and the client secret, if any), and verifies the
 *       {@code id_token}.</li>
 * </ol>
 * The {@code id_token} is verified with SimpleJWT against the provider's JWKS, which is cached and fetched again
 * when a token names an unknown {@code kid} (at most once per {@link #MIN_JWKS_REFRESH}). Checked: the algorithm
 * allow-list ({@link Jwks#ALLOWED_ALGORITHMS}), the signature, {@code iss} (exactly the configured issuer),
 * {@code aud} (contains the client id; {@code azp} must be the client id when present or when there are several
 * audiences), {@code exp}, {@code iat} and {@code nbf} with {@link #CLOCK_SKEW}, {@code nonce} and {@code sub}.
 * <p>A {@link Pending} is single-use: the caller removes it before calling {@link #finish}.
 */
public final class OidcLogin {
    /** How long a started sign-in may take. */
    public static final Duration PENDING_LIFETIME = Duration.ofMinutes(10);
    static final Duration CLOCK_SKEW = Duration.ofSeconds(60);
    static final Duration DISCOVERY_TTL = Duration.ofHours(1);
    static final Duration JWKS_TTL = Duration.ofHours(24);
    static final Duration MIN_JWKS_REFRESH = Duration.ofSeconds(30);
    static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);
    static final int MAX_RESPONSE_BYTES = 1 << 20;
    private static final String WELL_KNOWN = "/.well-known/openid-configuration";

    /**
     * A started sign-in, kept by the caller until the callback.
     *
     * @param providerId   the provider it was started for
     * @param state        the {@code state} sent to the provider (CSRF protection for the callback)
     * @param nonce        the {@code nonce} the {@code id_token} must carry
     * @param codeVerifier the PKCE verifier
     * @param redirectUri  the callback URL used, sent again with the code exchange
     * @param started      when it was started
     */
    public record Pending(String providerId, String state, String nonce, String codeVerifier, String redirectUri,
                          Instant started) {
        public Pending {
            Objects.requireNonNull(providerId);
            Objects.requireNonNull(state);
            Objects.requireNonNull(nonce);
            Objects.requireNonNull(codeVerifier);
            Objects.requireNonNull(redirectUri);
            Objects.requireNonNull(started);
        }
    }

    /** The result of {@link #start}: where to send the browser, and what to keep until the callback. */
    public record Start(URI authorizationUrl, Pending pending) {
    }

    /** The provider's discovery document, reduced to what the flow uses. */
    record Metadata(String issuer, URI authorizationEndpoint, URI tokenEndpoint, URI jwksUri,
                    List<String> tokenAuthMethods, Instant fetched) {
    }

    private record CachedJwks(Jwks jwks, Instant fetched) {
    }

    private final HttpClient http;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Metadata> discovery = new ConcurrentHashMap<>();
    private final Map<URI, CachedJwks> jwks = new ConcurrentHashMap<>();

    public OidcLogin() {
        this(Clock.systemUTC());
    }

    OidcLogin(Clock clock) {
        this.clock = clock;
        this.http = newHttpClient();
    }

    /** An HTTP client for provider requests: no redirects, {@link #HTTP_TIMEOUT} to connect. */
    static HttpClient newHttpClient() {
        return HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(HTTP_TIMEOUT)
                .build();
    }

    // ---- start ----

    /**
     * Starts a sign-in with an {@code oidc} provider.
     *
     * @param redirectUri the callback URL ({@code {publicBaseUrl}/auth/callback/{providerId}})
     */
    public Start start(ProviderConfig provider, String redirectUri) throws OidcException {
        requireOidc(provider);
        Metadata metadata = metadata(provider);
        String state = randomToken();
        String nonce = randomToken();
        String verifier = randomToken();
        Map<String, String> params = new LinkedHashMap<>();
        params.put("response_type", "code");
        params.put("client_id", provider.clientId());
        params.put("redirect_uri", redirectUri);
        params.put("scope", String.join(" ", provider.scopes()));
        params.put("state", state);
        params.put("nonce", nonce);
        params.put("code_challenge", codeChallenge(verifier));
        params.put("code_challenge_method", "S256");
        String base = metadata.authorizationEndpoint().toString();
        URI url = URI.create(base + (metadata.authorizationEndpoint().getRawQuery() == null ? "?" : "&")
                + form(params));
        return new Start(url, new Pending(provider.id(), state, nonce, verifier, redirectUri, clock.instant()));
    }

    // ---- finish ----

    /**
     * Completes a sign-in from the callback's query parameters.
     *
     * @param pending  what {@link #start} returned for this browser, or {@code null} if there is none
     * @param callback the callback's query parameters ({@code code}, {@code state}, maybe {@code error}, {@code iss})
     * @return the verified {@code id_token} claims (for {@link ProviderConfig#identify})
     * @throws OidcException if anything doesn't check out
     */
    public JsonObject finish(ProviderConfig provider, Pending pending, Map<String, String> callback)
            throws OidcException {
        requireOidc(provider);
        if (pending == null || !pending.providerId().equals(provider.id())) {
            throw new OidcException("no sign-in in progress for provider " + provider.id());
        }
        if (clock.instant().isAfter(pending.started().plus(PENDING_LIFETIME))) {
            throw new OidcException("the sign-in took too long");
        }
        String state = callback.get("state");
        if (state == null || !constantTimeEquals(state, pending.state())) {
            throw new OidcException("state mismatch");
        }
        String error = callback.get("error");
        if (error != null) {
            throw new OidcException("provider returned error \"" + truncate(error) + "\"");
        }
        String iss = callback.get("iss");
        if (iss != null && !iss.equals(provider.issuer())) {
            throw new OidcException("callback iss mismatch");
        }
        String code = callback.get("code");
        if (code == null || code.isEmpty()) throw new OidcException("callback has no code");

        Metadata metadata = metadata(provider);
        String idToken = exchangeCode(provider, metadata, pending, code);
        return verifyIdToken(provider, metadata, idToken, pending.nonce());
    }

    private String exchangeCode(ProviderConfig provider, Metadata metadata, Pending pending, String code)
            throws OidcException {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("grant_type", "authorization_code");
        params.put("code", code);
        params.put("redirect_uri", pending.redirectUri());
        params.put("code_verifier", pending.codeVerifier());
        HttpRequest.Builder request = HttpRequest.newBuilder(metadata.tokenEndpoint())
                .timeout(HTTP_TIMEOUT)
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded");
        if (provider.clientSecret() == null) {
            params.put("client_id", provider.clientId());
        } else if (metadata.tokenAuthMethods().contains("client_secret_basic")) {
            // RFC 6749 §2.3.1: both parts are form-encoded before Base64.
            String credentials = urlEncode(provider.clientId()) + ":" + urlEncode(provider.clientSecret());
            request.header("Authorization", "Basic "
                    + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8)));
        } else if (metadata.tokenAuthMethods().contains("client_secret_post")) {
            params.put("client_id", provider.clientId());
            params.put("client_secret", provider.clientSecret());
        } else {
            throw new OidcException("token endpoint supports neither client_secret_basic nor client_secret_post");
        }
        request.POST(HttpRequest.BodyPublishers.ofString(form(params)));
        JsonObject response = fetchJson(http, request.build(), "token endpoint");
        if (!(response.get("id_token") instanceof JsonPrimitive p && p.isString())) {
            throw new OidcException("token response has no id_token");
        }
        return p.getAsString();
    }

    // ---- id_token verification ----

    /** Verifies an {@code id_token} and returns its claims. */
    JsonObject verifyIdToken(ProviderConfig provider, Metadata metadata, String idToken, String nonce)
            throws OidcException {
        JWTToken token;
        JsonObject header;
        JsonObject claims;
        try {
            token = JWTToken.parse(idToken);
            header = token.getHeader();
            claims = token.getPayload();
        } catch (RuntimeException e) {
            throw new OidcException("id_token is malformed");
        }
        if (claims == null) throw new OidcException("id_token payload is not a JSON object");

        String alg = string(header, "alg");
        if (alg == null || !Jwks.ALLOWED_ALGORITHMS.contains(alg)) {
            throw new OidcException("id_token algorithm \"" + truncate(String.valueOf(alg)) + "\" is not allowed");
        }
        if (header.has("crit")) throw new OidcException("id_token has unsupported critical header parameters");
        if (header.has("kid") && string(header, "kid") == null) throw new OidcException("id_token kid is invalid");
        verifySignature(metadata, token, alg, string(header, "kid"));

        String iss = string(claims, "iss");
        if (iss == null || !iss.equals(provider.issuer())) throw new OidcException("id_token iss mismatch");
        checkAudience(provider, claims);

        long now = clock.instant().getEpochSecond();
        long skew = CLOCK_SKEW.toSeconds();
        Long exp = numericDate(claims, "exp");
        if (exp == null) throw new OidcException("id_token has no exp");
        if (now >= exp + skew) throw new OidcException("id_token has expired");
        Long iat = numericDate(claims, "iat");
        if (iat == null) throw new OidcException("id_token has no iat");
        if (iat > now + skew) throw new OidcException("id_token was issued in the future");
        if (claims.has("nbf")) {
            Long nbf = numericDate(claims, "nbf");
            if (nbf == null || nbf > now + skew) throw new OidcException("id_token is not valid yet");
        }

        String tokenNonce = string(claims, "nonce");
        if (tokenNonce == null || !constantTimeEquals(tokenNonce, nonce)) {
            throw new OidcException("id_token nonce mismatch");
        }
        String sub = string(claims, "sub");
        if (sub == null || sub.isEmpty()) throw new OidcException("id_token has no sub");
        return claims;
    }

    private void verifySignature(Metadata metadata, JWTToken token, String alg, String kid) throws OidcException {
        Jwks keys = jwks(metadata, false);
        if (kid != null && !keys.hasKid(kid)) {
            // A key we haven't seen: the provider may have rotated its keys.
            keys = jwks(metadata, true);
        }
        List<Jwks.Key> candidates = keys.candidates(kid, alg);
        if (candidates.isEmpty()) {
            throw new OidcException("no " + alg + " key" + (kid == null ? "" : " with kid \"" + truncate(kid)
                    + "\"") + " in the provider's JWKS");
        }
        byte[] signature = token.getSignature();
        for (Jwks.Key key : candidates) {
            // SimpleJWT's ES verification would accept other even lengths; JWS needs exactly r ‖ s.
            if (key.ecSignatureLength() >= 0 && signature.length != key.ecSignatureLength()) continue;
            try {
                if (key.verifier(alg).verify(token)) return;
            } catch (RuntimeException e) {
                // A malformed signature for this key: try the next one.
            }
        }
        throw new OidcException("id_token signature is invalid");
    }

    private static void checkAudience(ProviderConfig provider, JsonObject claims) throws OidcException {
        List<String> audiences = new ArrayList<>();
        JsonElement aud = claims.get("aud");
        if (aud instanceof JsonPrimitive p && p.isString()) {
            audiences.add(p.getAsString());
        } else if (aud instanceof JsonArray array) {
            for (JsonElement e : array) {
                if (!(e instanceof JsonPrimitive p && p.isString())) throw new OidcException("id_token aud is invalid");
                audiences.add(p.getAsString());
            }
        }
        if (!audiences.contains(provider.clientId())) throw new OidcException("id_token aud mismatch");
        boolean hasAzp = claims.has("azp");
        if (hasAzp && !provider.clientId().equals(string(claims, "azp"))) {
            throw new OidcException("id_token azp mismatch");
        }
        if (audiences.size() > 1 && !hasAzp) throw new OidcException("id_token has several audiences but no azp");
    }

    /** A NumericDate claim as whole seconds (fractions dropped), or {@code null} if it isn't a number. */
    private static Long numericDate(JsonObject claims, String name) {
        if (!(claims.get(name) instanceof JsonPrimitive p && p.isNumber())) return null;
        try {
            return new BigDecimal(p.getAsString()).longValue();
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ---- discovery & JWKS ----

    /** The provider's discovery document, fetched when not cached or older than {@link #DISCOVERY_TTL}. */
    Metadata metadata(ProviderConfig provider) throws OidcException {
        Metadata cached = discovery.get(provider.id());
        if (cached != null && clock.instant().isBefore(cached.fetched().plus(DISCOVERY_TTL))) return cached;
        synchronized (discovery) {
            cached = discovery.get(provider.id());
            if (cached != null && clock.instant().isBefore(cached.fetched().plus(DISCOVERY_TTL))) return cached;
            Metadata fresh = discover(provider);
            discovery.put(provider.id(), fresh);
            return fresh;
        }
    }

    private Metadata discover(ProviderConfig provider) throws OidcException {
        String issuer = provider.issuer();
        String url = (issuer.endsWith("/") ? issuer.substring(0, issuer.length() - 1) : issuer) + WELL_KNOWN;
        JsonObject doc = fetchJson(http, get(uri(url, "discovery URL")), "discovery");
        // OIDC Discovery §4.3: the document's issuer must be exactly the configured one.
        if (!issuer.equals(string(doc, "issuer"))) throw new OidcException("discovery issuer mismatch");
        URI authorization = endpoint(doc, "authorization_endpoint");
        URI token = endpoint(doc, "token_endpoint");
        URI jwksUri = endpoint(doc, "jwks_uri");
        List<String> methods = new ArrayList<>();
        if (doc.get("token_endpoint_auth_methods_supported") instanceof JsonArray array) {
            for (JsonElement e : array) {
                if (e instanceof JsonPrimitive p && p.isString()) methods.add(p.getAsString());
            }
        } else {
            methods.add("client_secret_basic"); // the default when the member is missing
        }
        return new Metadata(issuer, authorization, token, jwksUri, List.copyOf(methods), clock.instant());
    }

    /** The JWKS, fetched when not cached, stale, or ({@code refresh}) when allowed by {@link #MIN_JWKS_REFRESH}. */
    private Jwks jwks(Metadata metadata, boolean refresh) throws OidcException {
        URI uri = metadata.jwksUri();
        Instant now = clock.instant();
        synchronized (jwks) {
            CachedJwks cached = jwks.get(uri);
            boolean fetch = cached == null
                    || !now.isBefore(cached.fetched().plus(JWKS_TTL))
                    || (refresh && !now.isBefore(cached.fetched().plus(MIN_JWKS_REFRESH)));
            if (!fetch) return cached.jwks();
            Jwks fresh = Jwks.parse(fetchJson(http, get(uri), "JWKS"));
            jwks.put(uri, new CachedJwks(fresh, now));
            return fresh;
        }
    }

    private static URI endpoint(JsonObject doc, String member) throws OidcException {
        String value = string(doc, member);
        if (value == null) throw new OidcException("discovery has no " + member);
        URI uri = uri(value, member);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        boolean ok = uri.isAbsolute() && uri.getHost() != null && uri.getRawUserInfo() == null
                && uri.getRawFragment() == null
                && (scheme.equals("https") || (scheme.equals("http") && AuthProviders.isLoopback(uri.getHost())));
        if (!ok) throw new OidcException("discovery " + member + " must be an https URL");
        return uri;
    }

    static URI uri(String value, String what) throws OidcException {
        try {
            return new URI(value);
        } catch (URISyntaxException e) {
            throw new OidcException(what + " is not a valid URL");
        }
    }

    private static HttpRequest get(URI uri) {
        return HttpRequest.newBuilder(uri).timeout(HTTP_TIMEOUT).header("Accept", "application/json").GET().build();
    }

    /** Sends a request and reads a JSON object response (HTTP 200, at most {@link #MAX_RESPONSE_BYTES}). */
    static JsonObject fetchJson(HttpClient http, HttpRequest request, String what) throws OidcException {
        HttpResponse<InputStream> response;
        byte[] body;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = response.body()) {
                body = in.readNBytes(MAX_RESPONSE_BYTES + 1);
            }
        } catch (IOException e) {
            throw new OidcException(what + " request failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OidcException(what + " request interrupted", e);
        }
        if (response.statusCode() != 200) {
            throw new OidcException(what + " returned HTTP " + response.statusCode());
        }
        if (body.length > MAX_RESPONSE_BYTES) throw new OidcException(what + " response is too large");
        try {
            JsonElement json = JsonParser.parseString(new String(body, StandardCharsets.UTF_8));
            if (json instanceof JsonObject object) return object;
        } catch (RuntimeException e) {
            // fall through
        }
        throw new OidcException(what + " response is not a JSON object");
    }

    // ---- helpers ----

    private static void requireOidc(ProviderConfig provider) {
        if (provider.type() != ProviderConfig.Type.OIDC) {
            throw new IllegalArgumentException("provider " + provider.id() + " is not an oidc provider");
        }
    }

    /** 32 random bytes, base64url without padding (43 characters; also a valid PKCE verifier). */
    private String randomToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** The PKCE S256 challenge for a verifier. */
    static String codeChallenge(String verifier) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    static String form(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (!sb.isEmpty()) sb.append('&');
            sb.append(urlEncode(e.getKey())).append('=').append(urlEncode(e.getValue()));
        }
        return sb.toString();
    }

    static String urlEncode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    static String string(JsonObject object, String key) {
        return object.get(key) instanceof JsonPrimitive p && p.isString() ? p.getAsString() : null;
    }

    static String truncate(String s) {
        String printable = s.replaceAll("[\\p{Cntrl}]", "?");
        return printable.length() <= 100 ? printable : printable.substring(0, 100) + "…";
    }
}
