package io.siggi.healthchecker.auth;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.net.URI;
import java.time.Instant;
import java.util.Map;

/**
 * OIDC and OAuth 2.0 sign-in as the web pages use it: {@link OidcLogin} or {@link OAuth2Login} by provider type, with
 * the started sign-in kept as a string (in the visitor's session) until the callback.
 */
public interface ExternalLogin {
    /**
     * A started sign-in.
     *
     * @param authorizationUrl where to send the browser
     * @param pending          what to keep until the callback (opaque)
     */
    record Started(URI authorizationUrl, String pending) {
    }

    /** Starts a sign-in; {@code redirectUri} is {@code {publicBaseUrl}/auth/callback/{providerId}}. */
    Started start(ProviderConfig provider, String redirectUri) throws OidcException;

    /**
     * Completes a sign-in from the callback's query parameters.
     *
     * @param pending what {@link #start} returned for this browser, or {@code null}
     * @return the verified {@code id_token} claims ({@code oidc}) or the userinfo response ({@code oauth2}), for
     * {@link UserDirectory#resolve}
     */
    JsonObject finish(ProviderConfig provider, String pending, Map<String, String> callback) throws OidcException;

    /** The real flows. */
    static ExternalLogin standard() {
        return new Standard(new OidcLogin(), new OAuth2Login());
    }

    /** Delegates by provider type and stores the flows' {@code Pending} records as JSON. */
    final class Standard implements ExternalLogin {
        private final OidcLogin oidc;
        private final OAuth2Login oauth2;

        Standard(OidcLogin oidc, OAuth2Login oauth2) {
            this.oidc = oidc;
            this.oauth2 = oauth2;
        }

        @Override
        public Started start(ProviderConfig provider, String redirectUri) throws OidcException {
            JsonObject json = new JsonObject();
            URI url;
            if (provider.type() == ProviderConfig.Type.OIDC) {
                OidcLogin.Start start = oidc.start(provider, redirectUri);
                OidcLogin.Pending p = start.pending();
                json.addProperty("type", "oidc");
                json.addProperty("provider", p.providerId());
                json.addProperty("state", p.state());
                json.addProperty("nonce", p.nonce());
                json.addProperty("verifier", p.codeVerifier());
                json.addProperty("redirectUri", p.redirectUri());
                json.addProperty("started", p.started().toString());
                url = start.authorizationUrl();
            } else {
                OAuth2Login.Start start = oauth2.start(provider, redirectUri);
                OAuth2Login.Pending p = start.pending();
                json.addProperty("type", "oauth2");
                json.addProperty("provider", p.providerId());
                json.addProperty("state", p.state());
                if (p.codeVerifier() != null) json.addProperty("verifier", p.codeVerifier());
                json.addProperty("redirectUri", p.redirectUri());
                json.addProperty("started", p.started().toString());
                url = start.authorizationUrl();
            }
            return new Started(url, json.toString());
        }

        @Override
        public JsonObject finish(ProviderConfig provider, String pending, Map<String, String> callback)
                throws OidcException {
            JsonObject json = parse(pending);
            if (provider.type() == ProviderConfig.Type.OIDC) {
                OidcLogin.Pending p = null;
                if (json != null && "oidc".equals(OidcLogin.string(json, "type"))) {
                    try {
                        p = new OidcLogin.Pending(OidcLogin.string(json, "provider"), OidcLogin.string(json, "state"),
                                OidcLogin.string(json, "nonce"), OidcLogin.string(json, "verifier"),
                                OidcLogin.string(json, "redirectUri"), started(json));
                    } catch (RuntimeException e) {
                        p = null;
                    }
                }
                return oidc.finish(provider, p, callback);
            }
            OAuth2Login.Pending p = null;
            if (json != null && "oauth2".equals(OidcLogin.string(json, "type"))) {
                try {
                    p = new OAuth2Login.Pending(OidcLogin.string(json, "provider"), OidcLogin.string(json, "state"),
                            OidcLogin.string(json, "verifier"), OidcLogin.string(json, "redirectUri"), started(json));
                } catch (RuntimeException e) {
                    p = null;
                }
            }
            return oauth2.finish(provider, p, callback).userinfo();
        }

        private static JsonObject parse(String pending) {
            if (pending == null) return null;
            try {
                JsonElement e = JsonParser.parseString(pending);
                return e.isJsonObject() ? e.getAsJsonObject() : null;
            } catch (JsonParseException e) {
                return null;
            }
        }

        private static Instant started(JsonObject json) {
            String started = OidcLogin.string(json, "started");
            return started == null ? null : Instant.parse(started);
        }
    }
}
