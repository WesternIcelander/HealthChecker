package io.siggi.healthchecker.auth;

import com.google.gson.JsonObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static io.siggi.healthchecker.auth.OidcLogin.HTTP_TIMEOUT;
import static io.siggi.healthchecker.auth.OidcLogin.constantTimeEquals;
import static io.siggi.healthchecker.auth.OidcLogin.fetchJson;
import static io.siggi.healthchecker.auth.OidcLogin.form;
import static io.siggi.healthchecker.auth.OidcLogin.string;
import static io.siggi.healthchecker.auth.OidcLogin.truncate;
import static io.siggi.healthchecker.auth.OidcLogin.uri;

/**
 * The OAuth 2.0 authorization code flow for generic {@code oauth2} providers (e.g. GitHub, Discord), which have no
 * {@code id_token}: the person is identified from the provider's userinfo response.
 * <ol>
 *   <li>{@link #start}: a fresh {@code state} and, when the provider has {@code pkce} enabled (the default), a PKCE
 *       (S256) verifier, and the {@code authorizeUrl} to send the browser to. The caller keeps the returned
 *       {@link Pending} (in the visitor's session) until the callback.</li>
 *   <li>{@link #finish}: on the callback, checks {@code state}, exchanges the code at {@code tokenUrl}
 *       ({@code client_secret_post}, or only {@code client_id} for a public client), fetches {@code userinfoUrl}
 *       with the bearer access token, and takes the identifier from {@code idField} and the display name from
 *       {@code nameField}.</li>
 * </ol>
 * <p>A {@link Pending} is single-use: the caller removes it before calling {@link #finish}.
 */
public final class OAuth2Login {
    /** How long a started sign-in may take. */
    public static final Duration PENDING_LIFETIME = OidcLogin.PENDING_LIFETIME;
    private static final String USER_AGENT = "HealthChecker";

    /**
     * A started sign-in, kept by the caller until the callback.
     *
     * @param providerId   the provider it was started for
     * @param state        the {@code state} sent to the provider (CSRF protection for the callback)
     * @param codeVerifier the PKCE verifier, or {@code null} when the provider has PKCE disabled
     * @param redirectUri  the callback URL used, sent again with the code exchange
     * @param started      when it was started
     */
    public record Pending(String providerId, String state, String codeVerifier, String redirectUri,
                          Instant started) {
        public Pending {
            Objects.requireNonNull(providerId);
            Objects.requireNonNull(state);
            Objects.requireNonNull(redirectUri);
            Objects.requireNonNull(started);
        }
    }

    /** The result of {@link #start}: where to send the browser, and what to keep until the callback. */
    public record Start(URI authorizationUrl, Pending pending) {
    }

    /**
     * A completed sign-in.
     *
     * @param identifier  {@code {providerId}:{idField value}}
     * @param displayName the {@code nameField} value, if configured and present (a hint for the person only)
     * @param userinfo    the provider's userinfo response
     */
    public record Result(String identifier, Optional<String> displayName, JsonObject userinfo) {
    }

    private final HttpClient http;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public OAuth2Login() {
        this(Clock.systemUTC());
    }

    OAuth2Login(Clock clock) {
        this.clock = clock;
        this.http = OidcLogin.newHttpClient();
    }

    // ---- start ----

    /**
     * Starts a sign-in with an {@code oauth2} provider.
     *
     * @param redirectUri the callback URL ({@code {publicBaseUrl}/auth/callback/{providerId}})
     */
    public Start start(ProviderConfig provider, String redirectUri) throws OidcException {
        requireOAuth2(provider);
        URI authorize = uri(provider.authorizeUrl(), "authorizeUrl");
        String state = randomToken();
        String verifier = provider.pkce() ? randomToken() : null;
        Map<String, String> params = new LinkedHashMap<>();
        params.put("response_type", "code");
        params.put("client_id", provider.clientId());
        params.put("redirect_uri", redirectUri);
        if (!provider.scopes().isEmpty()) params.put("scope", String.join(" ", provider.scopes()));
        params.put("state", state);
        if (verifier != null) {
            params.put("code_challenge", OidcLogin.codeChallenge(verifier));
            params.put("code_challenge_method", "S256");
        }
        URI url = URI.create(provider.authorizeUrl() + (authorize.getRawQuery() == null ? "?" : "&") + form(params));
        return new Start(url, new Pending(provider.id(), state, verifier, redirectUri, clock.instant()));
    }

    // ---- finish ----

    /**
     * Completes a sign-in from the callback's query parameters.
     *
     * @param pending  what {@link #start} returned for this browser, or {@code null} if there is none
     * @param callback the callback's query parameters ({@code code}, {@code state}, maybe {@code error})
     * @return the identifier and display name
     * @throws OidcException if anything doesn't check out, including a userinfo response without a usable
     *                       {@code idField}
     */
    public Result finish(ProviderConfig provider, Pending pending, Map<String, String> callback)
            throws OidcException {
        requireOAuth2(provider);
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
        String code = callback.get("code");
        if (code == null || code.isEmpty()) throw new OidcException("callback has no code");

        String accessToken = exchangeCode(provider, pending, code);
        JsonObject userinfo = userinfo(provider, accessToken);
        Optional<String> identifier = provider.identify(userinfo);
        if (identifier.isEmpty()) {
            throw new OidcException("userinfo has no usable \"" + truncate(provider.idField()) + "\"");
        }
        return new Result(identifier.get(), provider.displayName(userinfo), userinfo);
    }

    private String exchangeCode(ProviderConfig provider, Pending pending, String code) throws OidcException {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("grant_type", "authorization_code");
        params.put("code", code);
        params.put("redirect_uri", pending.redirectUri());
        if (pending.codeVerifier() != null) params.put("code_verifier", pending.codeVerifier());
        params.put("client_id", provider.clientId());
        if (provider.clientSecret() != null) params.put("client_secret", provider.clientSecret());
        HttpRequest request = HttpRequest.newBuilder(uri(provider.tokenUrl(), "tokenUrl"))
                .timeout(HTTP_TIMEOUT)
                // Without this, GitHub answers form-encoded.
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form(params)))
                .build();
        JsonObject response = fetchJson(http, request, "token endpoint");
        // GitHub reports errors with HTTP 200.
        String error = string(response, "error");
        if (error != null) throw new OidcException("token endpoint returned error \"" + truncate(error) + "\"");
        String accessToken = string(response, "access_token");
        if (accessToken == null || accessToken.isEmpty()) throw new OidcException("token response has no access_token");
        // RFC 6750 b64token characters, so it can't break the Authorization header.
        if (!accessToken.matches("[A-Za-z0-9._~+/=-]+")) throw new OidcException("access_token is malformed");
        String tokenType = string(response, "token_type");
        if (tokenType != null && !tokenType.toLowerCase(Locale.ROOT).equals("bearer")) {
            throw new OidcException("token type \"" + truncate(tokenType) + "\" is not supported");
        }
        return accessToken;
    }

    private JsonObject userinfo(ProviderConfig provider, String accessToken) throws OidcException {
        HttpRequest request = HttpRequest.newBuilder(uri(provider.userinfoUrl(), "userinfoUrl"))
                .timeout(HTTP_TIMEOUT)
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT)
                .header("Authorization", "Bearer " + accessToken)
                .GET()
                .build();
        return fetchJson(http, request, "userinfo endpoint");
    }

    // ---- helpers ----

    private static void requireOAuth2(ProviderConfig provider) {
        if (provider.type() != ProviderConfig.Type.OAUTH2) {
            throw new IllegalArgumentException("provider " + provider.id() + " is not an oauth2 provider");
        }
    }

    /** 32 random bytes, base64url without padding (43 characters; also a valid PKCE verifier). */
    private String randomToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
