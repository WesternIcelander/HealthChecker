package io.siggi.healthchecker.web.page;

import io.siggi.healthchecker.audit.AuditLog;
import io.siggi.healthchecker.auth.Permission;
import io.siggi.healthchecker.auth.Permissions;
import io.siggi.healthchecker.auth.SessionManager;
import io.siggi.healthchecker.history.EventNote;
import io.siggi.healthchecker.history.HistoryEvent;
import io.siggi.healthchecker.history.HistoryStore;
import io.siggi.healthchecker.web.Html;
import io.siggi.healthchecker.web.site.Site;
import io.siggi.healthchecker.web.view.NoteView;
import io.siggi.healthchecker.web.view.ViewFilter;
import io.siggi.http.HTTPRequest;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;

/**
 * Event edits on the service pages, for users with {@code events.annotate}: renaming an event with
 * {@code POST /service/{id}/event/{eventId}/title} (form fields {@code title}, {@code csrf} and
 * {@code next}). An empty (or blank) title resets it to the default.
 *
 * <p>Notes: {@code POST /service/{id}/event/{eventId}/notes} adds one (fields {@code text},
 * {@code visibility} = {@code public} or {@code private}), {@code POST .../note/{noteId}} edits one
 * (optional {@code text} and {@code visibility}; a missing field is kept, so a form with only
 * {@code visibility} toggles it) and {@code POST .../note/{noteId}/delete} deletes one. A note the
 * viewer can't see (a private note for a user without {@code view.private}) is {@code 404}.
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
    /** Longest accepted note text, in characters (code points). */
    public static final int MAX_NOTE_LENGTH = 10_000;

    private final Permissions permissions;
    private final SessionManager sessions;
    private final HistoryStore history;
    private volatile AuditLog audit = AuditLog.disabled();

    public EventEditor(Permissions permissions, SessionManager sessions, HistoryStore history) {
        this.permissions = permissions;
        this.sessions = sessions;
        this.history = history;
    }

    /** Records renames and note changes in {@code audit}. */
    public EventEditor audit(AuditLog audit) {
        this.audit = audit;
        return this;
    }

    /** The note {@code noteId} of an event, or {@code null}. */
    private EventNote findNote(String endpointId, String eventId, String noteId) {
        return history.event(endpointId, eventId).flatMap(e -> e.notes().stream()
                .filter(n -> n.id().equals(noteId)).findFirst()).orElse(null);
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
            // Under the history's lock, so "before" is what the rename replaced.
            synchronized (history) {
                String before = history.event(endpointId, eventId).map(HistoryEvent::title).orElse(null);
                renamed = history.rename(endpointId, eventId, title.isEmpty() ? null : title);
                if (renamed.isPresent()) {
                    Audits.record(audit, permissions, request, "event.rename", endpointId + "/" + eventId,
                            Audits.title(before), Audits.title(renamed.get().title()));
                }
            }
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

    // ---- notes ----

    /**
     * The edit, toggle and delete forms of one note the viewer can see.
     *
     * @param next where to return after saving (a path on this site)
     */
    void noteForms(StringBuilder html, Site site, String endpointId, String eventId, NoteView note, String csrfToken,
                   String next) {
        String action = site.link("/service/" + endpointId + "/event/" + eventId + "/note/" + note.id());
        // Without view.private only public notes are visible, and their visibility isn't in the view.
        boolean isPrivate = note.visibility() == EventNote.Visibility.PRIVATE;
        String textId = "note-text-" + note.id();
        html.append("<div class=\"note-actions\">")
                .append("<details class=\"note-edit\"><summary>Edit</summary>");
        formStart(html, action, csrfToken, next, "note-form");
        html.append("<label for=\"").append(Html.escape(textId)).append("\">Note</label>")
                .append("<textarea id=\"").append(Html.escape(textId)).append("\" name=\"text\" rows=\"4\" maxlength=\"")
                .append(MAX_NOTE_LENGTH).append("\" required>").append(Html.escape(note.text())).append("</textarea>");
        visibilityField(html, "note-vis-" + note.id(), isPrivate);
        html.append("<button type=\"submit\">Save</button></form></details>");
        formStart(html, action, csrfToken, next, "note-toggle");
        html.append("<input type=\"hidden\" name=\"visibility\" value=\"").append(isPrivate ? "public" : "private")
                .append("\"><button type=\"submit\">").append(isPrivate ? "Make public" : "Make private")
                .append("</button></form>");
        formStart(html, action + "/delete", csrfToken, next, "note-delete");
        html.append("<button type=\"submit\" data-confirm=\"Delete this note?\">Delete</button></form></div>\n");
    }

    /** The form for adding a note to an event (private by default). */
    void addNoteForm(StringBuilder html, Site site, String endpointId, String eventId, String csrfToken, String next) {
        String textId = "new-note-" + eventId;
        html.append("<details class=\"note-add\"><summary>Add note</summary>");
        formStart(html, site.link("/service/" + endpointId + "/event/" + eventId + "/notes"), csrfToken, next, "note-form");
        html.append("<label for=\"").append(Html.escape(textId)).append("\">Note</label>")
                .append("<textarea id=\"").append(Html.escape(textId)).append("\" name=\"text\" rows=\"4\" maxlength=\"")
                .append(MAX_NOTE_LENGTH).append("\" required></textarea>");
        visibilityField(html, "new-note-vis-" + eventId, true);
        html.append("<button type=\"submit\">Add note</button></form></details>\n");
    }

    private static void formStart(StringBuilder html, String action, String csrfToken, String next, String cls) {
        html.append("<form class=\"").append(cls).append("\" method=\"post\" action=\"").append(Html.escape(action))
                .append("\"><input type=\"hidden\" name=\"").append(SessionManager.CSRF_FIELD).append("\" value=\"")
                .append(Html.escape(csrfToken == null ? "" : csrfToken)).append("\">")
                .append("<input type=\"hidden\" name=\"next\" value=\"").append(Html.escape(next)).append("\">");
    }

    private static void visibilityField(StringBuilder html, String id, boolean isPrivate) {
        html.append("<label for=\"").append(Html.escape(id)).append("\">Visible to</label>")
                .append("<select id=\"").append(Html.escape(id)).append("\" name=\"visibility\">")
                .append("<option value=\"private\"").append(isPrivate ? " selected" : "").append(">Signed-in users (private)</option>")
                .append("<option value=\"public\"").append(isPrivate ? "" : " selected").append(">Everyone (public)</option>")
                .append("</select>");
    }

    /** Handles adding a note to an event the request may see. */
    void addNote(HTTPRequest request, ServicePage.Service service, String eventId) throws Exception {
        request.allowedMethods("POST");
        if (!checkAccess(request, service)) return;
        Map<String, String> post = request.post();
        String text = noteText(request, post);
        if (text == null) return;
        EventNote.Visibility visibility = visibility(request, post, EventNote.Visibility.PRIVATE);
        if (visibility == null) return;
        String endpointId = service.endpoint().id();
        Optional<EventNote> added;
        try {
            synchronized (history) {
                added = history.addNote(endpointId, eventId, service.viewer().userName(), text, visibility);
                if (added.isPresent()) {
                    Audits.record(audit, permissions, request, "note.add",
                            endpointId + "/" + eventId + "/" + added.get().id(), null, Audits.note(added.get()));
                }
            }
        } catch (IOException e) {
            saveFailed(request, "Adding a note to event " + eventId + " of " + endpointId + " failed", e);
            return;
        }
        if (added.isEmpty()) return; // gone in the meantime → 404
        redirect(request, service, eventId, post, "#note-" + added.get().id());
    }

    /** Handles editing (text and/or visibility) a note the request may see. */
    void editNote(HTTPRequest request, ServicePage.Service service, String eventId, String noteId) throws Exception {
        request.allowedMethods("POST");
        if (!checkAccess(request, service)) return;
        Map<String, String> post = request.post();
        String text = null;
        if (post != null && post.containsKey("text")) {
            text = noteText(request, post);
            if (text == null) return;
        }
        EventNote.Visibility visibility = visibility(request, post, null);
        if (visibility == null && post != null && post.containsKey("visibility")) return; // 400 written
        String endpointId = service.endpoint().id();
        Optional<EventNote> edited;
        try {
            synchronized (history) {
                EventNote before = findNote(endpointId, eventId, noteId);
                edited = history.editNote(endpointId, eventId, noteId, text, visibility);
                if (edited.isPresent()) {
                    Audits.record(audit, permissions, request, "note.edit", endpointId + "/" + eventId + "/" + noteId,
                            Audits.note(before), Audits.note(edited.get()));
                }
            }
        } catch (IOException e) {
            saveFailed(request, "Editing note " + noteId + " of event " + eventId + " of " + endpointId + " failed", e);
            return;
        }
        if (edited.isEmpty()) return; // gone in the meantime → 404
        redirect(request, service, eventId, post, "#note-" + noteId);
    }

    /** Handles deleting a note the request may see. */
    void deleteNote(HTTPRequest request, ServicePage.Service service, String eventId, String noteId) throws Exception {
        request.allowedMethods("POST");
        if (!checkAccess(request, service)) return;
        Map<String, String> post = request.post();
        String endpointId = service.endpoint().id();
        boolean deleted;
        try {
            synchronized (history) {
                EventNote before = findNote(endpointId, eventId, noteId);
                deleted = history.deleteNote(endpointId, eventId, noteId);
                if (deleted) {
                    Audits.record(audit, permissions, request, "note.delete", endpointId + "/" + eventId + "/" + noteId,
                            Audits.note(before), null);
                }
            }
        } catch (IOException e) {
            saveFailed(request, "Deleting note " + noteId + " of event " + eventId + " of " + endpointId + " failed", e);
            return;
        }
        if (!deleted) return; // gone in the meantime → 404
        redirect(request, service, eventId, post, "");
    }

    /** Permission, then CSRF; writes the answer and returns {@code false} when either fails. */
    private boolean checkAccess(HTTPRequest request, ServicePage.Service service) throws Exception {
        return permissions.require(request, service.site(), Permission.EVENTS_ANNOTATE) && sessions.requireCsrf(request);
    }

    /**
     * The note text from the form: line breaks normalised to {@code \n}, leading and trailing
     * whitespace removed. Writes a {@code 400} and returns {@code null} when it's empty, too long or
     * contains control characters other than newline and tab.
     */
    private static String noteText(HTTPRequest request, Map<String, String> post) throws Exception {
        String text = post == null ? null : post.get("text");
        text = text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n').strip();
        if (text.isEmpty()) {
            badRequest(request, "The note can't be empty.");
            return null;
        }
        if (text.codePointCount(0, text.length()) > MAX_NOTE_LENGTH) {
            badRequest(request, "A note can be at most " + MAX_NOTE_LENGTH + " characters long.");
            return null;
        }
        if (text.codePoints().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\t')) {
            badRequest(request, "The note can't contain control characters.");
            return null;
        }
        return text;
    }

    /**
     * The {@code visibility} field, or {@code fallback} when it's missing. Writes a {@code 400} and
     * returns {@code null} for any other value than {@code public} or {@code private}.
     */
    private static EventNote.Visibility visibility(HTTPRequest request, Map<String, String> post,
                                                   EventNote.Visibility fallback) throws Exception {
        String value = post == null ? null : post.get("visibility");
        if (value == null) return fallback;
        switch (value) {
            case "public":
                return EventNote.Visibility.PUBLIC;
            case "private":
                return EventNote.Visibility.PRIVATE;
            default:
                badRequest(request, "The visibility must be \"public\" or \"private\".");
                return null;
        }
    }

    private static void badRequest(HTTPRequest request, String message) throws Exception {
        PageSupport.write(request, "400 Bad Request", "text/plain; charset=utf-8", message + "\n");
    }

    private static void saveFailed(HTTPRequest request, String what, IOException e) throws Exception {
        System.getLogger(EventEditor.class.getName()).log(System.Logger.Level.ERROR, what, e);
        PageSupport.write(request, "500 Internal Server Error", "text/plain; charset=utf-8",
                "Saving failed. Try again later.\n");
    }

    /** {@code 303} to the form's {@code next} (a path on this site), else to the event permalink plus {@code fragment}. */
    private static void redirect(HTTPRequest request, ServicePage.Service service, String eventId,
                                 Map<String, String> post, String fragment) throws Exception {
        String next = post == null ? null : post.get("next");
        String target = next == null || next.isEmpty()
                ? "/service/" + service.endpoint().id() + "/event/" + eventId + fragment
                : LoginPage.safeNext(next);
        request.response.setHeader("303 See Other");
        request.response.setHeader("Location", service.site().link(target));
        request.response.setHeader("Cache-Control", "no-store");
        request.response.setHeader("Content-Length", "0");
        request.response.write(new byte[0]);
    }
}
