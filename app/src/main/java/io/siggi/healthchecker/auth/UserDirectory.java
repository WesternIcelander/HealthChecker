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

    private final Map<String, Role> roles;
    private final Map<String, User> users;
    private final Map<String, User> byIdentity;

    private UserDirectory(Map<String, Role> roles, Collection<User> users) {
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
        Set<String> providers = providerIds(auth.get("providers"));
        JsonElement list = auth.get("users");
        if (list == null || list.isJsonNull()) return new UserDirectory(roles, users);
        if (!list.isJsonArray()) {
            errors.add("auth.users must be an array");
            return new UserDirectory(roles, users);
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
        return new UserDirectory(roles, users);
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

    /** The {@code id} of every provider object in {@code auth.providers}; the providers themselves are validated with M13. */
    private static Set<String> providerIds(JsonElement element) {
        Set<String> ids = new java.util.LinkedHashSet<>();
        if (element == null || !element.isJsonArray()) return ids;
        for (JsonElement provider : element.getAsJsonArray()) {
            if (provider.isJsonObject()) {
                String id = string(provider.getAsJsonObject(), "id");
                if (id != null && !id.isEmpty()) ids.add(id);
            }
        }
        return ids;
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value instanceof JsonPrimitive p && p.isString() ? p.getAsString() : null;
    }
}
