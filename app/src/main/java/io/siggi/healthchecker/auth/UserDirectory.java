package io.siggi.healthchecker.auth;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The configured roles and users ({@code auth.roles}, {@code auth.users}).
 * <ul>
 *   <li>Roles: the built-in {@code viewer}, {@code editor} and {@code admin} (which can't be
 *       redefined) plus custom roles, each a list of permission names. Unknown permission names
 *       are errors.</li>
 *   <li>Users: unique ids, a known role, an optional password hash, identifiers
 *       ({@code providerId:value}) of configured providers that belong to one user only, and
 *       {@code disabled}.</li>
 * </ul>
 * Users are looked up by id on every request ({@link Permissions}), so sessions only store the id.
 */
public final class UserDirectory {
    /** Role names and user ids. */
    static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");
    private static final Set<String> USER_KEYS = Set.of("id", "displayName", "role", "password", "identities",
            "disabled");

    private static final Set<String> RULE_KEYS = Set.of("provider", "role", "emailDomain", "group");
    private static final Pattern DOMAIN = Pattern.compile(
            "(?i)[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+");
    private static final int MAX_GROUP = 256;

    private final Map<String, Role> roles;
    private final Map<String, User> users;
    private final Map<String, User> byIdentity;
    private final List<Rule> rules;

    private UserDirectory(Map<String, Role> roles, Collection<User> users) {
        this(roles, users, List.of());
    }

    private UserDirectory(Map<String, Role> roles, Collection<User> users, List<Rule> rules) {
        this.rules = List.copyOf(rules);
        this.roles = Collections.unmodifiableMap(new LinkedHashMap<>(roles));
        Map<String, User> byId = new LinkedHashMap<>();
        Map<String, User> identities = new LinkedHashMap<>();
        for (User user : users) {
            byId.putIfAbsent(user.id(), user);
            for (String identity : user.identities()) identities.putIfAbsent(identity, user);
        }
        this.users = Collections.unmodifiableMap(byId);
        this.byIdentity = Collections.unmodifiableMap(identities);
    }

    /** A directory of these users (with the built-in roles plus each user's role). */
    public static UserDirectory of(Collection<User> users) {
        Map<String, Role> roles = new LinkedHashMap<>(Role.BUILT_IN);
        for (User user : users) roles.putIfAbsent(user.role().name(), user.role());
        return new UserDirectory(roles, users);
    }

    /** A directory of these users and rules (with the built-in roles plus each user's and rule's role). */
    public static UserDirectory of(Collection<User> users, List<Rule> rules) {
        Map<String, Role> roles = new LinkedHashMap<>(Role.BUILT_IN);
        for (User user : users) roles.putIfAbsent(user.role().name(), user.role());
        for (Rule rule : rules) roles.putIfAbsent(rule.role().name(), rule.role());
        return new UserDirectory(roles, users, rules);
    }

    /** The access rules in config order. */
    public List<Rule> rules() {
        return rules;
    }

    /**
     * Who a completed OIDC/OAuth sign-in belongs to: the identifier ({@link ProviderConfig#identify}) is looked up
     * in the users' {@code identities} first (an explicit user always wins, also when disabled), then the rules are
     * tried in order and the first match gives the role.
     *
     * @param attributes the verified {@code id_token} claims ({@code oidc}) or the userinfo response ({@code oauth2})
     */
    public SignInMatch resolve(ProviderConfig provider, JsonObject attributes) {
        Optional<String> found = provider.identify(attributes);
        if (found.isEmpty()) return new SignInMatch.NoIdentifier();
        String identifier = found.get();
        User user = byIdentity.get(identifier);
        if (user != null) {
            return user.disabled() ? new SignInMatch.Disabled(identifier) : new SignInMatch.Allowed(user, null);
        }
        for (Rule rule : rules) {
            if (rule.matches(provider.id(), attributes)) {
                String name = provider.nameHint(attributes).orElse(identifier);
                return new SignInMatch.Allowed(ruleUser(identifier, name, rule), rule);
            }
        }
        return new SignInMatch.Unknown(identifier);
    }

    private static User ruleUser(String identifier, String displayName, Rule rule) {
        return new User(identifier, displayName, rule.role(), null, List.of(identifier), false);
    }

    /**
     * The user of a session a rule granted, looked up again on each request: empty (so the session ends) when the
     * identifier now belongs to a configured user (who must sign in again as themselves) or no rule with that
     * {@link Rule#key()} exists any more.
     *
     * @param identifier  the session's user id, which for a rule is the identifier
     * @param ruleKey     the granting rule's key
     * @param displayName the name stored at sign-in, or {@code null}
     */
    public Optional<User> ruleUser(String identifier, String ruleKey, String displayName) {
        if (identifier == null || ruleKey == null || byIdentity.containsKey(identifier)) return Optional.empty();
        int colon = identifier.indexOf(':');
        if (colon <= 0) return Optional.empty();
        String provider = identifier.substring(0, colon);
        for (Rule rule : rules) {
            if (rule.provider().equals(provider) && rule.key().equals(ruleKey)) {
                return Optional.of(ruleUser(identifier, displayName == null ? identifier : displayName, rule));
            }
        }
        return Optional.empty();
    }

    /** The user with this id (disabled ones included). */
    public Optional<User> user(String id) {
        return Optional.ofNullable(users.get(id));
    }

    /** The user an OIDC/OAuth identifier ({@code providerId:value}) belongs to. */
    public Optional<User> byIdentity(String identifier) {
        return Optional.ofNullable(byIdentity.get(identifier));
    }

    /** All users in config order. */
    public Collection<User> users() {
        return users.values();
    }

    /** Built-in and custom roles by name. */
    public Map<String, Role> roles() {
        return roles;
    }

    /** Password hashes for {@link LocalLogin}: only users that have one and aren't disabled. */
    public LocalLogin.Credentials credentials() {
        return username -> user(username).filter(u -> !u.disabled()).map(User::passwordHash);
    }

    // ---- configuration ----

    /** {@link #parse(JsonObject, List)} that throws on the first problem. */
    public static UserDirectory parse(JsonObject auth) {
        List<String> errors = new ArrayList<>();
        UserDirectory directory = parse(auth, errors);
        if (!errors.isEmpty()) throw new IllegalArgumentException(String.join("; ", errors));
        return directory;
    }

    /**
     * Reads {@code auth.roles} and {@code auth.users}.
     *
     * @param auth   the raw {@code auth} section, or {@code null}
     * @param errors receives one message per problem, prefixed with its path
     */
    public static UserDirectory parse(JsonObject auth, List<String> errors) {
        Map<String, Role> roles = new LinkedHashMap<>(Role.BUILT_IN);
        List<User> users = new ArrayList<>();
        if (auth == null) return new UserDirectory(roles, users);
        parseRoles(auth.get("roles"), roles, errors);
        Map<String, String> providerTypes = providerTypes(auth.get("providers"));
        Set<String> providers = providerTypes.keySet();
        parseUsers(auth.get("users"), roles, providers, users, errors);
        List<Rule> rules = parseRules(auth.get("rules"), roles, providerTypes, errors);
        return new UserDirectory(roles, users, rules);
    }

    private static void parseUsers(JsonElement list, Map<String, Role> roles, Set<String> providers,
                                   List<User> users, List<String> errors) {
        if (list == null || list.isJsonNull()) return;
        if (!list.isJsonArray()) {
            errors.add("auth.users must be an array");
            return;
        }
        Set<String> ids = new HashSet<>();
        Map<String, String> identityOwners = new LinkedHashMap<>();
        JsonArray array = list.getAsJsonArray();
        for (int i = 0; i < array.size(); i++) {
            String path = "auth.users[" + i + "]";
            if (!array.get(i).isJsonObject()) {
                errors.add(path + " must be an object");
                continue;
            }
            JsonObject object = array.get(i).getAsJsonObject();
            String id = string(object, "id");
            if (id == null || !NAME.matcher(id).matches()) {
                errors.add(path + ": id must match " + NAME.pattern());
                continue;
            }
            path += " \"" + id + "\"";
            int before = errors.size();
            for (String key : object.keySet()) {
                if (!USER_KEYS.contains(key)) errors.add(path + ": unknown key \"" + key + "\"");
            }
            if (!ids.add(id)) errors.add(path + ": duplicate user id");

            String displayName = id;
            JsonElement name = object.get("displayName");
            if (name != null && !name.isJsonNull()) {
                displayName = string(object, "displayName");
                if (displayName == null || displayName.isBlank()) {
                    errors.add(path + ": displayName must be a non-empty string");
                    displayName = id;
                }
            }

            Role role = null;
            String roleName = string(object, "role");
            if (roleName == null) {
                errors.add(path + ": role must be a string");
            } else {
                role = roles.get(roleName);
                if (role == null) {
                    errors.add(path + ": unknown role \"" + roleName + "\" (available: "
                            + String.join(", ", roles.keySet()) + ")");
                }
            }

            String hash = null;
            JsonElement password = object.get("password");
            if (password != null && !password.isJsonNull()) {
                hash = string(object, "password");
                if (hash == null) {
                    errors.add(path + ": password must be a string");
                } else {
                    try {
                        PasswordHasher.parse(hash);
                    } catch (IllegalArgumentException e) {
                        errors.add(path + ": " + e.getMessage());
                        hash = null;
                    }
                }
            }

            List<String> identities = new ArrayList<>();
            JsonElement idList = object.get("identities");
            if (idList != null && !idList.isJsonNull()) {
                if (!idList.isJsonArray()) {
                    errors.add(path + ": identities must be an array of strings");
                } else {
                    JsonArray entries = idList.getAsJsonArray();
                    for (int j = 0; j < entries.size(); j++) {
                        String ipath = path + ": identities[" + j + "]";
                        String identity = entries.get(j) instanceof JsonPrimitive p && p.isString()
                                ? p.getAsString() : null;
                        int colon = identity == null ? -1 : identity.indexOf(':');
                        if (colon <= 0 || colon == identity.length() - 1) {
                            errors.add(ipath + " must be a string \"providerId:value\"");
                            continue;
                        }
                        String provider = identity.substring(0, colon);
                        if (!providers.contains(provider)) {
                            errors.add(ipath + ": unknown provider \"" + provider + "\""
                                    + (providers.isEmpty() ? " (no providers configured)"
                                    : " (available: " + String.join(", ", providers) + ")"));
                            continue;
                        }
                        String owner = identityOwners.putIfAbsent(identity, id);
                        if (owner != null) {
                            errors.add(ipath + ": \"" + identity + "\" already belongs to user \"" + owner + "\"");
                            continue;
                        }
                        identities.add(identity);
                    }
                }
            }

            boolean disabled = false;
            JsonElement flag = object.get("disabled");
            if (flag != null && !flag.isJsonNull()) {
                if (flag instanceof JsonPrimitive p && p.isBoolean()) {
                    disabled = p.getAsBoolean();
                } else {
                    errors.add(path + ": disabled must be true or false");
                }
            }
            if (errors.size() == before) {
                users.add(new User(id, displayName, role, hash, identities, disabled));
            }
        }
    }

    /**
     * {@code auth.rules}: {@code provider} (a configured provider), {@code role} (a known role) and at least one
     * condition, {@code emailDomain} (a domain name; {@code oidc} providers only) and/or {@code group}.
     */
    private static List<Rule> parseRules(JsonElement list, Map<String, Role> roles, Map<String, String> providers,
                                         List<String> errors) {
        List<Rule> rules = new ArrayList<>();
        if (list == null || list.isJsonNull()) return rules;
        if (!list.isJsonArray()) {
            errors.add("auth.rules must be an array");
            return rules;
        }
        JsonArray array = list.getAsJsonArray();
        for (int i = 0; i < array.size(); i++) {
            String path = "auth.rules[" + i + "]";
            if (!array.get(i).isJsonObject()) {
                errors.add(path + " must be an object");
                continue;
            }
            JsonObject object = array.get(i).getAsJsonObject();
            int before = errors.size();
            for (String key : object.keySet()) {
                if (!RULE_KEYS.contains(key)) errors.add(path + ": unknown key \"" + key + "\"");
            }
            String provider = string(object, "provider");
            if (provider == null) {
                errors.add(path + ": provider must be a string");
            } else if (!providers.containsKey(provider)) {
                errors.add(path + ": unknown provider \"" + provider + "\""
                        + (providers.isEmpty() ? " (no providers configured)"
                        : " (available: " + String.join(", ", providers.keySet()) + ")"));
                provider = null;
            }
            Role role = null;
            String roleName = string(object, "role");
            if (roleName == null) {
                errors.add(path + ": role must be a string");
            } else {
                role = roles.get(roleName);
                if (role == null) {
                    errors.add(path + ": unknown role \"" + roleName + "\" (available: "
                            + String.join(", ", roles.keySet()) + ")");
                }
            }
            String domain = null;
            if (present(object, "emailDomain")) {
                domain = string(object, "emailDomain");
                if (domain == null || domain.length() > 253 || !DOMAIN.matcher(domain).matches()) {
                    errors.add(path + ": emailDomain must be a domain name such as \"example.com\"");
                    domain = null;
                } else if (provider != null && !"oidc".equals(providers.get(provider))) {
                    errors.add(path + ": emailDomain needs an oidc provider (only OIDC says whether an email"
                            + " address is verified)");
                }
            }
            String group = null;
            if (present(object, "group")) {
                group = string(object, "group");
                if (group == null || group.isEmpty() || group.length() > MAX_GROUP) {
                    errors.add(path + ": group must be a non-empty string of at most " + MAX_GROUP + " characters");
                    group = null;
                }
            }
            if (!present(object, "emailDomain") && !present(object, "group")) {
                errors.add(path + ": needs a condition, emailDomain and/or group (a rule never admits everyone"
                        + " with an account at the provider)");
            }
            if (errors.size() == before) rules.add(new Rule(provider, domain, group, role));
        }
        return rules;
    }

    private static boolean present(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && !value.isJsonNull();
    }

    private static void parseRoles(JsonElement element, Map<String, Role> roles, List<String> errors) {
        if (element == null || element.isJsonNull()) return;
        if (!element.isJsonObject()) {
            errors.add("auth.roles must be an object");
            return;
        }
        for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
            String name = entry.getKey();
            String path = "auth.roles.\"" + name + "\"";
            if (Role.BUILT_IN.containsKey(name)) {
                errors.add(path + ": the built-in role \"" + name + "\" can't be redefined");
                continue;
            }
            if (!NAME.matcher(name).matches()) {
                errors.add(path + ": role name must match " + NAME.pattern());
                continue;
            }
            if (!entry.getValue().isJsonArray()) {
                errors.add(path + " must be an array of permission names");
                continue;
            }
            EnumSet<Permission> permissions = EnumSet.noneOf(Permission.class);
            boolean ok = true;
            JsonArray list = entry.getValue().getAsJsonArray();
            for (int i = 0; i < list.size(); i++) {
                String id = list.get(i) instanceof JsonPrimitive p && p.isString() ? p.getAsString() : null;
                Optional<Permission> permission = id == null ? Optional.empty() : Permission.byId(id);
                if (permission.isEmpty()) {
                    errors.add(path + "[" + i + "]: unknown permission "
                            + (id == null ? list.get(i).toString() : "\"" + id + "\"")
                            + " (known: " + Permission.knownIds() + ")");
                    ok = false;
                } else {
                    permissions.add(permission.get());
                }
            }
            // A role with errors is still defined (so its users don't get a second "unknown role" error).
            roles.put(name, new Role(name, ok ? permissions : EnumSet.noneOf(Permission.class)));
        }
    }

    /**
     * The {@code id} → {@code type} of every provider object in {@code auth.providers} (validated by
     * {@link AuthProviders}, which reports its own errors).
     */
    private static Map<String, String> providerTypes(JsonElement element) {
        Map<String, String> ids = new LinkedHashMap<>();
        if (element == null || !element.isJsonArray()) return ids;
        for (JsonElement provider : element.getAsJsonArray()) {
            if (provider.isJsonObject()) {
                String id = string(provider.getAsJsonObject(), "id");
                if (id != null && !id.isEmpty()) ids.putIfAbsent(id, string(provider.getAsJsonObject(), "type"));
            }
        }
        return ids;
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value instanceof JsonPrimitive p && p.isString() ? p.getAsString() : null;
    }
}
