package io.siggi.healthchecker.web.page;

import io.siggi.healthchecker.auth.Permission;
import io.siggi.healthchecker.auth.Permissions;
import io.siggi.healthchecker.auth.SessionManager;
import io.siggi.healthchecker.history.HistoryEvent;
import io.siggi.healthchecker.history.HistoryStore;
import io.siggi.healthchecker.web.Html;
import io.siggi.healthchecker.web.site.Site;
import io.siggi.healthchecker.web.view.ViewFilter;
import io.siggi.http.HTTPRequest;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;

/**
 * Event edits on the service pages, for users with {@code events.annotate}: renaming an event with
 * {@code POST /service/{id}/event/{eventId}/title} (form fields {@code title}, {@code csrf} and
 * {@code next}). An empty (or blank) title resets it to the default.
 * <ul>
 *   <li>The service page's {@code 404} rules apply first (so a private endpoint stays hidden from users
 *       without {@code view.private}), then the permission (anonymous visitors are sent to
 *       {@code /login}, others get {@code 403}), then the CSRF token.</li>
 *   <li>The title is trimmed; more than {@value #MAX_TITLE_LENGTH} characters or a control
 *       character is {@code 400}.</li>
 *   <li>The change is written to the events file before answering {@code 303} to {@code next}
 *       (a path on this site, else the event's permalink).</li>
 * </ul>
 */
public final class EventEditor {
    /** Longest accepted custom title, in characters (code points). */
    public static final int MAX_TITLE_LENGTH = 200;

    private final Permissions permissions;
    private final SessionManager sessions;
    private final HistoryStore history;

    public EventEditor(Permissions permissions, SessionManager sessions, HistoryStore history) {
        this.permissions = permissions;
        this.sessions = sessions;
        this.history = history;
    }

    /** Whether the request's user may rename events and edit notes (for showing the forms). */
    boolean canAnnotate(HTTPRequest request) {
        return permissions.has(request, Permission.EVENTS_ANNOTATE);
    }

    /**
     * The rename form of one event. It's prefilled with the stored custom title (empty for the
     * default title, which is the placeholder).
     *
     * @param next where to return after saving (a path on this site)
     */
    void renameForm(StringBuilder html, Site site, String endpointId, String endpointName, String eventId,
                    String csrfToken, String next) {
        String custom = history.event(endpointId, eventId).map(HistoryEvent::title).orElse(null);
        String inputId = "rename-" + eventId;
        html.append("<form class=\"rename-form\" method=\"post\" action=\"")
                .append(Html.escape(site.link("/service/" + endpointId + "/event/" + eventId + "/title")))
                .append("\"><input type=\"hidden\" name=\"").append(SessionManager.CSRF_FIELD).append("\" value=\"")
                .append(Html.escape(csrfToken == null ? "" : csrfToken)).append("\">")
                .append("<input type=\"hidden\" name=\"next\" value=\"").append(Html.escape(next)).append("\">")
                .append("<label for=\"").append(Html.escape(inputId)).append("\">Title</label> ")
                .append("<input type=\"text\" id=\"").append(Html.escape(inputId)).append("\" name=\"title\" maxlength=\"")
                .append(MAX_TITLE_LENGTH).append("\" value=\"").append(Html.escape(custom == null ? "" : custom))
                .append("\" placeholder=\"").append(Html.escape(ViewFilter.defaultTitle(endpointName))).append("\"> ")
                .append("<button type=\"submit\">Rename</button>")
                .append("<span class=\"muted rename-help\"> Leave empty for the default title.</span></form>\n");
    }

    /** Handles the rename POST for an event of a service the request may see. */
    void rename(HTTPRequest request, ServicePage.Service service, String eventId) throws Exception {
        if (history.event(service.endpoint().id(), eventId).isEmpty()) return; // nothing written → 404
        request.allowedMethods("POST");
        Site site = service.site();
        if (!permissions.require(request, site, Permission.EVENTS_ANNOTATE)) return;
        if (!sessions.requireCsrf(request)) return;
        String endpointId = service.endpoint().id();
        Map<String, String> post = request.post();
        String title = post == null ? null : post.get("title");
        title = title == null ? "" : title.strip();
        if (title.codePointCount(0, title.length()) > MAX_TITLE_LENGTH) {
            PageSupport.write(request, "400 Bad Request", "text/plain; charset=utf-8",
                    "The title can be at most " + MAX_TITLE_LENGTH + " characters long.\n");
            return;
        }
        if (title.codePoints().anyMatch(Character::isISOControl)) {
            PageSupport.write(request, "400 Bad Request", "text/plain; charset=utf-8",
                    "The title can't contain control characters.\n");
            return;
        }
        Optional<HistoryEvent> renamed;
        try {
            renamed = history.rename(endpointId, eventId, title.isEmpty() ? null : title);
        } catch (IOException e) {
            System.getLogger(EventEditor.class.getName()).log(System.Logger.Level.ERROR,
                    "Renaming event " + eventId + " of " + endpointId + " failed", e);
            PageSupport.write(request, "500 Internal Server Error", "text/plain; charset=utf-8",
                    "Saving failed. Try again later.\n");
            return;
        }
        if (renamed.isEmpty()) return; // gone in the meantime → 404
        String permalink = "/service/" + endpointId + "/event/" + eventId;
        String next = post == null ? null : post.get("next");
        String target = next == null || next.isEmpty() ? permalink : LoginPage.safeNext(next);
        request.response.setHeader("303 See Other");
        request.response.setHeader("Location", site.link(target));
        request.response.setHeader("Cache-Control", "no-store");
        request.response.setHeader("Content-Length", "0");
        request.response.write(new byte[0]);
    }
}
