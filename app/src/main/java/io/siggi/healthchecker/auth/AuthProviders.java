package io.siggi.healthchecker.auth;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import io.siggi.healthchecker.config.ServerConfig;
import io.siggi.healthchecker.config.SiteConfig;
import io.siggi.healthchecker.config.UnknownHostsPolicy;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The configured OIDC / OAuth 2.0 providers ({@code auth.providers}).
 * <ul>
 *   <li>Common keys: {@code id}, {@code type} ({@code oidc} or {@code oauth2}), {@code label}, {@code clientId},
 *       {@code clientSecret}, {@code scopes}, {@code sites}.</li>
 *   <li>{@code oidc}: {@code issuer}, {@code identifyBy} ({@code sub} by default, {@code email} or another
 *       claim name).</li>
 *   <li>{@code oauth2}: {@code authorizeUrl}, {@code tokenUrl}, {@code userinfoUrl}, {@code idField},
 *       {@code nameField}, {@code pkce}.</li>
 * </ul>
 * Every site a provider is offered on needs a {@code publicBaseUrl} (its own or {@code server.publicBaseUrl}),
 * since the callback URL is built from it.
 */
public final class AuthProviders {
    /** Provider ids: like user ids, so they never contain the identifier's {@code :}. */
    static final Pattern ID = UserDirectory.NAME;
    /** Claim and field names: printable, no whitespace (claims may be URLs). */
    private static final Pattern FIELD = Pattern.compile("[\\x21-\\x7e]{1,256}");
    private static final Pattern SCOPE = Pattern.compile("[\\x21\\x23-\\x5b\\x5d-\\x7e]{1,256}");
    private static final int MAX_LABEL = 100;
    private static final List<String> OIDC_DEFAULT_SCOPES = List.of("openid", "email", "profile");

    private static final Set<String> COMMON_KEYS = Set.of("id", "type", "label", "clientId", "clientSecret",
            "scopes", "sites");
    private static final Set<String> OIDC_KEYS = Set.of("issuer", "identifyBy");
    private static final Set<String> OAUTH2_KEYS = Set.of("authorizeUrl", "tokenUrl", "userinfoUrl", "idField",
            "nameField", "pkce");

    private final Map<String, ProviderConfig> providers;

    private AuthProviders(Collection<ProviderConfig> providers) {
        Map<String, ProviderConfig> byId = new LinkedHashMap<>();
        for (ProviderConfig p : providers) byId.putIfAbsent(p.id(), p);
        this.providers = Collections.unmodifiableMap(byId);
    }

    public static AuthProviders of(Collection<ProviderConfig> providers) {
        return new AuthProviders(providers);
    }

    /** The provider with this id. */
    public Optional<ProviderConfig> provider(String id) {
        return Optional.ofNullable(providers.get(id));
    }

    /** All providers in config order. */
    public Collection<ProviderConfig> providers() {
        return providers.values();
    }

    /** The providers offered on this site ({@code null} = the default site), in config order. */
    public List<ProviderConfig> availableOn(SiteConfig site) {
        return providers.values().stream().filter(p -> p.availableOn(site)).toList();
    }

    public boolean isEmpty() {
        return providers.isEmpty();
    }

    // ---- configuration ----

    /** {@link #parse(JsonObject, ServerConfig, List, List, List)} that throws on the first problem. */
    public static AuthProviders parse(JsonObject auth, ServerConfig server, List<SiteConfig> sites) {
        List<String> errors = new ArrayList<>();
        AuthProviders providers = parse(auth, server, sites, errors, new ArrayList<>());
        if (!errors.isEmpty()) throw new IllegalArgumentException(String.join("; ", errors));
        return providers;
    }

    /**
     * Reads and validates {@code auth.providers}.
     *
     * @param auth     the raw {@code auth} section, or {@code null}
     * @param server   for {@code server.publicBaseUrl} and {@code unknownHosts}
     * @param sites    the configured sites, for {@code sites} references and callback base URLs
     * @param errors   receives one message per problem, prefixed with its path
     * @param warnings receives problems that don't prevent startup (a provider no user or rule uses)
     */
    public static AuthProviders parse(JsonObject auth, ServerConfig server, List<SiteConfig> sites,
                                      List<String> errors, List<String> warnings) {
        List<ProviderConfig> out = new ArrayList<>();
        JsonElement list = auth == null ? null : auth.get("providers");
        if (list == null || list.isJsonNull()) return new AuthProviders(out);
        if (!list.isJsonArray()) {
            errors.add("auth.providers must be an array");
            return new AuthProviders(out);
        }
        Map<String, String> siteHosts = new LinkedHashMap<>();
        for (SiteConfig site : sites) {
            for (String host : site.hosts()) siteHosts.putIfAbsent(host.toLowerCase(Locale.ROOT), host);
        }
        Set<String> used = usedProviders(auth);
        Set<String> ids = new LinkedHashSet<>();
        JsonArray array = list.getAsJsonArray();
        for (int i = 0; i < array.size(); i++) {
            String path = "auth.providers[" + i + "]";
            if (!array.get(i).isJsonObject()) {
                errors.add(path + " must be an object");
                continue;
            }
            JsonObject object = array.get(i).getAsJsonObject();
            String id = string(object, "id");
            if (id == null || !ID.matcher(id).matches()) {
                errors.add(path + ": id must match " + ID.pattern());
                continue;
            }
            path += " \"" + id + "\"";
            int before = errors.size();
            if (!ids.add(id)) errors.add(path + ": duplicate provider id");

            String typeName = string(object, "type");
            Optional<ProviderConfig.Type> type = typeName == null ? Optional.empty()
                    : ProviderConfig.Type.byId(typeName);
            if (type.isEmpty()) {
                errors.add(path + ": type must be \"oidc\" or \"oauth2\"");
                continue;
            }
            boolean oidc = type.get() == ProviderConfig.Type.OIDC;
            for (String key : object.keySet()) {
                if (!COMMON_KEYS.contains(key) && !(oidc ? OIDC_KEYS : OAUTH2_KEYS).contains(key)) {
                    errors.add(path + ": unknown key \"" + key + "\" for type \"" + typeName + "\"");
                }
            }

            String label = id;
            if (present(object, "label")) {
                label = string(object, "label");
                if (label == null || label.isBlank() || label.length() > MAX_LABEL) {
                    errors.add(path + ": label must be a non-empty string of at most " + MAX_LABEL + " characters");
                    label = id;
                }
            }
            String clientId = string(object, "clientId");
            if (clientId == null || clientId.isEmpty()) errors.add(path + ": clientId must be a non-empty string");
            String clientSecret = null;
            if (present(object, "clientSecret")) {
                clientSecret = string(object, "clientSecret");
                if (clientSecret == null || clientSecret.isEmpty()) {
                    errors.add(path + ": clientSecret must be a non-empty string (leave it out for a public client)");
                }
            }

            List<String> scopes = oidc ? OIDC_DEFAULT_SCOPES : List.of();
            if (present(object, "scopes")) {
                scopes = strings(object.get("scopes"), SCOPE);
                if (scopes == null) {
                    errors.add(path + ": scopes must be an array of scope names (no spaces or quotes)");
                    scopes = List.of();
                } else if (oidc && !scopes.contains("openid")) {
                    errors.add(path + ": scopes must include \"openid\"");
                }
            }

            List<String> restricted = List.of();
            if (present(object, "sites")) {
                List<String> hosts = strings(object.get("sites"), null);
                if (hosts == null || hosts.isEmpty()) {
                    errors.add(path + ": sites must be a non-empty array of site hosts (leave it out for every site)");
                } else {
                    List<String> lower = new ArrayList<>();
                    for (int j = 0; j < hosts.size(); j++) {
                        String host = hosts.get(j).toLowerCase(Locale.ROOT);
                        if (!siteHosts.containsKey(host)) {
                            errors.add(path + ": sites[" + j + "]: \"" + hosts.get(j)
                                    + "\" is not a host of any configured site"
                                    + (siteHosts.isEmpty() ? " (no sites configured)"
                                    : " (available: " + String.join(", ", siteHosts.values()) + ")"));
                        } else if (!lower.contains(host)) {
                            lower.add(host);
                        }
                    }
                    restricted = lower;
                }
            }

            String issuer = null, identifyBy = null;
            String authorizeUrl = null, tokenUrl = null, userinfoUrl = null, idField = null, nameField = null;
            boolean pkce = true;
            if (oidc) {
                issuer = url(object, "issuer", path, errors, false);
                identifyBy = ProviderConfig.SUB;
                if (present(object, "identifyBy")) {
                    identifyBy = field(object, "identifyBy", path, errors);
                }
            } else {
                authorizeUrl = url(object, "authorizeUrl", path, errors, true);
                tokenUrl = url(object, "tokenUrl", path, errors, true);
                userinfoUrl = url(object, "userinfoUrl", path, errors, true);
                idField = field(object, "idField", path, errors);
                if (present(object, "nameField")) nameField = field(object, "nameField", path, errors);
                if (present(object, "pkce")) {
                    if (object.get("pkce") instanceof JsonPrimitive p && p.isBoolean()) {
                        pkce = p.getAsBoolean();
                    } else {
                        errors.add(path + ": pkce must be true or false");
                    }
                }
            }

            ProviderConfig provider = new ProviderConfig(id, type.get(), label, clientId, clientSecret, scopes,
                    restricted, issuer, identifyBy, authorizeUrl, tokenUrl, userinfoUrl, idField, nameField, pkce);
            checkBaseUrls(provider, path, server, sites, errors);
            if (errors.size() == before) {
                out.add(provider);
                if (!used.contains(id)) {
                    warnings.add(path + ": no user identity or rule uses this provider, so everyone signing in"
                            + " with it is shown their identifier to send to the administrator");
                }
            }
        }
        return new AuthProviders(out);
    }

    /** Callback URLs come from the site's {@code publicBaseUrl}, else {@code server.publicBaseUrl}. */
    private static void checkBaseUrls(ProviderConfig provider, String path, ServerConfig server,
                                      List<SiteConfig> sites, List<String> errors) {
        String fallback = server == null ? null : server.publicBaseUrl();
        for (int i = 0; i < sites.size(); i++) {
            SiteConfig site = sites.get(i);
            if (provider.availableOn(site) && site.publicBaseUrl() == null && fallback == null) {
                errors.add(path + ": sites[" + i + "] (" + String.join(", ", site.hosts()) + ") needs a"
                        + " publicBaseUrl (or server.publicBaseUrl) for the sign-in callback URL");
            }
        }
        boolean defaultSiteServed = server == null || server.unknownHosts() != UnknownHostsPolicy.REJECT;
        if (defaultSiteServed && provider.availableOn(null) && fallback == null) {
            errors.add(path + ": server.publicBaseUrl is required for the sign-in callback URL");
        }
    }

    /** Provider ids referenced by a user identity or a rule's {@code provider} (rules are validated separately). */
    private static Set<String> usedProviders(JsonObject auth) {
        Set<String> used = new LinkedHashSet<>();
        if (auth.get("users") instanceof JsonArray users) {
            for (JsonElement user : users) {
                if (user instanceof JsonObject u && u.get("identities") instanceof JsonArray identities) {
                    for (JsonElement identity : identities) {
                        if (identity instanceof JsonPrimitive p && p.isString()) {
                            int colon = p.getAsString().indexOf(':');
                            if (colon > 0) used.add(p.getAsString().substring(0, colon));
                        }
                    }
                }
            }
        }
        if (auth.get("rules") instanceof JsonArray rules) {
            for (JsonElement rule : rules) {
                if (rule instanceof JsonObject r) {
                    String provider = string(r, "provider");
                    if (provider != null) used.add(provider);
                }
            }
        }
        return used;
    }

    private static String url(JsonObject object, String key, String path, List<String> errors, boolean allowQuery) {
        String value = string(object, key);
        if (value == null || value.isEmpty()) {
            errors.add(path + ": " + key + " must be a URL");
            return null;
        }
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost();
            if (!uri.isAbsolute() || host == null || host.isEmpty()
                    || !(scheme.equals("https") || scheme.equals("http"))) {
                errors.add(path + ": " + key + " must be an absolute https URL");
                return null;
            }
            if (scheme.equals("http") && !isLoopback(host)) {
                errors.add(path + ": " + key + " must use https (http is only allowed for localhost)");
                return null;
            }
            if (uri.getRawUserInfo() != null) {
                errors.add(path + ": " + key + " must not contain credentials");
                return null;
            }
            if (uri.getRawFragment() != null || (!allowQuery && uri.getRawQuery() != null)) {
                errors.add(path + ": " + key + " must not have a " + (allowQuery ? "fragment" : "query or fragment"));
                return null;
            }
        } catch (URISyntaxException e) {
            errors.add(path + ": " + key + " is not a valid URL");
            return null;
        }
        return value;
    }

    /** {@code localhost} or a loopback IP literal (never resolved through DNS). */
    static boolean isLoopback(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        if (h.equals("localhost") || h.endsWith(".localhost")) return true;
        if (h.equals("[::1]") || h.equals("[0:0:0:0:0:0:0:1]")) return true;
        return LOOPBACK_V4.matcher(h).matches();
    }

    private static final Pattern LOOPBACK_V4 =
            Pattern.compile("127(\\.(25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])){3}");

    private static String field(JsonObject object, String key, String path, List<String> errors) {
        String value = string(object, key);
        if (value == null || !FIELD.matcher(value).matches()) {
            errors.add(path + ": " + key + " must be a claim or field name (no spaces)");
            return null;
        }
        return value;
    }

    /** The array's strings (each matching {@code pattern}, or non-empty when it's {@code null}), deduplicated. */
    private static List<String> strings(JsonElement element, Pattern pattern) {
        if (!(element instanceof JsonArray array)) return null;
        List<String> out = new ArrayList<>();
        for (JsonElement e : array) {
            if (!(e instanceof JsonPrimitive p && p.isString())) return null;
            String s = p.getAsString();
            if (pattern == null ? s.isEmpty() : !pattern.matcher(s).matches()) return null;
            if (!out.contains(s)) out.add(s);
        }
        return out;
    }

    private static boolean present(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && !value.isJsonNull();
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value instanceof JsonPrimitive p && p.isString() ? p.getAsString() : null;
    }
}
