package io.siggi.healthchecker.web.page;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import io.siggi.healthchecker.auth.SessionManager;
import io.siggi.healthchecker.config.EndpointStore;
import io.siggi.healthchecker.web.Html;
import io.siggi.healthchecker.web.site.Site;
import io.siggi.healthchecker.web.view.Viewer;
import io.siggi.http.HTTPRequest;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * The "Copy to dashboard" page shared by config-defined endpoints and categories. Before the copy it
 * explains what happens and shows the entry that will be written to {@code data/endpoints.json}; after
 * it, the removal instructions: the snippet to remove from {@code config.json} (as written there) and
 * the restart, plus "Cancel copy".
 * <p>The form posts {@code action=copy} or {@code action=cancel} with the CSRF token and the endpoints
 * {@code version}.
 */
final class CopyToDashboard {
    private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private CopyToDashboard() {
    }

    /**
     * What is being copied.
     *
     * @param kind      "service" or "category"
     * @param section   the {@code config.json} array it's in ({@code endpoints} or {@code categories})
     * @param written   the entry as written in {@code config.json}
     * @param resolved  the entry as it will be copied (placeholders substituted)
     * @param copy      the stored copy waiting for the restart, or {@code null} if not copied yet
     * @param warnings  extra steps for the instructions (e.g. config endpoints in a copied category)
     */
    record Subject(String kind, String id, String name, String section, JsonObject written, JsonObject resolved,
                   JsonObject copy, List<String> warnings) {
    }

    static String json(JsonObject o) {
        return PRETTY.toJson(o);
    }

    /**
     * Renders the page.
     *
     * @param action  the form's URL
     * @param back    where "Back" goes
     * @param notice  a status line (e.g. "Copied."), or {@code null}
     * @param error   an error line, or {@code null}
     */
    static void page(HTTPRequest request, Site site, Viewer viewer, String status, Subject subject, String action,
                     String back, String crumbs, int version, String notice, String error) throws Exception {
        StringBuilder html = new StringBuilder(8192);
        PageSupport.head(html, site, "Copy " + subject.name() + " to the dashboard · " + site.title(), false, viewer);
        html.append(crumbs)
                .append("<h2 class=\"ee-title\">Copy ").append(Html.escape(subject.name())).append(" to the dashboard</h2>\n");
        if (notice != null) {
            html.append("<p class=\"ee-notice\" role=\"status\">").append(Html.escape(notice)).append("</p>\n");
        }
        if (error != null) {
            html.append("<p class=\"form-error\" role=\"alert\">").append(Html.escape(error)).append("</p>\n");
        }
        String file = "<code>config.json</code>";
        String data = "<code>data/endpoints.json</code>";
        if (subject.copy() == null) {
            html.append("<p>This writes a copy of the ").append(subject.kind()).append("'s ").append(file)
                    .append(" entry to ").append(data).append(", so it can be edited in the dashboard. Nothing changes "
                            + "yet: the copy takes over the same id")
                    .append(subject.kind().equals("service") ? ", history and position" : " and position")
                    .append(" once you remove the entry from ").append(file).append(" and restart. Restarting without "
                            + "removing it stops HealthChecker at startup (an id defined in both), so finish the steps "
                            + "or cancel the copy before the next restart.</p>\n")
                    .append("<h3>Entry to copy</h3>\n<pre class=\"cd-json cd-copy\">")
                    .append(Html.escape(json(subject.resolved()))).append("</pre>\n");
            if (!subject.written().equals(subject.resolved())) {
                html.append("<p class=\"muted cd-placeholders\">The entry uses <code>${env:…}</code> placeholders. ")
                        .append(data).append(" isn't substituted, so the copy stores their current values.</p>\n");
            }
            form(html, viewer, action, version, "copy", "button button-primary", "Copy to dashboard");
            html.append("<p><a class=\"button\" href=\"").append(Html.escape(back)).append("\">Back</a></p>\n");
        } else {
            html.append("<p>A copy is stored in ").append(data).append(" and waits for the restart.</p>\n")
                    .append("<h3>To finish</h3>\n<ol class=\"cd-steps\">\n<li>Remove this entry from the <code>")
                    .append(Html.escape(subject.section())).append("</code> array in ").append(file).append(":")
                    .append("<pre class=\"cd-json cd-remove\">").append(Html.escape(json(subject.written())))
                    .append("</pre></li>\n");
            for (String warning : subject.warnings()) {
                html.append("<li>").append(warning).append("</li>\n");
            }
            html.append("<li>Restart HealthChecker. The copy then takes over, and it can be edited here.</li>\n</ol>\n")
                    .append("<h3>Stored copy</h3>\n<pre class=\"cd-json cd-copy\">")
                    .append(Html.escape(json(subject.copy()))).append("</pre>\n")
                    .append("<p class=\"muted\">Changed your mind? Cancel the copy before restarting; ")
                    .append(file).append(" then stays in charge.</p>\n");
            form(html, viewer, action, version, "cancel", "button", "Cancel copy");
            html.append("<p><a class=\"button\" href=\"").append(Html.escape(back)).append("\">Back</a></p>\n");
        }
        PageSupport.foot(html, site);
        request.response.setHeader("X-Frame-Options", "DENY");
        request.response.setHeader("Content-Security-Policy", "frame-ancestors 'none'");
        PageSupport.write(request, status, "text/html; charset=utf-8", html.toString());
    }

    private static void form(StringBuilder html, Viewer viewer, String action, int version, String value,
                             String buttonClass, String label) {
        html.append("<form class=\"endpoint-form cd-form\" method=\"post\" action=\"").append(Html.escape(action))
                .append("\"><input type=\"hidden\" name=\"").append(SessionManager.CSRF_FIELD).append("\" value=\"")
                .append(Html.escape(viewer.csrfToken() == null ? "" : viewer.csrfToken())).append("\">")
                .append("<input type=\"hidden\" name=\"version\" value=\"").append(version).append("\">")
                .append("<div class=\"ee-actions\"><button type=\"submit\" name=\"action\" value=\"").append(value)
                .append("\" class=\"").append(buttonClass).append("\">").append(Html.escape(label))
                .append("</button></div></form>\n");
    }

    /** What a POST to the copy page asked for. */
    interface Operation {
        void run(int version) throws EndpointStore.InvalidEntryException, EndpointStore.VersionConflictException,
                IOException;
    }

    /**
     * Handles a POST: runs {@code copy} or {@code cancel} (by the {@code action} field) and redirects to
     * {@code done + "?copied=1"} / {@code "?cancelled=1"}. Returns the error to show (with its status),
     * or {@code null} when it redirected.
     */
    static String[] submit(HTTPRequest request, Site site, String done, Operation copy, Operation cancel)
            throws Exception {
        Map<String, String> post = request.post();
        if (post == null) post = Map.of();
        String action = post.getOrDefault("action", "");
        Operation operation = switch (action) {
            case "copy" -> copy;
            case "cancel" -> cancel;
            default -> null;
        };
        if (operation == null) return new String[]{"400 Bad Request", "Unknown action."};
        try {
            operation.run(parseVersion(post.getOrDefault("version", "")));
        } catch (EndpointStore.VersionConflictException e) {
            return new String[]{"409 Conflict", "Someone else changed the services in the meantime. Check the page "
                    + "and try again."};
        } catch (EndpointStore.InvalidEntryException e) {
            return new String[]{"400 Bad Request", String.join("; ", e.errors())};
        } catch (IOException e) {
            System.getLogger(CopyToDashboard.class.getName()).log(System.Logger.Level.ERROR, "Copy to dashboard failed", e);
            return new String[]{"500 Internal Server Error", "Saving failed. Try again later."};
        }
        request.response.setHeader("303 See Other");
        request.response.setHeader("Location", site.link(done + (action.equals("copy") ? "?copied=1" : "?cancelled=1")));
        request.response.setHeader("Cache-Control", "no-store");
        request.response.setHeader("Content-Length", "0");
        request.response.write(new byte[0]);
        return null;
    }

    static int parseVersion(String value) {
        try {
            int v = Integer.parseInt(value.strip());
            return v >= 0 ? v : -1;
        } catch (NumberFormatException e) {
            return -1; // never the current version → 409
        }
    }

    /** The notice for {@code ?copied=1} / {@code ?cancelled=1}. */
    static String notice(HTTPRequest request) {
        if ("1".equals(request.get().get("copied"))) return "Copied to data/endpoints.json.";
        if ("1".equals(request.get().get("cancelled"))) return "The copy was removed. config.json stays in charge.";
        return null;
    }
}
