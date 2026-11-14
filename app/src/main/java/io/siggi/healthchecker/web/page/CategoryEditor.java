package io.siggi.healthchecker.web.page;

import com.google.gson.JsonObject;
import io.siggi.healthchecker.auth.Permission;
import io.siggi.healthchecker.auth.Permissions;
import io.siggi.healthchecker.auth.SessionManager;
import io.siggi.healthchecker.config.CategoryConfig;
import io.siggi.healthchecker.config.Config;
import io.siggi.healthchecker.config.EndpointDefinition;
import io.siggi.healthchecker.config.EndpointStore;
import io.siggi.healthchecker.web.Html;
import io.siggi.healthchecker.web.WebServer;
import io.siggi.healthchecker.web.layout.LayoutStore;
import io.siggi.healthchecker.web.layout.ResolvedLayout;
import io.siggi.healthchecker.web.site.Site;
import io.siggi.healthchecker.web.site.SiteResolver;
import io.siggi.healthchecker.web.view.Viewer;
import io.siggi.http.HTTPRequest;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The category editor, reached from the layout editor, for users with {@code endpoints.manage} and
 * {@code view.private} (like the endpoint editor; anonymous visitors go to {@code /login}, others get
 * {@code 403}). Every form is a plain POST with the CSRF token and the endpoints {@code version}
 * ({@code 409} when stale); after a change it returns to the layout editor.
 * <ul>
 *   <li>{@code /admin/categories/new}: add a dashboard category (id, name, description).</li>
 *   <li>{@code /admin/categories/{id}}: rename a dashboard category (name, description; the id can't
 *       change). A config-defined category is shown read-only and its POST is {@code 403}.</li>
 *   <li>{@code /admin/categories/{id}/delete}: confirmation listing the category's services, which move
 *       to "Other" (dashboard endpoints lose their {@code category}; the saved layout moves the category's
 *       services to "Other"). Config categories: {@code 403}.</li>
 *   <li>{@code /admin/categories/{id}/copy}: "Copy to dashboard" for a config-defined category.</li>
 * </ul>
 */
public final class CategoryEditor {
    public static final String PATH = "/admin/categories";
    private static final Pattern ID = Pattern.compile("[a-z0-9-]+");

    private final SiteResolver sites;
    private final Permissions permissions;
    private final SessionManager sessions;
    private final EndpointStore store;
    private final LayoutStore layout;
    private final Config config;

    public CategoryEditor(SiteResolver sites, Permissions permissions, SessionManager sessions, EndpointStore store,
                          LayoutStore layout, Config config) {
        this.sites = sites;
        this.permissions = permissions;
        this.sessions = sessions;
        this.store = store;
        this.layout = layout;
        this.config = config;
    }

    public void register(WebServer server) {
        server.register(PATH + "/", this::handle);
    }

    /** Whether the request's user may use the category editor (for the layout editor's links). */
    public boolean canManage(HTTPRequest request) {
        return permissions.user(request)
                .map(u -> u.has(Permission.ENDPOINTS_MANAGE) && u.has(Permission.VIEW_PRIVATE)).orElse(false);
    }

    /** Where category {@code id} is defined, if it exists. */
    Optional<EndpointStore.Source> source(String id) {
        return store.categories().stream().filter(c -> c.category().id().equals(id))
                .map(EndpointStore.StoredCategory::source).findFirst();
    }

    /** Whether config category {@code id} was copied to the dashboard and waits for a restart. */
    boolean copied(String id) {
        return store.categoryCopy(id).isPresent();
    }

    private boolean requireAccess(HTTPRequest request, Site site) throws Exception {
        if (!permissions.require(request, site, Permission.ENDPOINTS_MANAGE)) return false;
        if (permissions.has(request, Permission.VIEW_PRIVATE)) return true;
        PageSupport.write(request, "403 Forbidden", "text/plain; charset=utf-8",
                "Managing categories also needs the view.private permission, because the editor lists every "
                        + "service.\n");
        return false;
    }

    // ---- routing ----

    private void handle(HTTPRequest request) throws Exception {
        String path = request.path();
        if (!path.startsWith(PATH + "/")) return;
        String rest = path.substring(PATH.length() + 1);
        String sub = null;
        int slash = rest.indexOf('/');
        if (slash >= 0) {
            sub = rest.substring(slash + 1);
            rest = rest.substring(0, slash);
            if (!sub.equals("delete") && !sub.equals("copy")) return; // nothing written → 404
        }
        boolean create = rest.equals("new") && sub == null;
        if (!create && !ID.matcher(rest).matches()) return;
        Optional<Site> found = sites.resolve(request);
        if (found.isEmpty()) return;
        Site site = found.get();
        request.allowedMethods("GET", "POST");
        if (!requireAccess(request, site)) return;
        boolean post = request.method().equals("POST");
        if (post && !sessions.requireCsrf(request)) return;
        if (create) {
            if (post) {
                submit(request, site, null);
            } else {
                formPage(request, site, "200 OK", null, new LinkedHashMap<>(), List.of());
            }
            return;
        }
        String id = rest;
        Optional<EndpointStore.StoredCategory> stored = store.categories().stream()
                .filter(c -> c.category().id().equals(id)).findFirst();
        if (stored.isEmpty()) return; // nothing written → 404
        CategoryConfig category = stored.get().category();
        boolean fromConfig = stored.get().source() == EndpointStore.Source.CONFIG;
        if ("copy".equals(sub)) {
            if (!fromConfig) return; // only config categories → 404
            if (post) {
                String[] failed = CopyToDashboard.submit(request, site, PATH + "/" + id + "/copy",
                        version -> store.copyCategory(version, id), version -> store.cancelCategoryCopy(version, id));
                if (failed != null) copyPage(request, site, failed[0], category, null, failed[1]);
            } else {
                copyPage(request, site, "200 OK", category, CopyToDashboard.notice(request), null);
            }
            return;
        }
        if (fromConfig) {
            if (post || "delete".equals(sub)) {
                PageSupport.write(request, "403 Forbidden", "text/plain; charset=utf-8",
                        "This category is defined in config.json and can't be changed in the dashboard. Its position "
                                + "can still be changed with the layout editor.\n");
                return;
            }
            readOnlyPage(request, site, category);
            return;
        }
        if ("delete".equals(sub)) {
            if (post) {
                submitDelete(request, site, category);
            } else {
                deletePage(request, site, "200 OK", category, store.version(), null);
            }
            return;
        }
        if (post) {
            submit(request, site, category);
        } else {
            Map<String, String> form = new LinkedHashMap<>();
            form.put("name", category.name());
            form.put("description", category.description() == null ? "" : category.description());
            formPage(request, site, "200 OK", category, form, List.of());
        }
    }

    // ---- POST ----

    /** Adds ({@code existing == null}) or renames a dashboard category. */
    private void submit(HTTPRequest request, Site site, CategoryConfig existing) throws Exception {
        Map<String, String> post = request.post();
        if (post == null) post = Map.of();
        Map<String, String> form = new LinkedHashMap<>();
        for (String key : List.of("id", "name", "description", "version")) {
            form.put(key, post.getOrDefault(key, ""));
        }
        String id = existing != null ? existing.id() : form.get("id").strip();
        JsonObject entry = new JsonObject();
        entry.addProperty("id", id);
        entry.addProperty("name", form.get("name").strip());
        String description = form.get("description").strip();
        if (!description.isEmpty()) entry.addProperty("description", description);
        if (existing == null && !id.isEmpty() && source(id).isPresent()) {
            formPage(request, site, "400 Bad Request", null, form, List.of("id: \"" + id + "\" is already in use"));
            return;
        }
        try {
            store.putCategory(CopyToDashboard.parseVersion(form.get("version")), entry);
        } catch (EndpointStore.InvalidEntryException e) {
            formPage(request, site, "400 Bad Request", existing, form, e.errors());
            return;
        } catch (EndpointStore.VersionConflictException e) {
            form.put("version", Integer.toString(e.currentVersion()));
            formPage(request, site, "409 Conflict", existing, form, List.of("Someone else changed the services or "
                    + "categories in the meantime. Check the values and save again."));
            return;
        } catch (IOException e) {
            System.getLogger(CategoryEditor.class.getName()).log(System.Logger.Level.ERROR,
                    "Saving category " + id + " failed", e);
            PageSupport.write(request, "500 Internal Server Error", "text/plain; charset=utf-8",
                    "Saving failed. Try again later.\n");
            return;
        }
        redirectToLayout(request, site);
    }

    /** Deletes a dashboard category; its services move to "Other". */
    private void submitDelete(HTTPRequest request, Site site, CategoryConfig category) throws Exception {
        Map<String, String> post = request.post();
        if (post == null) post = Map.of();
        String id = category.id();
        try {
            store.removeCategory(CopyToDashboard.parseVersion(post.getOrDefault("version", "")), id);
        } catch (EndpointStore.VersionConflictException e) {
            deletePage(request, site, "409 Conflict", category, e.currentVersion(), "Someone else changed the services "
                    + "or categories in the meantime. Check the list and confirm again.");
            return;
        } catch (EndpointStore.InvalidEntryException e) {
            PageSupport.write(request, "409 Conflict", "text/plain; charset=utf-8", String.join("\n", e.errors()) + "\n");
            return;
        } catch (IOException e) {
            System.getLogger(CategoryEditor.class.getName()).log(System.Logger.Level.ERROR,
                    "Deleting category " + id + " failed", e);
            PageSupport.write(request, "500 Internal Server Error", "text/plain; charset=utf-8",
                    "Deleting failed. Try again later.\n");
            return;
        }
        try {
            layout.moveToOther(id);
        } catch (IOException e) {
            // The category is gone; the resolved layout puts its services back in their own category or "Other".
            System.getLogger(CategoryEditor.class.getName()).log(System.Logger.Level.ERROR,
                    "Moving the services of deleted category " + id + " to \"Other\" in the layout failed", e);
        }
        redirectToLayout(request, site);
    }

    private static void redirectToLayout(HTTPRequest request, Site site) throws Exception {
        request.response.setHeader("303 See Other");
        request.response.setHeader("Location", site.link("/?edit=layout"));
        request.response.setHeader("Cache-Control", "no-store");
        request.response.setHeader("Content-Length", "0");
        request.response.write(new byte[0]);
    }

    // ---- HTML ----

    private static void secureHeaders(HTTPRequest request) {
        request.response.setHeader("X-Frame-Options", "DENY");
        request.response.setHeader("Content-Security-Policy", "frame-ancestors 'none'");
    }

    private static void crumbs(StringBuilder html, Site site) {
        html.append("<p class=\"crumbs\"><a href=\"").append(Html.escape(site.link("/"))).append("\">All services</a> · ")
                .append("<a href=\"").append(Html.escape(site.link("/?edit=layout"))).append("\">Edit layout</a></p>\n");
    }

    private static void hidden(StringBuilder html, Viewer viewer, String version) {
        html.append("<input type=\"hidden\" name=\"").append(SessionManager.CSRF_FIELD).append("\" value=\"")
                .append(Html.escape(viewer.csrfToken() == null ? "" : viewer.csrfToken())).append("\">")
                .append("<input type=\"hidden\" name=\"version\" value=\"").append(Html.escape(version)).append("\">\n");
    }

    /** The add or rename form ({@code existing == null} when adding). */
    private void formPage(HTTPRequest request, Site site, String status, CategoryConfig existing,
                          Map<String, String> form, List<String> errors) throws Exception {
        Viewer viewer = permissions.viewer(request);
        boolean create = existing == null;
        String title = create ? "Add category" : "Rename " + existing.name();
        String version = form.getOrDefault("version", "");
        if (version.isEmpty()) version = Integer.toString(store.version());
        StringBuilder html = new StringBuilder(4096);
        PageSupport.head(html, site, title + " · " + site.title(), false, viewer);
        crumbs(html, site);
        html.append("<h2 class=\"ee-title\">").append(Html.escape(title)).append("</h2>\n");
        if (!errors.isEmpty()) {
            html.append("<p class=\"form-error\" role=\"alert\">Nothing was saved.</p>\n<ul class=\"form-error-list ce-errors\">");
            for (String error : errors) html.append("<li>").append(Html.escape(error)).append("</li>");
            html.append("</ul>\n");
        }
        html.append("<form class=\"endpoint-form ce-form\" method=\"post\" action=\"")
                .append(Html.escape(site.link(PATH + "/" + (create ? "new" : existing.id())))).append("\">");
        hidden(html, viewer, version);
        if (create) {
            html.append("<div class=\"field\" data-field=\"id\"><label class=\"field-label\" for=\"f-id\">Id</label>")
                    .append("<input type=\"text\" id=\"f-id\" name=\"id\" required pattern=\"[a-z0-9-]+\" value=\"")
                    .append(Html.escape(form.getOrDefault("id", ""))).append("\" aria-describedby=\"h-f-id\">")
                    .append("<p class=\"field-help muted\" id=\"h-f-id\">Lowercase letters, digits and dashes. "
                            + "It can't be changed later.</p></div>\n");
        } else {
            html.append("<p class=\"muted\">Id: <code>").append(Html.escape(existing.id()))
                    .append("</code> (can't be changed)</p>\n");
        }
        html.append("<div class=\"field\" data-field=\"name\"><label class=\"field-label\" for=\"f-name\">Name</label>")
                .append("<input type=\"text\" id=\"f-name\" name=\"name\" required value=\"")
                .append(Html.escape(form.getOrDefault("name", ""))).append("\"></div>\n")
                .append("<div class=\"field\" data-field=\"description\"><label class=\"field-label\" for=\"f-description\">")
                .append("Description (optional)</label><input type=\"text\" id=\"f-description\" name=\"description\" value=\"")
                .append(Html.escape(form.getOrDefault("description", ""))).append("\"></div>\n")
                .append("<div class=\"ee-actions\"><button type=\"submit\" class=\"button button-primary\">")
                .append(create ? "Add category" : "Save").append("</button>")
                .append("<a class=\"button\" href=\"").append(Html.escape(site.link("/?edit=layout"))).append("\">Cancel</a>");
        if (!create) {
            html.append("<a class=\"button button-danger\" href=\"")
                    .append(Html.escape(site.link(PATH + "/" + existing.id() + "/delete"))).append("\">Delete…</a>");
        }
        html.append("</div>\n</form>\n");
        PageSupport.foot(html, site);
        secureHeaders(request);
        PageSupport.write(request, status, "text/html; charset=utf-8", html.toString());
    }

    private void readOnlyPage(HTTPRequest request, Site site, CategoryConfig category) throws Exception {
        Viewer viewer = permissions.viewer(request);
        StringBuilder html = new StringBuilder(4096);
        PageSupport.head(html, site, category.name() + " · " + site.title(), false, viewer);
        crumbs(html, site);
        html.append("<h2 class=\"ee-title\">").append(Html.escape(category.name()))
                .append(" <span class=\"badge badge-config\" title=\"Defined in config.json\">config</span></h2>\n")
                .append("<p class=\"muted\">This category is defined in <code>config.json</code>, so it can't be "
                        + "renamed or deleted here. Change it there and restart. Its position can still be changed "
                        + "with the layout editor.</p>\n<dl class=\"facts\">")
                .append("<div class=\"fact\"><dt>Id</dt><dd><code>").append(Html.escape(category.id())).append("</code></dd></div>")
                .append("<div class=\"fact\"><dt>Name</dt><dd>").append(Html.escape(category.name())).append("</dd></div>");
        if (category.description() != null) {
            html.append("<div class=\"fact\"><dt>Description</dt><dd>").append(Html.escape(category.description()))
                    .append("</dd></div>");
        }
        html.append("</dl>\n<p class=\"page-actions\"><a class=\"button ce-copy\" href=\"")
                .append(Html.escape(site.link(PATH + "/" + category.id() + "/copy"))).append("\">")
                .append(copied(category.id()) ? "Copied to the dashboard: finish or cancel…" : "Copy to dashboard…")
                .append("</a></p>\n");
        PageSupport.foot(html, site);
        secureHeaders(request);
        PageSupport.write(request, "200 OK", "text/html; charset=utf-8", html.toString());
    }

    /** The services in a category, in layout order, as {@code id → name}. */
    private Map<String, String> servicesIn(String categoryId) {
        List<EndpointDefinition> endpoints = store.definitions();
        Map<String, String> names = new HashMap<>();
        for (EndpointDefinition d : endpoints) names.putIfAbsent(d.id(), d.name());
        Map<String, String> out = new LinkedHashMap<>();
        ResolvedLayout resolved = layout.resolve(store.categoryConfigs(), endpoints);
        for (ResolvedLayout.Category c : resolved.categories()) {
            if (c.isOther() || !c.id().equals(categoryId)) continue;
            for (String id : c.endpointIds()) out.put(id, names.getOrDefault(id, id));
        }
        // Disabled dashboard endpoints aren't in the layout but lose the category too.
        for (EndpointStore.StoredEndpoint e : store.endpoints()) {
            if (e.enabled() || e.entry() == null) continue;
            JsonObject entry = e.entry();
            if (entry.get("category") != null && entry.get("category").isJsonPrimitive()
                    && entry.get("category").getAsString().equals(categoryId)) {
                out.putIfAbsent(e.id(), e.id() + " (disabled)");
            }
        }
        return out;
    }

    private void deletePage(HTTPRequest request, Site site, String status, CategoryConfig category, int version,
                            String error) throws Exception {
        Viewer viewer = permissions.viewer(request);
        Map<String, String> services = servicesIn(category.id());
        StringBuilder html = new StringBuilder(4096);
        PageSupport.head(html, site, "Delete " + category.name() + " · " + site.title(), false, viewer);
        crumbs(html, site);
        html.append("<h2 class=\"ee-title\">Delete category ").append(Html.escape(category.name())).append("</h2>\n");
        if (error != null) {
            html.append("<p class=\"form-error\" role=\"alert\">").append(Html.escape(error)).append("</p>\n");
        }
        if (services.isEmpty()) {
            html.append("<p>The category <code>").append(Html.escape(category.id())).append("</code> has no services.</p>\n");
        } else {
            html.append("<p>The category <code>").append(Html.escape(category.id()))
                    .append("</code> is deleted and these services move to \"Other\":</p>\n<ul class=\"ce-moved\">");
            for (Map.Entry<String, String> e : services.entrySet()) {
                html.append("<li data-service=\"").append(Html.escape(e.getKey())).append("\">")
                        .append(Html.escape(e.getValue())).append(" <code>").append(Html.escape(e.getKey()))
                        .append("</code></li>");
            }
            html.append("</ul>\n");
        }
        html.append("<form class=\"endpoint-form ce-delete-form\" method=\"post\" action=\"")
                .append(Html.escape(site.link(PATH + "/" + category.id() + "/delete"))).append("\">");
        hidden(html, viewer, Integer.toString(version));
        html.append("<div class=\"ee-actions\"><button type=\"submit\" class=\"button button-danger\">Delete category</button>")
                .append("<a class=\"button\" href=\"").append(Html.escape(site.link("/?edit=layout")))
                .append("\">Cancel</a></div>\n</form>\n");
        PageSupport.foot(html, site);
        secureHeaders(request);
        PageSupport.write(request, status, "text/html; charset=utf-8", html.toString());
    }

    /** "Copy to dashboard" for a config-defined category. */
    private void copyPage(HTTPRequest request, Site site, String status, CategoryConfig category, String notice,
                          String error) throws Exception {
        String id = category.id();
        Config.Entry entry = config.categoryEntries().get(id);
        JsonObject written = entry != null ? entry.written() : EndpointStore.categoryEntry(category);
        JsonObject resolved = entry != null ? entry.resolved() : EndpointStore.categoryEntry(category);
        List<String> warnings = new ArrayList<>();
        List<String> users = new ArrayList<>();
        for (EndpointDefinition d : config.endpoints()) {
            if (id.equals(d.category()) && store.endpointCopy(d.id()).isEmpty()) users.add(d.id());
        }
        if (!users.isEmpty()) {
            StringBuilder w = new StringBuilder("These <code>config.json</code> services still use this category: ");
            for (int i = 0; i < users.size(); i++) {
                if (i > 0) w.append(", ");
                w.append("<a href=\"").append(Html.escape(site.link(EndpointEditor.PATH + "/" + users.get(i))))
                        .append("\"><code>").append(Html.escape(users.get(i))).append("</code></a>");
            }
            w.append(". Copy them to the dashboard and remove them too, or change their category, before restarting; "
                    + "otherwise <code>config.json</code> no longer loads.");
            warnings.add(w.toString());
        }
        StringBuilder crumbs = new StringBuilder();
        crumbs(crumbs, site);
        CopyToDashboard.page(request, site, permissions.viewer(request), status,
                new CopyToDashboard.Subject("category", id, category.name(), "categories", written, resolved,
                        store.categoryCopy(id).map(EndpointStore::categoryEntry).orElse(null), warnings),
                site.link(PATH + "/" + id + "/copy"), site.link("/?edit=layout"), crumbs.toString(), store.version(),
                notice, error);
    }
}
