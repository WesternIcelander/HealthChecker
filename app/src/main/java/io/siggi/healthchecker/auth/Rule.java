package io.siggi.healthchecker.auth;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.Locale;
import java.util.Objects;

/**
 * An access rule ({@code auth.rules[]}): grants {@code role} to people signing in with {@code provider} who aren't
 * listed as a user, when every condition given matches.
 * <ul>
 *   <li>{@code emailDomain}: the {@code email} claim's domain (after the last {@code @}, case-insensitive, exact,
 *       so subdomains don't match), and only when {@code email_verified} is {@code true}. Only for {@code oidc}
 *       providers, since plain OAuth 2.0 userinfo doesn't say whether an address is verified.</li>
 *   <li>{@code group}: the {@code groups} claim (or userinfo field) is this string or an array containing it.</li>
 * </ul>
 * At least one condition is required, so a rule never admits everyone who has an account at the provider.
 *
 * @param provider    the provider id
 * @param emailDomain lowercase domain, or {@code null}
 * @param group       group name, or {@code null}
 * @param role        the role granted
 */
public record Rule(String provider, String emailDomain, String group, Role role) {
    /** The claim holding a person's groups. */
    public static final String GROUPS_CLAIM = "groups";

    public Rule {
        Objects.requireNonNull(provider);
        Objects.requireNonNull(role);
        if (emailDomain == null && group == null) throw new IllegalArgumentException("a rule needs a condition");
        if (emailDomain != null) emailDomain = emailDomain.toLowerCase(Locale.ROOT);
    }

    /** Whether the verified claims ({@code oidc}) or userinfo ({@code oauth2}) from {@code providerId} match. */
    public boolean matches(String providerId, JsonObject attributes) {
        if (!provider.equals(providerId) || attributes == null) return false;
        if (emailDomain != null && !emailDomainMatches(attributes)) return false;
        return group == null || groupMatches(attributes.get(GROUPS_CLAIM));
    }

    /**
     * What a session granted by this rule remembers: the rule's provider, conditions and role name. A session
     * stays valid only while a rule with the same key exists, so a rule removed or changed in the config (applied
     * at restart) ends the sessions it granted.
     */
    public String key() {
        JsonObject key = new JsonObject();
        key.addProperty("provider", provider);
        if (emailDomain != null) key.addProperty("emailDomain", emailDomain);
        if (group != null) key.addProperty("group", group);
        key.addProperty("role", role.name());
        return key.toString();
    }

    private boolean emailDomainMatches(JsonObject attributes) {
        if (!(attributes.get("email_verified") instanceof JsonPrimitive verified)) return false;
        boolean ok = verified.isBoolean() ? verified.getAsBoolean()
                : verified.isString() && verified.getAsString().equalsIgnoreCase("true");
        if (!ok) return false;
        if (!(attributes.get("email") instanceof JsonPrimitive email && email.isString())) return false;
        String address = email.getAsString();
        int at = address.lastIndexOf('@');
        if (at <= 0 || at == address.length() - 1) return false;
        return address.substring(at + 1).toLowerCase(Locale.ROOT).equals(emailDomain);
    }

    private boolean groupMatches(JsonElement groups) {
        if (groups instanceof JsonPrimitive p) return p.isString() && p.getAsString().equals(group);
        if (groups instanceof JsonArray array) {
            for (JsonElement e : array) {
                if (e instanceof JsonPrimitive p && p.isString() && p.getAsString().equals(group)) return true;
            }
        }
        return false;
    }
}
