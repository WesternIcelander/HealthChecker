package io.siggi.healthchecker.web.page;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import io.siggi.healthchecker.auth.Permission;
import io.siggi.healthchecker.auth.Permissions;
import io.siggi.healthchecker.auth.SessionManager;
import io.siggi.healthchecker.auth.User;
import io.siggi.healthchecker.config.CategoryConfig;
import io.siggi.healthchecker.config.EndpointDefinition;
import io.siggi.healthchecker.web.Html;
import io.siggi.healthchecker.web.WebServer;
import io.siggi.healthchecker.web.layout.LayoutStore;
import io.siggi.healthchecker.web.layout.ResolvedLayout;
import io.siggi.healthchecker.web.layout.SavedLayout;
import io.siggi.healthchecker.web.site.Site;
import io.siggi.healthchecker.web.site.SiteResolver;
import io.siggi.healthchecker.web.view.Viewer;
import io.siggi.http.HTTPRequest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The layout editor: rendered on the main page ({@code /?edit=layout}, by {@link StatusPage}) and
 * saved with {@code POST /admin/layout}.
 * <ul>
 *   <li>Access needs {@code layout.edit} and {@code view.private} (the editor lists every service,
 *       private ones included): anonymous visitors are sent to {@code /login}, others get {@code 403}.</li>
 *   <li>The editor always shows the whole, global layout, whatever site it's opened on.</li>
 *   <li>The POST is JSON with the session's CSRF token in {@code X-CSRF-Token}:
 *       {@code {"version": n, "categories": [{"id", "services": [...]}]}} or
 *       {@code {"version": n, "reset": true}} ("Reset to config order"). It answers {@code 200}
 *       {@code {"version": new}}, {@code 400} {@code {"error"}} for malformed input, unknown or repeated
 *       ids, and {@code 409} {@code {"error", "version": current}} when the layout changed since
 *       {@code version}.</li>
 *   <li>Categories and services the POST leaves out are added as {@link ResolvedLayout#resolve} does;
 *       the whole effective layout is stored.</li>
 * </ul>
 */
public final class LayoutEditor {
    public static final String PATH = "/admin/layout";
    /** Largest accepted request body. */
    static final int MAX_BODY_BYTES = 256 * 1024;

    private static final Set<String> KEYS = Set.of("version", "categories", "reset");
    private static final Set<String> CATEGORY_KEYS = Set.of("id", "services");

    private final SiteResolver sites;
    private final Permissions permissions;
    private final SessionManager sessions;
    private final LayoutStore store;
    private final Supplier<List<CategoryConfig>> categories;
    private final Supplier<List<EndpointDefinition>> endpoints;

    /**
     * @param categories all current categories, in config order
     * @param endpoints  all current endpoints, in config order
     */
    public LayoutEditor(SiteResolver sites, Permissions permissions, SessionManager sessions, LayoutStore store,
                        Supplier<List<CategoryConfig>> categories, Supplier<List<EndpointDefinition>> endpoints) {
        this.sites = sites;
        this.permissions = permissions;
        this.sessions = sessions;
        this.store = store;
        this.categories = categories;
        this.endpoints = endpoints;
    }

    public void register(WebServer server) {
        server.register(PATH, this::post);
    }

    /** The current effective layout. */
    public ResolvedLayout layout() {
        return store.resolve(categories.get(), endpoints.get());
    }

    /** Whether the request's user may edit the layout (for showing the "Edit layout" link). */
    boolean canEdit(HTTPRequest request) {
        return permissions.user(request).map(LayoutEditor::canEdit).orElse(false);
    }

    private static boolean canEdit(User user) {
        return user.has(Permission.LAYOUT_EDIT) && user.has(Permission.VIEW_PRIVATE);
    }

    /**
     * Enforces access to the editor; without it, answers ({@code 303} to the sign-in page for anonymous
     * visitors, else {@code 403}) and returns false.
     */
    boolean requireAccess(HTTPRequest request, Site site) throws Exception {
        if (!permissions.require(request, site, Permission.LAYOUT_EDIT)) return false;
        if (permissions.has(request, Permission.VIEW_PRIVATE)) return true;
        PageSupport.write(request, "403 Forbidden", "text/plain; charset=utf-8",
                "Editing the layout also needs the view.private permission, because the editor lists every service.\n");
        return false;
    }

    // ---- HTML ----

    /** The editor section for the main page in edit mode. */
    void render(StringBuilder html, Site site, Viewer viewer) {
        ResolvedLayout layout = layout();
        Map<String, EndpointDefinition> byId = new HashMap<>();
        for (EndpointDefinition endpoint : endpoints.get()) {
            byId.putIfAbsent(endpoint.id(), endpoint);
        }
        html.append("<section class=\"layout-editor").append(layout.flat() ? " le-flat" : "")
                .append("\" aria-labelledby=\"le-title\" data-version=\"").append(store.version())
                .append("\" data-csrf=\"").append(Html.escape(viewer.csrfToken() == null ? "" : viewer.csrfToken()))
                .append("\" data-action=\"").append(Html.escape(site.link(PATH)))
                .append("\" data-done=\"").append(Html.escape(site.link("/"))).append("\">\n")
                .append("<h2 id=\"le-title\">Edit layout</h2>\n")
                .append("<p class=\"muted le-help\">")
                .append(layout.flat()
                        ? "Drag services, or use the arrow buttons, to change their order."
                        : "Drag categories and services, or use the arrow buttons and category menus, to change "
                        + "the order and move services between categories.")
                .append(" Every service is listed, including private ones. The layout is shared by all sites.</p>\n")
                .append("<noscript><p class=\"form-error\">The layout editor needs JavaScript.</p></noscript>\n")
                .append("<ol class=\"le-categories\">");
        for (ResolvedLayout.Category category : layout.categories()) {
            String name = category.isOther() ? "Other" : category.config().name();
            String id = category.id();
            html.append("<li class=\"le-category\" data-category=\"").append(Html.escape(id)).append("\">")
                    .append("<div class=\"le-category-head\">")
                    .append("<span class=\"le-grip\" aria-hidden=\"true\">☰</span>")
                    .append("<h3 class=\"le-category-name\">").append(Html.escape(name)).append("</h3>");
            if (category.isOther()) {
                html.append("<span class=\"muted le-note\">services without a category</span>");
            }
            html.append("<span class=\"le-controls\">");
            moveButtons(html, "category " + name);
            html.append("</span></div>")
                    .append("<ol class=\"le-services\" aria-label=\"Services in ").append(Html.escape(name)).append("\">");
            for (String serviceId : category.endpointIds()) {
                EndpointDefinition endpoint = byId.get(serviceId);
                service(html, layout, category, serviceId, endpoint == null ? serviceId : endpoint.name(),
                        endpoint == null ? null : endpoint.visibility().configName());
            }
            html.append("</ol></li>");
        }
        html.append("</ol>\n")
                .append("<div class=\"le-actions\">")
                .append("<button type=\"button\" class=\"le-save button-primary\">Save</button>")
                .append("<a class=\"le-cancel button\" href=\"").append(Html.escape(site.link("/"))).append("\">Cancel</a>")
                .append("<button type=\"button\" class=\"le-reset\">Reset to config order</button>")
                .append("</div>\n<p class=\"le-status\" role=\"status\" aria-live=\"polite\"></p>\n</section>\n");
    }

    private static void service(StringBuilder html, ResolvedLayout layout, ResolvedLayout.Category current,
                                String id, String name, String visibility) {
        html.append("<li class=\"le-service\" data-service=\"").append(Html.escape(id)).append("\">")
                .append("<span class=\"le-grip\" aria-hidden=\"true\">☰</span>")
                .append("<span class=\"le-name\">").append(Html.escape(name)).append("</span>");
        if (visibility != null) {
            html.append("<span class=\"badge le-vis le-vis-").append(Html.escape(visibility)).append("\" title=\"Visibility\">")
                    .append(Html.escape(visibility)).append("</span>");
        }
        html.append("<span class=\"le-controls\">");
        if (!layout.flat()) {
            String selectId = "le-cat-" + id;
            html.append("<label class=\"visually-hidden\" for=\"").append(Html.escape(selectId)).append("\">Category of ")
                    .append(Html.escape(name)).append("</label>")
                    .append("<select class=\"le-move\" id=\"").append(Html.escape(selectId)).append("\">");
            for (ResolvedLayout.Category category : layout.categories()) {
                html.append("<option value=\"").append(Html.escape(category.id())).append("\"")
                        .append(category.id().equals(current.id()) ? " selected" : "").append(">")
                        .append(Html.escape(category.isOther() ? "Other" : category.config().name())).append("</option>");
            }
            html.append("</select>");
        }
        moveButtons(html, name);
        html.append("</span></li>");
    }

    private static void moveButtons(StringBuilder html, String label) {
        html.append("<button type=\"button\" class=\"le-up\" aria-label=\"Move ").append(Html.escape(label))
                .append(" up\">↑</button>")
                .append("<button type=\"button\" class=\"le-down\" aria-label=\"Move ").append(Html.escape(label))
                .append(" down\">↓</button>");
    }

    // ---- POST ----

    private void post(HTTPRequest request) throws Exception {
        if (!request.path().equals(PATH)) return; // nothing written → 404
        Optional<Site> site = sites.resolve(request);
        if (site.isEmpty()) return;
        request.allowedMethods("POST");
        if (!requireAccess(request, site.get())) return;
        if (!sessions.requireCsrf(request)) return;
        String contentType = request.getHeader("Content-Type");
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT).trim().startsWith("application/json")) {
            error(request, "415 Unsupported Media Type", "expected application/json");
            return;
        }
        byte[] body = readBody(request.getInputStream());
        if (body == null) {
            error(request, "413 Content Too Large", "request too large");
            return;
        }
        Parsed parsed;
        try {
            parsed = parse(new String(body, StandardCharsets.UTF_8), categories.get(), endpoints.get());
        } catch (InvalidLayoutException e) {
            error(request, "400 Bad Request", e.getMessage());
            return;
        }
        List<SavedLayout.Category> toSave;
        if (parsed.reset()) {
            toSave = List.of();
        } else {
            ResolvedLayout merged = ResolvedLayout.resolve(new SavedLayout(0, parsed.categories()),
                    categories.get(), endpoints.get());
            toSave = new ArrayList<>();
            for (ResolvedLayout.Category category : merged.categories()) {
                toSave.add(new SavedLayout.Category(category.id(), category.endpointIds()));
            }
        }
        SavedLayout saved;
        try {
            saved = store.save(parsed.version(), toSave);
        } catch (LayoutStore.VersionConflictException e) {
            JsonObject conflict = new JsonObject();
            conflict.addProperty("error", "Someone else changed the layout in the meantime.");
            conflict.addProperty("version", e.currentVersion());
            PageSupport.write(request, "409 Conflict", JsonViews.CONTENT_TYPE, JsonViews.GSON.toJson(conflict));
            return;
        } catch (IOException e) {
            System.getLogger(LayoutEditor.class.getName()).log(System.Logger.Level.ERROR, "Saving the layout failed", e);
            error(request, "500 Internal Server Error", "saving failed");
            return;
        }
        JsonObject ok = new JsonObject();
        ok.addProperty("version", saved.version());
        PageSupport.write(request, "200 OK", JsonViews.CONTENT_TYPE, JsonViews.GSON.toJson(ok));
    }

    private static void error(HTTPRequest request, String status, String message) throws Exception {
        JsonObject error = new JsonObject();
        error.addProperty("error", message);
        PageSupport.write(request, status, JsonViews.CONTENT_TYPE, JsonViews.GSON.toJson(error));
    }

    /** The body, or {@code null} when it's larger than {@link #MAX_BODY_BYTES}. */
    private static byte[] readBody(InputStream in) throws IOException {
        if (in == null) return new byte[0];
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) != -1) {
            if (out.size() + n > MAX_BODY_BYTES) return null;
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    /** A validated save request. */
    record Parsed(int version, boolean reset, List<SavedLayout.Category> categories) {
    }

    static final class InvalidLayoutException extends Exception {
        InvalidLayoutException(String message) {
            super(message);
        }
    }

    /**
     * Parses and validates a save request against the current categories and endpoints: every category
     * id must exist (or be {@code "_other"}; only that one in a flat layout), every service id must be a
     * current endpoint, and no id may appear twice. Ids may be left out.
     */
    static Parsed parse(String json, List<CategoryConfig> categories, List<EndpointDefinition> endpoints)
            throws InvalidLayoutException {
        JsonElement root;
        try {
            root = JsonParser.parseString(json);
        } catch (JsonParseException e) {
            throw new InvalidLayoutException("invalid JSON");
        }
        if (root == null || !root.isJsonObject()) throw new InvalidLayoutException("expected a JSON object");
        JsonObject o = root.getAsJsonObject();
        for (String key : o.keySet()) {
            if (!KEYS.contains(key)) throw new InvalidLayoutException("unknown key \"" + key + "\"");
        }
        int version = version(o.get("version"));
        boolean reset = false;
        JsonElement resetJson = o.get("reset");
        if (resetJson != null) {
            if (!(resetJson instanceof JsonPrimitive p && p.isBoolean())) {
                throw new InvalidLayoutException("reset: expected true or false");
            }
            reset = p.getAsBoolean();
        }
        JsonElement categoriesJson = o.get("categories");
        if (reset) {
            if (categoriesJson != null) throw new InvalidLayoutException("reset: categories not allowed");
            return new Parsed(version, true, List.of());
        }
        if (categoriesJson == null || !categoriesJson.isJsonArray()) {
            throw new InvalidLayoutException("categories: expected an array");
        }
        boolean flat = categories.isEmpty();
        Set<String> categoryIds = new HashSet<>();
        if (!flat) {
            for (CategoryConfig category : categories) categoryIds.add(category.id());
        }
        categoryIds.add(SavedLayout.OTHER_ID);
        Set<String> endpointIds = new HashSet<>();
        for (EndpointDefinition endpoint : endpoints) endpointIds.add(endpoint.id());

        Set<String> seenCategories = new HashSet<>();
        Set<String> seenServices = new HashSet<>();
        List<SavedLayout.Category> result = new ArrayList<>();
        JsonArray array = categoriesJson.getAsJsonArray();
        for (int i = 0; i < array.size(); i++) {
            String where = "categories[" + i + "]";
            JsonElement element = array.get(i);
            if (!element.isJsonObject()) throw new InvalidLayoutException(where + ": expected an object");
            JsonObject c = element.getAsJsonObject();
            for (String key : c.keySet()) {
                if (!CATEGORY_KEYS.contains(key)) throw new InvalidLayoutException(where + ": unknown key \"" + key + "\"");
            }
            String id = string(c.get("id"), where + ".id");
            if (!categoryIds.contains(id)) throw new InvalidLayoutException(where + ": unknown category \"" + id + "\"");
            if (!seenCategories.add(id)) throw new InvalidLayoutException(where + ": category \"" + id + "\" repeated");
            JsonElement servicesJson = c.get("services");
            if (servicesJson == null || !servicesJson.isJsonArray()) {
                throw new InvalidLayoutException(where + ".services: expected an array");
            }
            List<String> services = new ArrayList<>();
            JsonArray servicesArray = servicesJson.getAsJsonArray();
            for (int j = 0; j < servicesArray.size(); j++) {
                String serviceWhere = where + ".services[" + j + "]";
                String service = string(servicesArray.get(j), serviceWhere);
                if (!endpointIds.contains(service)) {
                    throw new InvalidLayoutException(serviceWhere + ": unknown service \"" + service + "\"");
                }
                if (!seenServices.add(service)) {
                    throw new InvalidLayoutException(serviceWhere + ": service \"" + service + "\" repeated");
                }
                services.add(service);
            }
            result.add(new SavedLayout.Category(id, services));
        }
        return new Parsed(version, false, result);
    }

    private static int version(JsonElement e) throws InvalidLayoutException {
        if (e instanceof JsonPrimitive p && p.isNumber()) {
            double d = p.getAsDouble();
            if (d >= 0 && d <= Integer.MAX_VALUE && d == Math.rint(d)) return (int) d;
        }
        throw new InvalidLayoutException("version: expected a non-negative integer");
    }

    private static String string(JsonElement e, String where) throws InvalidLayoutException {
        if (e instanceof JsonPrimitive p && p.isString()) return p.getAsString();
        throw new InvalidLayoutException(where + ": expected a string");
    }
}
