package io.siggi.healthchecker.web.page;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import io.siggi.healthchecker.audit.AuditLog;
import io.siggi.healthchecker.auth.Permission;
import io.siggi.healthchecker.auth.Permissions;
import io.siggi.healthchecker.web.Html;
import io.siggi.healthchecker.web.WebServer;
import io.siggi.healthchecker.web.site.Site;
import io.siggi.healthchecker.web.site.SiteResolver;
import io.siggi.healthchecker.web.view.Viewer;
import io.siggi.http.HTTPRequest;

import java.io.IOException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * {@code GET /admin/audit}: the audit log, newest first, {@value #PAGE_SIZE} entries per page
 * ({@code ?page=N}, 0 = newest).
 * <p>Access needs {@code audit.view} and {@code view.private}, since entries show private services,
 * targets and private notes: anonymous visitors are sent to {@code /login}, others get {@code 403}.
 */
public final class AuditPage {
    public static final String PATH = "/admin/audit";
    public static final int PAGE_SIZE = 50;
    /** Pages past this are {@code 404}; keeps the arithmetic far from overflowing. */
    private static final int MAX_PAGE = 1_000_000;

    private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().serializeNulls().disableHtmlEscaping()
            .create();
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z");

    private final SiteResolver sites;
    private final Permissions permissions;
    private final AuditLog log;
    private final ZoneId zone;

    /** @param zone the configured timezone, for showing times */
    public AuditPage(SiteResolver sites, Permissions permissions, AuditLog log, ZoneId zone) {
        this.sites = sites;
        this.permissions = permissions;
        this.log = log;
        this.zone = zone;
    }

    public void register(WebServer server) {
        server.register(PATH, this::page);
    }

    /** Whether the request's user may see the audit log (for showing the link to it). */
    public boolean canView(HTTPRequest request) {
        return permissions.user(request)
                .map(u -> u.has(Permission.AUDIT_VIEW) && u.has(Permission.VIEW_PRIVATE)).orElse(false);
    }

    private void page(HTTPRequest request) throws Exception {
        if (!request.path().equals(PATH)) return; // nothing written → 404
        Optional<Site> found = sites.resolve(request);
        if (found.isEmpty()) return;
        Site site = found.get();
        request.allowedMethods("GET");
        if (!permissions.require(request, site, Permission.AUDIT_VIEW)) return;
        if (!permissions.has(request, Permission.VIEW_PRIVATE)) {
            PageSupport.write(request, "403 Forbidden", "text/plain; charset=utf-8",
                    "The audit log also needs the view.private permission, because it shows private services, "
                            + "targets and notes.\n");
            return;
        }
        int number = parsePage(request.get().get("page"));
        if (number < 0) return; // nothing written → 404
        AuditLog.Page page;
        try {
            page = log.page(number, PAGE_SIZE);
        } catch (IOException e) {
            System.getLogger(AuditPage.class.getName()).log(System.Logger.Level.ERROR, "Reading the audit log failed", e);
            PageSupport.write(request, "500 Internal Server Error", "text/plain; charset=utf-8",
                    "The audit log couldn't be read. See the server log.\n");
            return;
        }
        if (number > 0 && page.entries().isEmpty()) return; // past the end → 404
        Viewer viewer = permissions.viewer(request);
        StringBuilder html = new StringBuilder(16384);
        PageSupport.head(html, site, "Audit log · " + site.title(), false, viewer);
        html.append("<p class=\"crumbs\"><a href=\"").append(Html.escape(site.link("/"))).append("\">All services</a></p>\n")
                .append("<h2>Audit log</h2>\n");
        if (page.entries().isEmpty()) {
            html.append("<p class=\"muted\">Nothing has been changed in the dashboard yet.</p>\n");
        } else {
            html.append("<ol class=\"audit-list\">\n");
            for (AuditLog.Entry entry : page.entries()) {
                entry(html, entry);
            }
            html.append("</ol>\n");
        }
        if (page.page() > 0 || page.hasNext()) {
            html.append("<nav class=\"pager\" aria-label=\"Audit log pages\">");
            if (page.page() > 0) {
                html.append("<a rel=\"prev\" href=\"").append(Html.escape(site.link(link(page.page() - 1))))
                        .append("\">← Newer</a>");
            } else {
                html.append("<span></span>");
            }
            html.append("<span class=\"muted\">Page ").append(page.page() + 1).append("</span>");
            if (page.hasNext()) {
                html.append("<a rel=\"next\" href=\"").append(Html.escape(site.link(link(page.page() + 1))))
                        .append("\">Older →</a>");
            } else {
                html.append("<span></span>");
            }
            html.append("</nav>\n");
        }
        PageSupport.foot(html, site);
        request.response.setHeader("X-Frame-Options", "DENY");
        request.response.setHeader("Content-Security-Policy", "frame-ancestors 'none'");
        PageSupport.write(request, "200 OK", "text/html; charset=utf-8", html.toString());
    }

    private static String link(int page) {
        return page == 0 ? PATH : PATH + "?page=" + page;
    }

    /** The page number, or -1 when it isn't a valid one. A missing parameter is page 0. */
    static int parsePage(String value) {
        if (value == null || value.isEmpty()) return 0;
        if (!value.matches("[0-9]{1,7}")) return -1;
        int page = Integer.parseInt(value);
        return page > MAX_PAGE ? -1 : page;
    }

    private void entry(StringBuilder html, AuditLog.Entry entry) {
        html.append("<li class=\"audit-entry\"><div class=\"audit-head\">")
                .append("<time datetime=\"").append(Html.escape(entry.time().toString())).append("\">")
                .append(Html.escape(TIME.format(entry.time().atZone(zone)))).append("</time> ")
                .append("<span class=\"audit-user\">");
        if (entry.user() == null) {
            html.append("<span class=\"muted\">anonymous</span>");
        } else {
            html.append(Html.escape(entry.userName() == null ? entry.user() : entry.userName()))
                    .append(" <span class=\"muted\">(").append(Html.escape(entry.user())).append(")</span>");
        }
        html.append("</span> <code class=\"audit-action\">").append(Html.escape(entry.action())).append("</code>");
        if (entry.id() != null) {
            html.append(" <code class=\"audit-id\">").append(Html.escape(entry.id())).append("</code>");
        }
        html.append("</div>");
        if (!entry.before().isJsonNull() || !entry.after().isJsonNull()) {
            html.append("<details class=\"audit-values\"><summary>Before and after</summary><div class=\"audit-diff\">");
            value(html, "Before", entry.before());
            value(html, "After", entry.after());
            html.append("</div></details>");
        }
        html.append("</li>\n");
    }

    private static void value(StringBuilder html, String label, JsonElement value) {
        html.append("<div><h3 class=\"audit-label\">").append(label).append("</h3>");
        if (value.isJsonNull()) {
            html.append("<p class=\"muted\">(none)</p>");
        } else {
            html.append("<pre class=\"cd-json\">").append(Html.escape(PRETTY.toJson(value))).append("</pre>");
        }
        html.append("</div>");
    }
}
