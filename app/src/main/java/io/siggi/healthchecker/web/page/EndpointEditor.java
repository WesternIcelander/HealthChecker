package io.siggi.healthchecker.web.page;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import io.siggi.healthchecker.api.CheckPlugin;
import io.siggi.healthchecker.api.SettingSpec;
import io.siggi.healthchecker.auth.Permission;
import io.siggi.healthchecker.auth.Permissions;
import io.siggi.healthchecker.auth.SessionManager;
import io.siggi.healthchecker.config.CategoryConfig;
import io.siggi.healthchecker.config.Config;
import io.siggi.healthchecker.config.ConfigLoader;
import io.siggi.healthchecker.config.EndpointDefaults;
import io.siggi.healthchecker.config.EndpointDefinition;
import io.siggi.healthchecker.config.EndpointStore;
import io.siggi.healthchecker.config.Visibility;
import io.siggi.healthchecker.plugin.PluginManager;
import io.siggi.healthchecker.web.Html;
import io.siggi.healthchecker.web.WebServer;
import io.siggi.healthchecker.web.site.Site;
import io.siggi.healthchecker.web.site.SiteResolver;
import io.siggi.healthchecker.web.view.Viewer;
import io.siggi.http.HTTPRequest;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The endpoint editor, for users with {@code endpoints.manage} (and {@code view.private}, since it
 * shows every endpoint with its targets):
 * <ul>
 *   <li>{@code GET /admin/endpoints}: every endpoint of both sources, with a "config" badge for
 *       config-defined ones and the errors of disabled ones.</li>
 *   <li>{@code GET /admin/endpoints/new} and {@code POST /admin/endpoints/new}: the "Add service" form.</li>
 *   <li>{@code GET /admin/endpoints/{id}} and {@code POST /admin/endpoints/{id}}: the edit form of a
 *       dashboard endpoint, or a read-only view (with a "config" badge) of a config-defined one, whose
 *       POST is refused with {@code 403}.</li>
 * </ul>
 * The form has the common fields, a type dropdown ({@link CheckPlugin#displayName()}) and the
 * type-specific fields generated from the plugin's {@link CheckPlugin#settings()}. With JS, changing
 * the type swaps in that type's fields (from a {@code <template>}); without it, the form is two steps:
 * the "Continue" button ({@code action=type}) shows the form again for the chosen type, keeping what was
 * entered. Saving ({@code action=save}) goes through {@link EndpointStore#putEndpoint}, i.e. the same
 * validation as config loading including the plugin's {@code create()}; errors are shown next to their
 * fields (by setting key) and nothing is saved until everything is valid.
 */
public final class EndpointEditor {
    public static final String PATH = "/admin/endpoints";

    private static final Pattern ID = Pattern.compile("[a-z0-9-]+");
    /** A {@code key: message} error (also {@code key[0]: …} and {@code key.sub: …}). */
    private static final Pattern KEYED_ERROR = Pattern.compile("([A-Za-z0-9_-]+)(?:[\\[.][^:]*)?: (.*)", Pattern.DOTALL);
    private static final String SETTING_PREFIX = "s.";
    private static final String NOTIFY_PREFIX = "notify.";
    private static final List<String> NUMBER_FIELDS =
            List.of("intervalSeconds", "timeoutMillis", "failureThreshold", "reminderRepeatMinutes");

    private final SiteResolver sites;
    private final Permissions permissions;
    private final SessionManager sessions;
    private final EndpointStore store;
    private final PluginManager plugins;
    private final Config config;

    public EndpointEditor(SiteResolver sites, Permissions permissions, SessionManager sessions, EndpointStore store,
                          PluginManager plugins, Config config) {
        this.sites = sites;
        this.permissions = permissions;
        this.sessions = sessions;
        this.store = store;
        this.plugins = plugins;
        this.config = config;
    }

    public void register(WebServer server) {
        server.register(PATH, this::handle);
        server.register(PATH + "/", this::handle);
    }

    /** Whether the request's user may use the editor (for showing the links to it). */
    public boolean canManage(HTTPRequest request) {
        return permissions.user(request)
                .map(u -> u.has(Permission.ENDPOINTS_MANAGE) && u.has(Permission.VIEW_PRIVATE)).orElse(false);
    }

    private boolean requireAccess(HTTPRequest request, Site site) throws Exception {
        if (!permissions.require(request, site, Permission.ENDPOINTS_MANAGE)) return false;
        if (permissions.has(request, Permission.VIEW_PRIVATE)) return true;
        PageSupport.write(request, "403 Forbidden", "text/plain; charset=utf-8",
                "Managing services also needs the view.private permission, because the editor shows every service "
                        + "and its targets.\n");
        return false;
    }

    // ---- routing ----

    private void handle(HTTPRequest request) throws Exception {
        String path = request.path();
        String rest;
        if (path.equals(PATH)) {
            rest = null;
        } else if (path.startsWith(PATH + "/")) {
            rest = path.substring(PATH.length() + 1);
            if (rest.isEmpty() || rest.contains("/")) return; // nothing written → 404
            if (!rest.equals("new") && !ID.matcher(rest).matches()) return;
        } else {
            return;
        }
        Optional<Site> found = sites.resolve(request);
        if (found.isEmpty()) return;
        Site site = found.get();
        if (rest == null) {
            request.allowedMethods("GET");
            if (!requireAccess(request, site)) return;
            listPage(request, site);
            return;
        }
        request.allowedMethods("GET", "POST");
        if (!requireAccess(request, site)) return;
        boolean post = request.method().equals("POST");
        if (rest.equals("new")) {
            if (post) {
                if (!sessions.requireCsrf(request)) return;
                submit(request, site, null);
            } else {
                Form form = Form.empty();
                String type = request.get().get("type");
                if (type != null && typeInfo(type).isPresent()) form.fields.put("type", type);
                formPage(request, site, "200 OK", null, form, Errors.none(), null);
            }
            return;
        }
        Optional<EndpointStore.StoredEndpoint> stored = store.endpoint(rest);
        if (stored.isEmpty()) return; // nothing written → 404
        if (stored.get().source() == EndpointStore.Source.CONFIG) {
            if (post) {
                PageSupport.write(request, "403 Forbidden", "text/plain; charset=utf-8",
                        "This service is defined in config.json and can't be edited in the dashboard.\n");
                return;
            }
            readOnlyPage(request, site, stored.get());
            return;
        }
        if (post) {
            if (!sessions.requireCsrf(request)) return;
            submit(request, site, stored.get());
            return;
        }
        Form form = Form.fromEntry(stored.get().entry());
        Errors errors = Errors.none();
        if (!stored.get().enabled()) {
            errors = Errors.of(stored.get().errors(), knownKeys(form.get("type")));
            errors.disabled = true;
        }
        String notice = "1".equals(request.get().get("saved")) ? "Saved." : null;
        formPage(request, site, "200 OK", stored.get(), form, errors, notice);
    }

    // ---- POST ----

    private void submit(HTTPRequest request, Site site, EndpointStore.StoredEndpoint existing) throws Exception {
        Map<String, String> post = request.post();
        Form form = Form.fromPost(post == null ? Map.of() : post);
        if (existing != null) form.fields.put("id", existing.id());
        String action = post == null ? null : post.get("action");
        if (!"save".equals(action)) {
            // Step one of the no-JS form (or "Change type"): show the form for the chosen type.
            formPage(request, site, "200 OK", existing, form, Errors.none(), null);
            return;
        }
        Errors errors = new Errors();
        JsonObject entry = toEntry(form, errors);
        String id = form.get("id").strip();
        if (existing == null && !id.isEmpty() && store.endpoint(id).isPresent()) {
            errors.field("id", "\"" + id + "\" is already in use");
        }
        if (!errors.isEmpty()) {
            formPage(request, site, "400 Bad Request", existing, form, errors, null);
            return;
        }
        int version = parseVersion(form.get("version"));
        try {
            store.putEndpoint(version, entry);
        } catch (EndpointStore.InvalidEntryException e) {
            formPage(request, site, "400 Bad Request", existing, form, Errors.of(e.errors(), knownKeys(form.get("type"))), null);
            return;
        } catch (EndpointStore.VersionConflictException e) {
            Errors conflict = new Errors();
            conflict.general.add("Someone else changed the services in the meantime (now version " + e.currentVersion()
                    + "). Check the values below and save again.");
            form.fields.put("version", Integer.toString(e.currentVersion()));
            formPage(request, site, "409 Conflict", existing, form, conflict, null);
            return;
        } catch (IOException e) {
            System.getLogger(EndpointEditor.class.getName()).log(System.Logger.Level.ERROR,
                    "Saving endpoint " + id + " failed", e);
            PageSupport.write(request, "500 Internal Server Error", "text/plain; charset=utf-8",
                    "Saving failed. Try again later.\n");
            return;
        }
        request.response.setHeader("303 See Other");
        request.response.setHeader("Location", site.link(PATH + "/" + id + "?saved=1"));
        request.response.setHeader("Cache-Control", "no-store");
        request.response.setHeader("Content-Length", "0");
        request.response.write(new byte[0]);
    }

    private static int parseVersion(String value) {
        try {
            int v = Integer.parseInt(value.strip());
            return v >= 0 ? v : -1;
        } catch (NumberFormatException e) {
            return -1; // never the current version → 409
        }
    }

    /** The JSON entry for the form's values; problems that stop it from being built go into {@code errors}. */
    JsonObject toEntry(Form form, Errors errors) {
        JsonObject entry = new JsonObject();
        for (String key : List.of("id", "name", "type", "category", "visibility")) {
            String value = form.get(key).strip();
            if (!value.isEmpty()) entry.addProperty(key, value);
        }
        for (String key : NUMBER_FIELDS) {
            String value = form.get(key).strip();
            if (value.isEmpty()) continue;
            Long number = parseLong(value);
            if (number == null) {
                errors.field(key, "must be a whole number");
            } else {
                entry.addProperty(key, number);
            }
        }
        switch (form.get("notifyMode")) {
            case "none" -> entry.addProperty("notify", false);
            case "channels" -> {
                JsonArray channels = new JsonArray();
                for (String channel : new TreeSet<>(config.notificationChannels())) {
                    if (form.notify.contains(channel)) channels.add(channel);
                }
                entry.add("notify", channels);
            }
            default -> {
                // use the defaults
            }
        }
        String reminders = form.get("reminders").strip();
        if (!reminders.isEmpty()) {
            JsonArray minutes = new JsonArray();
            if (!reminders.equalsIgnoreCase("none")) {
                for (String part : reminders.split("[,\\s]+")) {
                    if (part.isEmpty()) continue;
                    Long number = parseLong(part);
                    if (number == null) {
                        errors.field("reminders", "must be whole numbers of minutes separated by commas, or \"none\"");
                        minutes = null;
                        break;
                    }
                    minutes.add(number);
                }
            }
            if (minutes != null) entry.add("reminders", minutes);
        }
        Optional<TypeInfo> type = typeInfo(form.get("type").strip());
        if (type.isPresent()) {
            for (SettingSpec spec : type.get().settings()) {
                String raw = form.settings.getOrDefault(spec.key(), "");
                switch (spec.kind()) {
                    case STRING, CHOICE -> {
                        String value = raw.strip();
                        if (!value.isEmpty()) entry.addProperty(spec.key(), value);
                    }
                    case INTEGER -> {
                        String value = raw.strip();
                        if (value.isEmpty()) break;
                        Long number = parseLong(value);
                        if (number == null) {
                            errors.field(spec.key(), "must be a whole number");
                        } else {
                            entry.addProperty(spec.key(), number);
                        }
                    }
                    case BOOLEAN -> {
                        switch (raw.strip()) {
                            case "" -> {
                            }
                            case "true" -> entry.addProperty(spec.key(), true);
                            case "false" -> entry.addProperty(spec.key(), false);
                            default -> errors.field(spec.key(), "must be yes or no");
                        }
                    }
                    case STRING_LIST -> {
                        JsonArray values = new JsonArray();
                        for (String line : raw.split("\\R")) {
                            String value = line.strip();
                            if (!value.isEmpty()) values.add(value);
                        }
                        if (!values.isEmpty()) entry.add(spec.key(), values);
                    }
                }
            }
        }
        return entry;
    }

    private static Long parseLong(String value) {
        if (!value.matches("-?[0-9]{1,18}")) return null;
        return Long.parseLong(value);
    }

    // ---- plugins ----

    /** A loaded type as the form shows it. */
    record TypeInfo(String type, String displayName, List<SettingSpec> settings) {
    }

    /** Every loaded type, in load order (built-ins first). A plugin whose methods fail gets no settings. */
    List<TypeInfo> types() {
        List<TypeInfo> out = new ArrayList<>();
        for (PluginManager.LoadedPlugin loaded : plugins.plugins()) {
            CheckPlugin plugin = loaded.plugin();
            String displayName;
            try {
                displayName = plugin.displayName();
            } catch (RuntimeException | LinkageError e) {
                displayName = null;
            }
            if (displayName == null || displayName.isBlank()) displayName = loaded.type();
            List<SettingSpec> settings = new ArrayList<>();
            try {
                Set<String> seen = new HashSet<>();
                List<SettingSpec> specs = plugin.settings();
                for (SettingSpec spec : specs == null ? List.<SettingSpec>of() : specs) {
                    // The app handles the common keys itself; a plugin can't describe them.
                    if (spec == null || ConfigLoader.COMMON_ENDPOINT_KEYS.contains(spec.key()) || !seen.add(spec.key())) {
                        continue;
                    }
                    settings.add(spec);
                }
            } catch (RuntimeException | LinkageError e) {
                System.getLogger(EndpointEditor.class.getName()).log(System.Logger.Level.WARNING,
                        "settings() of plugin type \"" + loaded.type() + "\" failed", e);
                settings.clear();
            }
            out.add(new TypeInfo(loaded.type(), displayName, List.copyOf(settings)));
        }
        return out;
    }

    private Optional<TypeInfo> typeInfo(String type) {
        if (type == null || type.isEmpty()) return Optional.empty();
        return types().stream().filter(t -> t.type().equals(type)).findFirst();
    }

    /** The keys the form shows a field for: the common ones plus the type's settings. */
    private Set<String> knownKeys(String type) {
        Set<String> keys = new HashSet<>(ConfigLoader.COMMON_ENDPOINT_KEYS);
        typeInfo(type).ifPresent(t -> t.settings().forEach(s -> keys.add(s.key())));
        return keys;
    }

    // ---- form state ----

    /** The form's raw values, as entered (or as stored). */
    static final class Form {
        /** Common fields by their JSON key, plus {@code notifyMode} and {@code version}. */
        final Map<String, String> fields = new LinkedHashMap<>();
        /** The channels ticked when {@code notifyMode} is {@code channels}. */
        final Set<String> notify = new LinkedHashSet<>();
        /** Type-specific settings by key, as entered. */
        final Map<String, String> settings = new LinkedHashMap<>();
        /** The type whose setting fields were submitted; other types' fields start from their defaults. */
        String settingsFor = "";

        String get(String key) {
            return fields.getOrDefault(key, "");
        }

        static Form empty() {
            Form form = new Form();
            form.fields.put("notifyMode", "default");
            return form;
        }

        static Form fromPost(Map<String, String> post) {
            Form form = new Form();
            for (Map.Entry<String, String> e : post.entrySet()) {
                String key = e.getKey();
                String value = e.getValue() == null ? "" : e.getValue();
                if (key.startsWith(SETTING_PREFIX)) {
                    form.settings.put(key.substring(SETTING_PREFIX.length()), value);
                } else if (key.startsWith(NOTIFY_PREFIX)) {
                    form.notify.add(key.substring(NOTIFY_PREFIX.length()));
                } else if (key.equals("settingsFor")) {
                    form.settingsFor = value;
                } else if (ConfigLoader.COMMON_ENDPOINT_KEYS.contains(key) || key.equals("notifyMode")
                        || key.equals("version")) {
                    form.fields.put(key, value);
                }
            }
            form.fields.putIfAbsent("notifyMode", "default");
            return form;
        }

        /** The form for a stored dashboard entry: exactly what it sets, so blank fields keep using the defaults. */
        static Form fromEntry(JsonObject entry) {
            Form form = new Form();
            for (Map.Entry<String, JsonElement> e : entry.entrySet()) {
                String key = e.getKey();
                JsonElement value = e.getValue();
                switch (key) {
                    case "notify" -> {
                        if (value instanceof JsonPrimitive p && p.isBoolean() && !p.getAsBoolean()) {
                            form.fields.put("notifyMode", "none");
                        } else if (value.isJsonArray()) {
                            form.fields.put("notifyMode", "channels");
                            for (JsonElement channel : value.getAsJsonArray()) form.notify.add(text(channel));
                        }
                    }
                    case "reminders" -> form.fields.put(key, value.isJsonArray() && value.getAsJsonArray().isEmpty()
                            ? "none" : list(value, ", "));
                    default -> {
                        if (ConfigLoader.COMMON_ENDPOINT_KEYS.contains(key)) {
                            form.fields.put(key, text(value));
                        } else {
                            form.settings.put(key, list(value, "\n"));
                        }
                    }
                }
            }
            form.fields.putIfAbsent("notifyMode", "default");
            form.settingsFor = form.get("type");
            return form;
        }

        /** The read-only form of a config-defined endpoint: its effective values (defaults applied). */
        static Form fromDefinition(EndpointDefinition d) {
            Form form = new Form();
            form.fields.put("id", d.id());
            form.fields.put("name", d.name());
            form.fields.put("type", d.type());
            if (d.category() != null) form.fields.put("category", d.category());
            form.fields.put("visibility", d.visibility().configName());
            form.fields.put("intervalSeconds", Integer.toString(d.intervalSeconds()));
            form.fields.put("timeoutMillis", Integer.toString(d.timeoutMillis()));
            form.fields.put("failureThreshold", Integer.toString(d.failureThreshold()));
            form.fields.put("reminderRepeatMinutes", Integer.toString(d.reminderRepeatMinutes()));
            form.fields.put("notifyMode", d.notifyChannels().isEmpty() ? "none" : "channels");
            form.notify.addAll(d.notifyChannels());
            form.fields.put("reminders", d.reminders().isEmpty() ? "none"
                    : d.reminders().stream().map(String::valueOf).collect(Collectors.joining(", ")));
            for (Map.Entry<String, JsonElement> e : d.settings().entrySet()) {
                form.settings.put(e.getKey(), list(e.getValue(), "\n"));
            }
            form.settingsFor = d.type();
            return form;
        }

        private static String text(JsonElement value) {
            if (value == null || value.isJsonNull()) return "";
            if (value instanceof JsonPrimitive p) return p.getAsString();
            return value.toString();
        }

        private static String list(JsonElement value, String separator) {
            if (value == null || !value.isJsonArray()) return text(value);
            List<String> parts = new ArrayList<>();
            for (JsonElement e : value.getAsJsonArray()) parts.add(text(e));
            return String.join(separator, parts);
        }
    }

    /** Errors to show: per field (by JSON/setting key) and for the whole form. */
    static final class Errors {
        final Map<String, List<String>> fields = new LinkedHashMap<>();
        final List<String> general = new ArrayList<>();
        /** Whether these are the stored errors of a disabled endpoint. */
        boolean disabled;

        static Errors none() {
            return new Errors();
        }

        /** Sorts {@code key: message} errors onto the fields in {@code keys}; the rest are general. */
        static Errors of(List<String> messages, Set<String> keys) {
            Errors errors = new Errors();
            for (String message : messages) {
                Matcher m = KEYED_ERROR.matcher(message);
                if (m.matches() && keys.contains(m.group(1))) {
                    errors.field(m.group(1), m.group(2));
                } else {
                    errors.general.add(message);
                }
            }
            return errors;
        }

        void field(String key, String message) {
            fields.computeIfAbsent(key, k -> new ArrayList<>()).add(message);
        }

        boolean isEmpty() {
            return fields.isEmpty() && general.isEmpty();
        }
    }

    // ---- HTML ----

    private static void secureHeaders(HTTPRequest request) {
        request.response.setHeader("X-Frame-Options", "DENY");
        request.response.setHeader("Content-Security-Policy", "frame-ancestors 'none'");
    }

    private void listPage(HTTPRequest request, Site site) throws Exception {
        Viewer viewer = permissions.viewer(request);
        StringBuilder html = new StringBuilder(8192);
        PageSupport.head(html, site, "Services · " + site.title(), false, viewer);
        html.append("<p class=\"crumbs\"><a href=\"").append(Html.escape(site.link("/"))).append("\">All services</a></p>\n")
                .append("<h2>Services</h2>\n<p class=\"page-actions\"><a class=\"button\" href=\"")
                .append(Html.escape(site.link(PATH + "/new"))).append("\">Add service</a></p>\n")
                .append("<ul class=\"ee-list\">\n");
        for (EndpointStore.StoredEndpoint e : store.endpoints()) {
            String name = e.enabled() ? e.definition().name() : nameOf(e);
            String type = e.enabled() ? e.definition().type() : typeOf(e);
            html.append("<li class=\"ee-item\" data-service=\"").append(Html.escape(e.id())).append("\"><a href=\"")
                    .append(Html.escape(site.link(PATH + "/" + e.id()))).append("\">").append(Html.escape(name))
                    .append("</a> <code>").append(Html.escape(e.id())).append("</code>");
            if (type != null) html.append(" <span class=\"muted\">").append(Html.escape(type)).append("</span>");
            if (e.source() == EndpointStore.Source.CONFIG) configBadge(html);
            if (!e.enabled()) {
                html.append(" <span class=\"badge badge-disabled\">disabled</span><ul class=\"form-error-list\">");
                for (String error : e.errors()) html.append("<li>").append(Html.escape(error)).append("</li>");
                html.append("</ul>");
            }
            html.append("</li>\n");
        }
        html.append("</ul>\n");
        PageSupport.foot(html, site);
        secureHeaders(request);
        PageSupport.write(request, "200 OK", "text/html; charset=utf-8", html.toString());
    }

    private static String nameOf(EndpointStore.StoredEndpoint e) {
        JsonObject entry = e.entry();
        return entry != null && entry.get("name") instanceof JsonPrimitive p && p.isString() ? p.getAsString() : e.id();
    }

    private static String typeOf(EndpointStore.StoredEndpoint e) {
        JsonObject entry = e.entry();
        return entry != null && entry.get("type") instanceof JsonPrimitive p && p.isString() ? p.getAsString() : null;
    }

    private static void configBadge(StringBuilder html) {
        html.append(" <span class=\"badge badge-config\" title=\"Defined in config.json\">config</span>");
    }

    private void readOnlyPage(HTTPRequest request, Site site, EndpointStore.StoredEndpoint stored) throws Exception {
        Viewer viewer = permissions.viewer(request);
        Form form = Form.fromDefinition(stored.definition());
        StringBuilder html = new StringBuilder(16384);
        PageSupport.head(html, site, stored.definition().name() + " · " + site.title(), false, viewer);
        crumbs(html, site);
        html.append("<h2 class=\"ee-title\">").append(Html.escape(stored.definition().name()));
        configBadge(html);
        html.append("</h2>\n<p class=\"muted\">This service is defined in <code>config.json</code>, so it can't be "
                + "edited here. Change it there and restart. Its position on the main page can still be changed "
                + "with the layout editor.</p>\n")
                .append("<form class=\"endpoint-form ee-readonly\"><fieldset class=\"ee-all\" disabled>\n");
        fields(html, form, Errors.none(), false, true);
        html.append("</fieldset></form>\n");
        PageSupport.foot(html, site);
        secureHeaders(request);
        PageSupport.write(request, "200 OK", "text/html; charset=utf-8", html.toString());
    }

    private static void crumbs(StringBuilder html, Site site) {
        html.append("<p class=\"crumbs\"><a href=\"").append(Html.escape(site.link("/"))).append("\">All services</a> · ")
                .append("<a href=\"").append(Html.escape(site.link(PATH))).append("\">Manage services</a></p>\n");
    }

    /**
     * The add or edit form.
     *
     * @param existing the dashboard endpoint being edited, or {@code null} when adding one
     */
    private void formPage(HTTPRequest request, Site site, String status, EndpointStore.StoredEndpoint existing,
                          Form form, Errors errors, String notice) throws Exception {
        Viewer viewer = permissions.viewer(request);
        boolean create = existing == null;
        String title = create ? "Add service" : "Edit " + (existing.enabled() ? existing.definition().name() : nameOf(existing));
        form.fields.putIfAbsent("version", Integer.toString(store.version()));
        StringBuilder html = new StringBuilder(16384);
        PageSupport.head(html, site, title + " · " + site.title(), false, viewer);
        crumbs(html, site);
        html.append("<h2 class=\"ee-title\">").append(Html.escape(title)).append("</h2>\n");
        if (notice != null) {
            html.append("<p class=\"ee-notice\" role=\"status\">").append(Html.escape(notice)).append("</p>\n");
        }
        if (errors.disabled) {
            html.append("<p class=\"form-error\">This service is disabled and isn't checked, because its saved settings "
                    + "aren't valid. Fix them and save to enable it.</p>\n");
        } else if (!errors.isEmpty()) {
            html.append("<p class=\"form-error\" role=\"alert\">Nothing was saved. Please fix the problems marked below.</p>\n");
        }
        if (!errors.general.isEmpty()) {
            html.append("<ul class=\"form-error-list ee-general-errors\">");
            for (String error : errors.general) html.append("<li>").append(Html.escape(error)).append("</li>");
            html.append("</ul>\n");
        }
        String action = site.link(PATH + "/" + (create ? "new" : existing.id()));
        html.append("<form class=\"endpoint-form\" method=\"post\" action=\"").append(Html.escape(action)).append("\">")
                .append("<input type=\"hidden\" name=\"").append(SessionManager.CSRF_FIELD).append("\" value=\"")
                .append(Html.escape(viewer.csrfToken() == null ? "" : viewer.csrfToken())).append("\">")
                .append("<input type=\"hidden\" name=\"version\" value=\"").append(Html.escape(form.get("version")))
                .append("\">\n");
        boolean typeChosen = fields(html, form, errors, create, false);
        html.append("<div class=\"ee-actions\"><button type=\"submit\" name=\"action\" value=\"save\" class=\"button button-primary ee-save\"")
                .append(typeChosen ? "" : " hidden").append(">Save</button>")
                .append("<a class=\"button\" href=\"").append(Html.escape(site.link(PATH))).append("\">Cancel</a></div>\n")
                .append("</form>\n");
        // With JS, changing the type swaps in that type's fields from these templates.
        for (TypeInfo type : types()) {
            html.append("<template class=\"ee-settings-template\" data-type=\"").append(Html.escape(type.type())).append("\">");
            settingsFieldset(html, type, form, Errors.none(), true);
            html.append("</template>\n");
        }
        PageSupport.foot(html, site);
        secureHeaders(request);
        PageSupport.write(request, status, "text/html; charset=utf-8", html.toString());
    }

    /**
     * Every field of the form (inside the {@code <form>}). Returns whether a known type is chosen,
     * i.e. whether the type-specific fields are shown.
     */
    private boolean fields(StringBuilder html, Form form, Errors errors, boolean create, boolean readOnly) {
        EndpointDefaults d = config.defaults();
        html.append("<fieldset class=\"ee-group\"><legend>Service</legend>\n");
        // id
        if (create) {
            fieldStart(html, "id", "f-id", "Id", false);
            html.append("<input type=\"text\" id=\"f-id\" name=\"id\" required pattern=\"[a-z0-9\\-]+\" value=\"")
                    .append(Html.escape(form.get("id"))).append("\"").append(describedBy("id", errors, true)).append(">");
            fieldEnd(html, "id", "Lowercase letters, digits and dashes. It's part of the service's address and can't be "
                    + "changed later, since the history is stored under it.", errors);
        } else {
            fieldStart(html, "id", "f-id", "Id", false);
            html.append("<input type=\"text\" id=\"f-id\" value=\"").append(Html.escape(form.get("id")))
                    .append("\" disabled").append(describedBy("id", errors, !readOnly)).append(">");
            fieldEnd(html, "id", readOnly ? null : "The id can't be changed.", errors);
        }
        // name
        fieldStart(html, "name", "f-name", "Name", false);
        html.append("<input type=\"text\" id=\"f-name\" name=\"name\" required value=\"").append(Html.escape(form.get("name")))
                .append("\"").append(describedBy("name", errors, false)).append(">");
        fieldEnd(html, "name", null, errors);
        // category
        fieldStart(html, "category", "f-category", "Category", false);
        html.append("<select id=\"f-category\" name=\"category\"").append(describedBy("category", errors, false)).append(">");
        option(html, "", "Other (no category)", form.get("category"));
        Set<String> listed = new HashSet<>();
        for (CategoryConfig c : store.categoryConfigs()) {
            listed.add(c.id());
            option(html, c.id(), c.name(), form.get("category"));
        }
        if (!form.get("category").isEmpty() && !listed.contains(form.get("category"))) {
            option(html, form.get("category"), form.get("category") + " (unknown)", form.get("category"));
        }
        html.append("</select>");
        fieldEnd(html, "category", null, errors);
        // visibility
        fieldStart(html, "visibility", "f-visibility", "Visibility", !readOnly && !form.get("visibility").isEmpty());
        html.append("<select id=\"f-visibility\" name=\"visibility\"").append(describedBy("visibility", errors, true)).append(">");
        if (!readOnly) option(html, "", "Default (" + d.visibility().configName() + ")", form.get("visibility"));
        boolean known = form.get("visibility").isEmpty();
        for (Visibility v : Visibility.values()) {
            option(html, v.configName(), v.configName(), form.get("visibility"));
            known |= v.configName().equals(form.get("visibility"));
        }
        if (!known) option(html, form.get("visibility"), form.get("visibility") + " (unknown)", form.get("visibility"));
        html.append("</select>");
        fieldEnd(html, "visibility", "public: everything but the target is public; public-status: only name, state "
                + "and uptime are public; private: only signed-in users see it.", errors);
        html.append("</fieldset>\n");

        // type and check settings
        html.append("<fieldset class=\"ee-group\"><legend>Check</legend>\n");
        String typeValue = form.get("type");
        List<TypeInfo> types = types();
        Optional<TypeInfo> chosen = types.stream().filter(t -> t.type().equals(typeValue)).findFirst();
        fieldStart(html, "type", "f-type", "Type", false);
        html.append("<span class=\"ee-type-row\"><select id=\"f-type\" class=\"ee-type\" name=\"type\" required")
                .append(describedBy("type", errors, false)).append(">");
        if (typeValue.isEmpty()) option(html, "", "Choose a type…", "");
        for (TypeInfo t : types) {
            option(html, t.type(), t.displayName().equals(t.type()) ? t.type() : t.displayName() + " (" + t.type() + ")",
                    typeValue);
        }
        if (!typeValue.isEmpty() && chosen.isEmpty()) {
            option(html, typeValue, typeValue + " (not available)", typeValue);
        }
        html.append("</select>");
        if (!readOnly) {
            html.append(" <button type=\"submit\" class=\"button ee-type-step\" name=\"action\" value=\"type\" formnovalidate>")
                    .append(chosen.isPresent() ? "Change type" : "Continue").append("</button>");
        }
        html.append("</span>");
        fieldEnd(html, "type", chosen.isPresent() || readOnly ? null
                : "Choose the type, then press Continue to fill in its settings.", errors);
        numberField(html, form, errors, "intervalSeconds", "Check interval (seconds)", d.intervalSeconds(), readOnly);
        numberField(html, form, errors, "timeoutMillis", "Timeout (milliseconds)", d.timeoutMillis(), readOnly);
        numberField(html, form, errors, "failureThreshold", "Failures before down", d.failureThreshold(), readOnly);
        html.append("</fieldset>\n<div class=\"ee-settings\">");
        if (chosen.isPresent()) {
            settingsFieldset(html, chosen.get(), form, errors, false);
        } else if (readOnly && !form.settings.isEmpty()) {
            // The plugin isn't loaded: show the raw settings.
            html.append("<fieldset class=\"ee-group\"><legend>Settings</legend>");
            for (Map.Entry<String, String> e : form.settings.entrySet()) {
                String id = "s-" + fieldId(e.getKey());
                fieldStart(html, e.getKey(), id, e.getKey(), false);
                html.append("<input type=\"text\" id=\"").append(Html.escape(id)).append("\" value=\"")
                        .append(Html.escape(e.getValue())).append("\">");
                fieldEnd(html, e.getKey(), null, errors);
            }
            html.append("</fieldset>");
        }
        html.append("</div>\n");

        // notifications
        html.append("<fieldset class=\"ee-group\"><legend>Notifications</legend>\n");
        String mode = form.get("notifyMode");
        boolean overridden = !readOnly && !mode.equals("default");
        html.append("<div class=\"field").append(errors.fields.containsKey("notify") ? " field-invalid" : "")
                .append("\" data-field=\"notify\"><span class=\"field-label\" id=\"l-notify\">Notify")
                .append(overridden ? " <span class=\"badge ee-override\">overrides default</span>" : "")
                .append("</span><div class=\"ee-choices\" role=\"radiogroup\" aria-labelledby=\"l-notify\">");
        String defaultChannels = d.notifyChannels().isEmpty() ? "none" : String.join(", ", d.notifyChannels());
        if (!readOnly) radio(html, "notifyMode", "default", "Use defaults (" + defaultChannels + ")", mode);
        radio(html, "notifyMode", "none", "None", mode);
        radio(html, "notifyMode", "channels", "These channels:", mode);
        html.append("<span class=\"ee-channels\">");
        Set<String> channels = new TreeSet<>(config.notificationChannels());
        for (String channel : channels) {
            String id = "f-notify-" + fieldId(channel);
            html.append("<label class=\"ee-check\" for=\"").append(Html.escape(id)).append("\"><input type=\"checkbox\" id=\"")
                    .append(Html.escape(id)).append("\" name=\"").append(Html.escape(NOTIFY_PREFIX + channel)).append("\" value=\"on\"")
                    .append(form.notify.contains(channel) ? " checked" : "").append("> ").append(Html.escape(channel))
                    .append("</label>");
        }
        if (channels.isEmpty()) html.append("<span class=\"muted\">No channels are configured.</span>");
        html.append("</span></div>");
        fieldErrors(html, "notify", errors);
        html.append("</div>\n");
        String defaultReminders = d.reminders().isEmpty() ? "none"
                : d.reminders().stream().map(String::valueOf).collect(Collectors.joining(", "));
        fieldStart(html, "reminders", "f-reminders", "Reminders (minutes after the outage started)",
                !readOnly && !form.get("reminders").isEmpty());
        html.append("<input type=\"text\" id=\"f-reminders\" name=\"reminders\" value=\"").append(Html.escape(form.get("reminders")))
                .append("\" placeholder=\"").append(Html.escape(defaultReminders)).append("\"")
                .append(describedBy("reminders", errors, !readOnly)).append(">");
        fieldEnd(html, "reminders", readOnly ? null
                : "E.g. 30, 60, 120, or \"none\". Leave empty for the default (" + defaultReminders + ").", errors);
        numberField(html, form, errors, "reminderRepeatMinutes", "Repeat reminders every (minutes, 0 = off)",
                d.reminderRepeatMinutes(), readOnly);
        html.append("</fieldset>\n");
        return chosen.isPresent();
    }

    private static void numberField(StringBuilder html, Form form, Errors errors, String key, String label, int def,
                                    boolean readOnly) {
        String id = "f-" + key;
        String value = form.get(key);
        fieldStart(html, key, id, label, !readOnly && !value.isEmpty());
        html.append("<input type=\"text\" inputmode=\"numeric\" id=\"").append(id).append("\" name=\"").append(key)
                .append("\" value=\"").append(Html.escape(value)).append("\" placeholder=\"").append(def).append("\"")
                .append(describedBy(key, errors, !readOnly)).append(">");
        fieldEnd(html, key, readOnly ? null : "Leave empty for the default (" + def + ").", errors);
    }

    /**
     * The type-specific fields of {@code type}. When the form's settings were entered for another type
     * (or none), fields the form has no value for start with the plugin's default value.
     */
    private static void settingsFieldset(StringBuilder html, TypeInfo type, Form form, Errors errors, boolean template) {
        boolean prefill = !type.type().equals(form.settingsFor);
        html.append("<fieldset class=\"ee-group ee-settings-fields\" data-type=\"").append(Html.escape(type.type()))
                .append("\"><legend>").append(Html.escape(type.displayName())).append(" settings</legend>")
                .append("<input type=\"hidden\" name=\"settingsFor\" value=\"").append(Html.escape(type.type())).append("\">");
        if (type.settings().isEmpty()) {
            html.append("<p class=\"muted\">This type has no settings.</p>");
        }
        for (SettingSpec spec : type.settings()) {
            String key = spec.key();
            String id = "s-" + fieldId(key);
            String name = SETTING_PREFIX + key;
            String value = form.settings.containsKey(key) ? form.settings.get(key)
                    : prefill ? defaultText(spec) : "";
            String help = spec.help() != null && !spec.help().isBlank() ? spec.help() : null;
            if (spec.kind() == SettingSpec.Kind.STRING_LIST) {
                help = (help == null ? "" : help + " ") + "One per line.";
            }
            boolean hasHelp = help != null;
            String described = template ? (hasHelp ? " aria-describedby=\"h-" + Html.escape(id) + "\"" : "")
                    : describedBy(id, key, errors, hasHelp);
            fieldStart(html, key, id, spec.label() + (spec.required() ? " (required)" : ""), false);
            switch (spec.kind()) {
                case STRING, INTEGER -> html.append("<input type=\"text\"")
                        .append(spec.kind() == SettingSpec.Kind.INTEGER ? " inputmode=\"numeric\"" : "")
                        .append(" id=\"").append(Html.escape(id)).append("\" name=\"").append(Html.escape(name))
                        .append("\" value=\"").append(Html.escape(value)).append("\"")
                        .append(spec.required() ? " required" : "").append(described).append(">");
                case BOOLEAN -> {
                    html.append("<select id=\"").append(Html.escape(id)).append("\" name=\"").append(Html.escape(name))
                            .append("\"").append(described).append(">");
                    String unset = spec.defaultValue() instanceof Boolean b ? "Not set (default: " + (b ? "yes" : "no") + ")"
                            : "Not set";
                    option(html, "", unset, value);
                    option(html, "true", "Yes", value);
                    option(html, "false", "No", value);
                    if (!value.isEmpty() && !value.equals("true") && !value.equals("false")) option(html, value, value, value);
                    html.append("</select>");
                }
                case CHOICE -> {
                    html.append("<select id=\"").append(Html.escape(id)).append("\" name=\"").append(Html.escape(name))
                            .append("\"").append(spec.required() ? " required" : "").append(described).append(">");
                    option(html, "", spec.required() ? "Choose…" : "Not set", value);
                    for (String choice : spec.choices()) option(html, choice, choice, value);
                    if (!value.isEmpty() && !spec.choices().contains(value)) option(html, value, value, value);
                    html.append("</select>");
                }
                case STRING_LIST -> html.append("<textarea id=\"").append(Html.escape(id)).append("\" name=\"")
                        .append(Html.escape(name)).append("\" rows=\"3\"").append(spec.required() ? " required" : "")
                        .append(described).append(">").append(Html.escape(value)).append("</textarea>");
            }
            if (template) {
                if (help != null) {
                    html.append("<p class=\"field-help muted\" id=\"h-").append(Html.escape(id)).append("\">")
                            .append(Html.escape(help)).append("</p>");
                }
                html.append("</div>");
            } else {
                fieldEnd(html, id, key, help, errors);
            }
        }
        html.append("</fieldset>");
    }

    /** A setting's default as the form shows it. */
    private static String defaultText(SettingSpec spec) {
        Object def = spec.defaultValue();
        if (def == null) return "";
        if (def instanceof List<?> list) {
            return list.stream().map(String::valueOf).collect(Collectors.joining("\n"));
        }
        return String.valueOf(def);
    }

    /** An HTML id fragment for a key: anything but letters, digits, {@code -} and {@code _} becomes {@code _}. */
    private static String fieldId(String key) {
        return key.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    private static void fieldStart(StringBuilder html, String key, String id, String label, boolean overrides) {
        html.append("<div class=\"field\" data-field=\"").append(Html.escape(key)).append("\"><label class=\"field-label\" for=\"")
                .append(Html.escape(id)).append("\">").append(Html.escape(label));
        if (overrides) html.append(" <span class=\"badge ee-override\">overrides default</span>");
        html.append("</label>");
    }

    /** {@code aria-invalid} and {@code aria-describedby} for a common field ({@code f-{key}}). */
    private static String describedBy(String key, Errors errors, boolean hasHelp) {
        return describedBy("f-" + key, key, errors, hasHelp);
    }

    private static String describedBy(String id, String key, Errors errors, boolean hasHelp) {
        List<String> ids = new ArrayList<>();
        boolean invalid = errors.fields.containsKey(key);
        if (invalid) ids.add("e-" + id);
        if (hasHelp) ids.add("h-" + id);
        StringBuilder out = new StringBuilder();
        if (invalid) out.append(" aria-invalid=\"true\"");
        if (!ids.isEmpty()) out.append(" aria-describedby=\"").append(Html.escape(String.join(" ", ids))).append("\"");
        return out.toString();
    }

    private static void fieldEnd(StringBuilder html, String key, String help, Errors errors) {
        fieldEnd(html, "f-" + key, key, help, errors);
    }

    private static void fieldEnd(StringBuilder html, String id, String key, String help, Errors errors) {
        if (help != null) {
            html.append("<p class=\"field-help muted\" id=\"h-").append(Html.escape(id)).append("\">")
                    .append(Html.escape(help)).append("</p>");
        }
        List<String> messages = errors.fields.get(key);
        if (messages != null) {
            html.append("<p class=\"field-error form-error\" id=\"e-").append(Html.escape(id)).append("\">")
                    .append(Html.escape(String.join("; ", messages))).append("</p>");
        }
        html.append("</div>\n");
    }

    private static void fieldErrors(StringBuilder html, String key, Errors errors) {
        List<String> messages = errors.fields.get(key);
        if (messages != null) {
            html.append("<p class=\"field-error form-error\" id=\"e-f-").append(Html.escape(key)).append("\">")
                    .append(Html.escape(String.join("; ", messages))).append("</p>");
        }
    }

    private static void option(StringBuilder html, String value, String label, String selected) {
        html.append("<option value=\"").append(Html.escape(value)).append("\"").append(value.equals(selected) ? " selected" : "")
                .append(">").append(Html.escape(label)).append("</option>");
    }

    private static void radio(StringBuilder html, String name, String value, String label, String selected) {
        String id = "f-" + name + "-" + value;
        html.append("<label class=\"ee-check\" for=\"").append(id).append("\"><input type=\"radio\" id=\"").append(id)
                .append("\" name=\"").append(name).append("\" value=\"").append(value).append("\"")
                .append(value.equals(selected) ? " checked" : "").append("> ").append(Html.escape(label)).append("</label>");
    }
}
