package io.siggi.healthchecker.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Loads and validates {@code config.json}. All errors are collected and thrown together
 * in one {@link ConfigLoadException}, each prefixed with its path in the config.
 *
 * <p>Checks that need other components (endpoint types known to the plugin manager, the
 * plugins' own settings, users/providers, ids also defined in {@code data/endpoints.json})
 * are done by those components.
 */
public final class ConfigLoader {
    static final Pattern ID = Pattern.compile("[a-z0-9-]+");
    private static final Pattern HOST_LABEL = Pattern.compile("[a-z0-9]([a-z0-9-]*[a-z0-9])?");

    /** Endpoint keys handled by the app; every other key is a type-specific setting. */
    public static final Set<String> COMMON_ENDPOINT_KEYS = Set.of(
            "id", "name", "type", "category", "visibility", "notify", "reminders",
            "reminderRepeatMinutes", "intervalSeconds", "timeoutMillis", "failureThreshold");

    private static final int MAX_INTERVAL_SECONDS = 86_400;
    private static final int MIN_TIMEOUT_MILLIS = 100;
    private static final int MAX_TIMEOUT_MILLIS = 300_000;
    private static final int MAX_FAILURE_THRESHOLD = 100;
    private static final int MAX_REMINDER_MINUTES = 525_600; // one year

    private final Function<String, String> env;
    private final ZoneId systemZone;

    /** A loader that reads the process environment and defaults to the system time zone. */
    public ConfigLoader() {
        this(System::getenv, ZoneId.systemDefault());
    }

    /**
     * @param env        looks up environment variables for {@code ${env:NAME}}; returns {@code null} if unset
     * @param systemZone the time zone used when {@code timezone} isn't set
     */
    public ConfigLoader(Function<String, String> env, ZoneId systemZone) {
        this.env = env;
        this.systemZone = systemZone;
    }

    public Config load(Path file) throws ConfigLoadException {
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            throw new ConfigLoadException(List.of(file + ": file not found"), List.of());
        } catch (IOException e) {
            throw new ConfigLoadException(List.of(file + ": cannot read: " + e.getMessage()), List.of());
        }
        return load(new StringReader(text));
    }

    public Config load(String json) throws ConfigLoadException {
        return load(new StringReader(json));
    }

    public Config load(Reader reader) throws ConfigLoadException {
        JsonElement root;
        try {
            JsonReader jr = new JsonReader(reader);
            jr.setStrictness(Strictness.STRICT);
            root = JsonParser.parseReader(jr);
            if (jr.peek() != JsonToken.END_DOCUMENT) {
                throw new JsonParseException("unexpected data after the top-level object");
            }
        } catch (JsonParseException | IOException e) {
            String msg = e.getCause() != null && e.getCause().getMessage() != null
                    ? e.getCause().getMessage() : e.getMessage();
            throw new ConfigLoadException(List.of("invalid JSON: " + msg), List.of());
        }
        if (!root.isJsonObject()) {
            throw new ConfigLoadException(List.of("the config must be a JSON object"), List.of());
        }
        Problems problems = new Problems();
        JsonObject substituted = new EnvSubstitution(env, problems).apply(root, "").getAsJsonObject();
        Config config = new Parser(new Section(substituted, "", problems), problems).parse();
        if (!problems.errors.isEmpty()) {
            throw new ConfigLoadException(problems.errors, problems.warnings);
        }
        return config;
    }

    private final class Parser {
        private final Section root;
        private final Problems problems;

        Parser(Section root, Problems problems) {
            this.root = root;
            this.problems = problems;
        }

        Config parse() {
            ServerConfig server = parseServer(root.object("server"));
            Path dataDir = parsePath("dataDir", "data");
            ZoneId timezone = parseTimezone();
            Path pluginsDir = parsePlugins(root.object("plugins"));
            DashboardConfig dashboard = parseDashboard(root.object("dashboard"));
            MaintenanceSettings maintenance = parseMaintenance(root.object("maintenance"));
            JsonObject auth = rawObject("auth");
            JsonObject notifications = rawObject("notifications");
            Set<String> channels = channelNames(notifications);
            EndpointDefaults defaults = parseDefaults(root.object("defaults"), channels);
            List<CategoryConfig> categories = parseCategories(root.array("categories"));
            List<EndpointDefinition> endpoints = parseEndpoints(root.array("endpoints"), defaults, categories, channels);
            List<SiteConfig> sites = parseSites(root.array("sites"), categories, endpoints);
            warnEmptyCategories(categories, endpoints);
            root.rejectUnknownKeys();
            return new Config(server, dataDir, timezone, pluginsDir, dashboard, maintenance, defaults,
                    sites, categories, endpoints, channels, auth, notifications, problems.warnings);
        }

        // ---- server --------------------------------------------------------------------

        private ServerConfig parseServer(Section s) {
            if (s == null) {
                return new ServerConfig(8080, "0.0.0.0", null, List.of(), UnknownHostsPolicy.DEFAULT);
            }
            int port = s.integer("port", 8080, 1, 65535);
            String bind = s.string("bind", "0.0.0.0");
            if (bind.isBlank()) {
                s.error("bind", "must not be empty");
                bind = "0.0.0.0";
            }
            String publicBaseUrl = baseUrl(s, "publicBaseUrl");
            List<String> proxies = s.stringList("trustedProxies");
            if (proxies != null) {
                for (int i = 0; i < proxies.size(); i++) {
                    try {
                        IpRange.parse(proxies.get(i));
                    } catch (IllegalArgumentException e) {
                        s.problems.error(s.path("trustedProxies") + "[" + i + "]", e.getMessage());
                    }
                }
            }
            String unknownHostsName = s.string("unknownHosts", "default");
            UnknownHostsPolicy unknownHosts = switch (unknownHostsName) {
                case "default" -> UnknownHostsPolicy.DEFAULT;
                case "reject" -> UnknownHostsPolicy.REJECT;
                default -> {
                    s.error("unknownHosts", "must be \"default\" or \"reject\"");
                    yield UnknownHostsPolicy.DEFAULT;
                }
            };
            s.rejectUnknownKeys();
            return new ServerConfig(port, bind, publicBaseUrl, proxies == null ? List.of() : proxies, unknownHosts);
        }

        /** An absolute http(s) URL with a host and no query or fragment, without a trailing slash. */
        private String baseUrl(Section s, String key) {
            String value = s.string(key, null);
            if (value == null) return null;
            try {
                URI uri = new URI(value);
                String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
                if (!scheme.equals("http") && !scheme.equals("https")) {
                    s.error(key, "must be an http or https URL");
                    return null;
                }
                if (uri.getHost() == null || uri.getHost().isEmpty()) {
                    s.error(key, "must include a host");
                    return null;
                }
                if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
                    s.error(key, "must not have a query or fragment");
                    return null;
                }
            } catch (URISyntaxException e) {
                s.error(key, "is not a valid URL");
                return null;
            }
            while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
            return value;
        }

        // ---- simple top-level values -----------------------------------------------------

        private Path parsePath(String key, String def) {
            String value = root.string(key, def);
            if (value.isBlank()) {
                root.error(key, "must not be empty");
                value = def;
            }
            try {
                return Path.of(value);
            } catch (RuntimeException e) {
                root.error(key, "is not a valid path");
                return Path.of(def);
            }
        }

        private ZoneId parseTimezone() {
            String name = root.string("timezone", null);
            if (name == null) return systemZone;
            if (!ZoneId.getAvailableZoneIds().contains(name)) {
                root.error("timezone", "unknown time zone \"" + name + "\" (use an IANA name such as \"Europe/Berlin\")");
                return systemZone;
            }
            return ZoneId.of(name);
        }

        private Path parsePlugins(Section s) {
            if (s == null) return Path.of("plugins");
            String dir = s.string("dir", "plugins");
            if (dir.isBlank()) {
                s.error("dir", "must not be empty");
                dir = "plugins";
            }
            s.rejectUnknownKeys();
            return Path.of(dir);
        }

        private DashboardConfig parseDashboard(Section s) {
            if (s == null) return new DashboardConfig("Status", 100.0, 99.0, 95.0);
            String title = s.string("title", "Status");
            double yellow = 100.0, orange = 99.0, red = 95.0;
            Section colors = s.object("colors");
            if (colors != null) {
                yellow = colors.number("yellowBelow", yellow, 0, 100);
                orange = colors.number("orangeBelow", orange, 0, 100);
                red = colors.number("redBelow", red, 0, 100);
                if (!(yellow >= orange && orange >= red)) {
                    problems.error(colors.path, "thresholds must satisfy yellowBelow ≥ orangeBelow ≥ redBelow");
                }
                colors.rejectUnknownKeys();
            }
            s.rejectUnknownKeys();
            return new DashboardConfig(title, yellow, orange, red);
        }

        private MaintenanceSettings parseMaintenance(Section s) {
            if (s == null) return new MaintenanceSettings(false, 7, 7);
            boolean counts = s.bool("countsAsDowntime", false);
            int maxLengthDays = s.integer("maxLengthDays", 7, 1, 366);
            int upcomingDays = s.integer("upcomingDays", 7, 1, 366);
            s.rejectUnknownKeys();
            return new MaintenanceSettings(counts, maxLengthDays, upcomingDays);
        }

        private JsonObject rawObject(String key) {
            Section s = root.object(key);
            return s == null ? new JsonObject() : s.obj;
        }

        private Set<String> channelNames(JsonObject notifications) {
            JsonElement channels = notifications.get("channels");
            if (channels == null || channels.isJsonNull()) return Set.of();
            if (!channels.isJsonObject()) {
                problems.error("notifications.channels", "must be an object");
                return Set.of();
            }
            return new LinkedHashSet<>(channels.getAsJsonObject().keySet());
        }

        // ---- defaults ------------------------------------------------------------------

        private EndpointDefaults parseDefaults(Section s, Set<String> channels) {
            if (s == null) {
                return new EndpointDefaults(30, 5000, 1, Visibility.PRIVATE, List.of(), List.of(30, 60, 120), 0);
            }
            int interval = s.integer("intervalSeconds", 30, 1, MAX_INTERVAL_SECONDS);
            int timeout = s.integer("timeoutMillis", 5000, MIN_TIMEOUT_MILLIS, MAX_TIMEOUT_MILLIS);
            int threshold = s.integer("failureThreshold", 1, 1, MAX_FAILURE_THRESHOLD);
            Visibility visibility = visibility(s, Visibility.PRIVATE);
            List<String> notify = notify(s, channels);
            List<Integer> reminders = reminders(s);
            int repeat = s.integer("reminderRepeatMinutes", 0, 0, MAX_REMINDER_MINUTES);
            s.rejectUnknownKeys();
            return new EndpointDefaults(interval, timeout, threshold, visibility,
                    notify == null ? List.of() : notify,
                    reminders == null ? List.of(30, 60, 120) : reminders, repeat);
        }

        private Visibility visibility(Section s, Visibility def) {
            String name = s.string("visibility", null);
            if (name == null) return def;
            return Visibility.fromConfigName(name).orElseGet(() -> {
                s.error("visibility", "must be one of " + Visibility.allNames());
                return def;
            });
        }

        /** {@code notify}: {@code false}, or an array of channel names. {@code null} if absent or invalid. */
        private List<String> notify(Section s, Set<String> channels) {
            JsonElement e = s.raw("notify");
            if (e == null) return null;
            if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean()) {
                if (!e.getAsBoolean()) return List.of();
                s.error("notify", "must be false or an array of channel names");
                return null;
            }
            List<String> names = s.stringList("notify");
            if (names == null) return null;
            List<String> out = new ArrayList<>();
            boolean ok = true;
            for (String name : names) {
                if (!channels.contains(name)) {
                    s.error("notify", "unknown notification channel \"" + name + "\" (configured: "
                            + (channels.isEmpty() ? "none" : String.join(", ", new TreeSet<>(channels))) + ")");
                    ok = false;
                } else if (!out.contains(name)) {
                    out.add(name);
                }
            }
            return ok ? out : null;
        }

        /** {@code reminders}: strictly increasing positive integers. {@code null} if absent or invalid. */
        private List<Integer> reminders(Section s) {
            JsonElement e = s.raw("reminders");
            if (e == null) return null;
            if (!e.isJsonArray()) {
                s.error("reminders", "must be an array of minutes");
                return null;
            }
            JsonArray arr = e.getAsJsonArray();
            List<Integer> out = new ArrayList<>();
            for (int i = 0; i < arr.size(); i++) {
                Integer v = Section.asInteger(arr.get(i));
                if (v == null || v <= 0 || v > MAX_REMINDER_MINUTES) {
                    s.error("reminders", "must contain positive whole numbers of minutes (up to "
                            + MAX_REMINDER_MINUTES + "), got " + arr.get(i));
                    return null;
                }
                if (!out.isEmpty() && v <= out.getLast()) {
                    s.error("reminders", "must be strictly increasing (" + v + " after " + out.getLast() + ")");
                    return null;
                }
                out.add(v);
            }
            return out;
        }

        // ---- categories ----------------------------------------------------------------

        private List<CategoryConfig> parseCategories(JsonArray arr) {
            List<CategoryConfig> out = new ArrayList<>();
            if (arr == null) return out;
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < arr.size(); i++) {
                String basePath = "categories[" + i + "]";
                if (!arr.get(i).isJsonObject()) {
                    problems.error(basePath, "must be an object");
                    continue;
                }
                JsonObject obj = arr.get(i).getAsJsonObject();
                Section s = new Section(obj, labelled(basePath, obj), problems);
                String id = id(s);
                String name = s.requiredString("name");
                String description = s.string("description", null);
                s.rejectUnknownKeys();
                if (id != null && !seen.add(id)) {
                    s.error("id", "duplicate category id \"" + id + "\"");
                    continue;
                }
                if (id != null && name != null) {
                    out.add(new CategoryConfig(id, name, description));
                }
            }
            return out;
        }

        private String id(Section s) {
            String id = s.requiredString("id");
            if (id != null && !ID.matcher(id).matches()) {
                s.error("id", "\"" + id + "\" must match [a-z0-9-]+");
                return null;
            }
            return id;
        }

        private String labelled(String basePath, JsonObject obj) {
            JsonElement id = obj.get("id");
            return id != null && Section.isString(id) ? basePath + " \"" + id.getAsString() + "\"" : basePath;
        }

        // ---- endpoints -----------------------------------------------------------------

        private List<EndpointDefinition> parseEndpoints(JsonArray arr, EndpointDefaults d,
                                                        List<CategoryConfig> categories, Set<String> channels) {
            List<EndpointDefinition> out = new ArrayList<>();
            if (arr == null) return out;
            Set<String> categoryIds = new HashSet<>();
            for (CategoryConfig c : categories) categoryIds.add(c.id());
            Set<String> ids = new HashSet<>();
            Map<String, String> names = new HashMap<>();
            for (int i = 0; i < arr.size(); i++) {
                String basePath = "endpoints[" + i + "]";
                if (!arr.get(i).isJsonObject()) {
                    problems.error(basePath, "must be an object");
                    continue;
                }
                JsonObject obj = arr.get(i).getAsJsonObject();
                Section s = new Section(obj, labelled(basePath, obj), problems);
                int errorsBefore = problems.errors.size();

                String id = id(s);
                if (id != null && !ids.add(id)) {
                    s.error("id", "duplicate endpoint id \"" + id + "\"");
                }
                String name = s.requiredString("name");
                if (name != null) {
                    String previous = names.putIfAbsent(name, s.path);
                    if (previous != null) {
                        s.error("name", "duplicate endpoint name \"" + name + "\" (also used by " + previous + ")");
                    }
                }
                String type = s.requiredString("type");
                if (type != null && !ID.matcher(type).matches()) {
                    s.error("type", "\"" + type + "\" must match [a-z0-9-]+");
                }
                String category = s.string("category", null);
                if (category != null && !categoryIds.contains(category)) {
                    s.error("category", "unknown category \"" + category + "\" (configured: "
                            + (categoryIds.isEmpty() ? "none" : String.join(", ", new TreeSet<>(categoryIds))) + ")");
                }
                Visibility visibility = visibility(s, d.visibility());
                int interval = s.integer("intervalSeconds", d.intervalSeconds(), 1, MAX_INTERVAL_SECONDS);
                int timeout = s.integer("timeoutMillis", d.timeoutMillis(), MIN_TIMEOUT_MILLIS, MAX_TIMEOUT_MILLIS);
                int threshold = s.integer("failureThreshold", d.failureThreshold(), 1, MAX_FAILURE_THRESHOLD);
                List<String> notify = notify(s, channels);
                List<Integer> reminders = reminders(s);
                int repeat = s.integer("reminderRepeatMinutes", d.reminderRepeatMinutes(), 0, MAX_REMINDER_MINUTES);

                JsonObject settings = new JsonObject();
                for (var entry : obj.entrySet()) {
                    if (!COMMON_ENDPOINT_KEYS.contains(entry.getKey())) {
                        settings.add(entry.getKey(), entry.getValue().deepCopy());
                    }
                }
                if (problems.errors.size() == errorsBefore) {
                    out.add(new EndpointDefinition(id, name, type, category, visibility, interval, timeout,
                            threshold, notify == null ? d.notifyChannels() : notify,
                            reminders == null ? d.reminders() : reminders, repeat, settings));
                }
            }
            return out;
        }

        private void warnEmptyCategories(List<CategoryConfig> categories, List<EndpointDefinition> endpoints) {
            Set<String> used = new HashSet<>();
            for (EndpointDefinition e : endpoints) {
                if (e.category() != null) used.add(e.category());
            }
            for (int i = 0; i < categories.size(); i++) {
                CategoryConfig c = categories.get(i);
                if (!used.contains(c.id())) {
                    problems.warn("categories", "category \"" + c.id() + "\" has no endpoints in config.json");
                }
            }
        }

        // ---- sites ---------------------------------------------------------------------

        private List<SiteConfig> parseSites(JsonArray arr, List<CategoryConfig> categories,
                                            List<EndpointDefinition> endpoints) {
            List<SiteConfig> out = new ArrayList<>();
            if (arr == null) return out;
            Set<String> categoryIds = new HashSet<>();
            for (CategoryConfig c : categories) categoryIds.add(c.id());
            Set<String> endpointIds = new HashSet<>();
            for (EndpointDefinition e : endpoints) endpointIds.add(e.id());
            Map<String, String> hostOwner = new HashMap<>();
            for (int i = 0; i < arr.size(); i++) {
                String path = "sites[" + i + "]";
                if (!arr.get(i).isJsonObject()) {
                    problems.error(path, "must be an object");
                    continue;
                }
                Section s = new Section(arr.get(i).getAsJsonObject(), path, problems);
                List<String> hosts = new ArrayList<>();
                List<String> rawHosts = s.stringList("hosts");
                if (!s.has("hosts")) {
                    s.error("hosts", "is required");
                } else if (rawHosts != null && rawHosts.isEmpty()) {
                    s.error("hosts", "must not be empty");
                } else if (rawHosts != null) {
                    for (String raw : rawHosts) {
                        String host = raw.toLowerCase(Locale.ROOT);
                        if (!validHostPattern(host)) {
                            s.error("hosts", "\"" + raw + "\" is not a valid host name (wildcards only as a leading \"*.\")");
                            continue;
                        }
                        String owner = hostOwner.putIfAbsent(host, path);
                        if (owner != null) {
                            s.error("hosts", "\"" + host + "\" is also listed in " + (owner.equals(path) ? "this site" : owner));
                            continue;
                        }
                        hosts.add(host);
                    }
                }
                String title = s.string("title", null);
                String publicBaseUrl = baseUrl(s, "publicBaseUrl");
                List<String> cats = s.stringList("categories");
                List<String> eps = s.stringList("endpoints");
                for (String c : cats == null ? List.<String>of() : cats) {
                    if (!categoryIds.contains(c)) s.problems.warn(s.path("categories"), "unknown category \"" + c + "\"");
                }
                for (String e : eps == null ? List.<String>of() : eps) {
                    if (!endpointIds.contains(e)) s.problems.warn(s.path("endpoints"), "endpoint \"" + e + "\" is not in config.json");
                }
                s.rejectUnknownKeys();
                out.add(new SiteConfig(hosts, title, publicBaseUrl,
                        cats == null ? List.of() : cats, eps == null ? List.of() : eps));
            }
            warnWildcardOverlaps(hostOwner);
            return out;
        }

        private static boolean validHostPattern(String host) {
            String rest = host.startsWith("*.") ? host.substring(2) : host;
            if (rest.isEmpty() || rest.length() > 253) return false;
            for (String label : rest.split("\\.", -1)) {
                if (label.length() > 63 || !HOST_LABEL.matcher(label).matches()) return false;
            }
            return true;
        }

        private void warnWildcardOverlaps(Map<String, String> hostOwner) {
            for (var wildcard : hostOwner.entrySet()) {
                if (!wildcard.getKey().startsWith("*.")) continue;
                String suffix = wildcard.getKey().substring(1); // ".example.com"
                for (var other : hostOwner.entrySet()) {
                    if (other.getValue().equals(wildcard.getValue())) continue;
                    String otherHost = other.getKey().startsWith("*.") ? other.getKey().substring(2) : other.getKey();
                    if (otherHost.endsWith(suffix)) {
                        problems.warn(other.getValue() + ".hosts", "\"" + other.getKey() + "\" overlaps with \""
                                + wildcard.getKey() + "\" in " + wildcard.getValue() + "; the first matching site wins");
                    }
                }
            }
        }
    }
}
