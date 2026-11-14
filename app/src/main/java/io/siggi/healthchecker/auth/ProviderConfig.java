package io.siggi.healthchecker.auth;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import io.siggi.healthchecker.config.SiteConfig;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * One OIDC or OAuth 2.0 sign-in provider ({@code auth.providers[]}), validated by {@link AuthProviders}.
 *
 * @param id           the provider id: the first part of every identifier and of the callback path
 * @param type         {@code oidc} or {@code oauth2}
 * @param label        the "Sign in with …" button text
 * @param clientId     the OAuth client id
 * @param clientSecret the client secret, or {@code null} for a public client (PKCE only)
 * @param scopes       requested scopes (for {@code oidc} always including {@code openid})
 * @param sites        lowercase site hosts the provider is limited to; empty = every site
 * @param issuer       {@code oidc}: the issuer as configured (exactly what {@code iss} must be), else {@code null}
 * @param identifyBy   {@code oidc}: the claim that identifies a person ({@code sub}, {@code email} or another
 *                     claim), else {@code null}
 * @param authorizeUrl {@code oauth2}: the authorization endpoint, else {@code null}
 * @param tokenUrl     {@code oauth2}: the token endpoint, else {@code null}
 * @param userinfoUrl  {@code oauth2}: the userinfo endpoint, else {@code null}
 * @param idField      {@code oauth2}: the userinfo field that identifies a person, else {@code null}
 * @param nameField    {@code oauth2}: the userinfo field with a display name, or {@code null}
 * @param pkce         whether PKCE (S256) is used: always for {@code oidc}, {@code pkce} setting for {@code oauth2}
 */
public record ProviderConfig(String id, Type type, String label, String clientId, String clientSecret,
                             List<String> scopes, List<String> sites,
                             String issuer, String identifyBy,
                             String authorizeUrl, String tokenUrl, String userinfoUrl,
                             String idField, String nameField, boolean pkce) {
    /** The default {@code identifyBy} for {@code oidc}. */
    public static final String SUB = "sub";
    /** {@code identifyBy} for verified email addresses. */
    public static final String EMAIL = "email";
    /** Longest identifier value accepted from a provider. */
    public static final int MAX_VALUE_LENGTH = 512;

    public enum Type {
        OIDC("oidc"), OAUTH2("oauth2");

        private final String id;

        Type(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        public static Optional<Type> byId(String id) {
            for (Type type : values()) {
                if (type.id.equals(id)) return Optional.of(type);
            }
            return Optional.empty();
        }
    }

    public ProviderConfig {
        scopes = List.copyOf(scopes);
        sites = List.copyOf(sites);
    }

    /**
     * The identifier {@code {providerId}:{value}} for a value from this provider.
     *
     * @throws IllegalArgumentException if the value is empty, too long or contains control characters
     */
    public String identifier(String value) {
        if (!validValue(value)) throw new IllegalArgumentException("invalid identifier value");
        return id + ":" + value;
    }

    /**
     * The identifier for a signed-in person, from the verified {@code id_token} claims ({@code oidc}) or the
     * userinfo response ({@code oauth2}).
     * <ul>
     *   <li>{@code oidc}: the {@code identifyBy} claim; for {@code email} only when {@code email_verified} is
     *       {@code true}.</li>
     *   <li>{@code oauth2}: the {@code idField} value.</li>
     * </ul>
     * The value must be a string or a whole number (e.g. GitHub's numeric {@code id}).
     *
     * @return the identifier, or empty when the value is missing, unverified or unusable
     */
    public Optional<String> identify(JsonObject claims) {
        if (claims == null) return Optional.empty();
        String field = type == Type.OIDC ? identifyBy : idField;
        if (type == Type.OIDC && field.equals(EMAIL) && !emailVerified(claims.get("email_verified"))) {
            return Optional.empty();
        }
        return value(claims.get(field)).filter(ProviderConfig::validValue).map(v -> id + ":" + v);
    }

    /** The display name hint from the userinfo response ({@code oauth2} with {@code nameField}), if any. */
    public Optional<String> displayName(JsonObject userinfo) {
        if (nameField == null || userinfo == null) return Optional.empty();
        return value(userinfo.get(nameField)).filter(v -> !v.isBlank());
    }

    /**
     * A name for the signed-in person, as the provider gave it: {@code oauth2} the {@code nameField} value;
     * {@code oidc} the {@code name} claim, else {@code preferred_username}. Only a hint (not unique, not verified).
     */
    public Optional<String> nameHint(JsonObject attributes) {
        if (attributes == null) return Optional.empty();
        if (type == Type.OAUTH2) return displayName(attributes).filter(ProviderConfig::validValue);
        return value(attributes.get("name")).filter(v -> !v.isBlank() && validValue(v))
                .or(() -> value(attributes.get("preferred_username")).filter(v -> !v.isBlank() && validValue(v)));
    }

    /** The {@code email} claim or userinfo field, verified or not: only a hint shown to the person themselves. */
    public Optional<String> emailHint(JsonObject attributes) {
        if (attributes == null || !(attributes.get("email") instanceof JsonPrimitive p) || !p.isString()) {
            return Optional.empty();
        }
        return Optional.of(p.getAsString()).filter(v -> !v.isBlank() && validValue(v));
    }

    /** Whether the provider is offered on this site ({@code null} = the default site). */
    public boolean availableOn(SiteConfig site) {
        if (sites.isEmpty()) return true;
        if (site == null) return false;
        for (String host : site.hosts()) {
            if (sites.contains(host.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    private static boolean emailVerified(JsonElement element) {
        if (!(element instanceof JsonPrimitive p)) return false;
        if (p.isBoolean()) return p.getAsBoolean();
        // Some providers send the flag as a string.
        return p.isString() && p.getAsString().equalsIgnoreCase("true");
    }

    private static Optional<String> value(JsonElement element) {
        if (!(element instanceof JsonPrimitive p)) return Optional.empty();
        if (p.isString()) return Optional.of(p.getAsString());
        if (p.isNumber()) {
            try {
                return Optional.of(new BigDecimal(p.getAsString()).toBigIntegerExact().toString());
            } catch (ArithmeticException | NumberFormatException e) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    static boolean validValue(String value) {
        if (value == null || value.isEmpty() || value.length() > MAX_VALUE_LENGTH) return false;
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) return false;
        }
        return true;
    }
}
