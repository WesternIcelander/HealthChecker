package io.siggi.healthchecker.web.page;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.siggi.healthchecker.auth.Permission;
import io.siggi.healthchecker.auth.Permissions;
import io.siggi.healthchecker.auth.SessionManager;
import io.siggi.healthchecker.auth.User;
import io.siggi.healthchecker.history.EventNote;
import io.siggi.healthchecker.maintenance.MaintenanceScheduler;
import io.siggi.healthchecker.maintenance.MaintenanceStore;
import io.siggi.healthchecker.maintenance.MaintenanceWindow;
import io.siggi.healthchecker.maintenance.OccurrenceChange;
import io.siggi.healthchecker.maintenance.Recurrence;
import io.siggi.healthchecker.schedule.Endpoint;
import io.siggi.healthchecker.web.Durations;
import io.siggi.healthchecker.web.Html;
import io.siggi.healthchecker.web.WebServer;
import io.siggi.healthchecker.web.layout.ResolvedLayout;
import io.siggi.healthchecker.web.site.Site;
import io.siggi.healthchecker.web.site.SiteResolver;
import io.siggi.healthchecker.web.view.NoteView;
import io.siggi.healthchecker.web.view.ViewFilter;
import io.siggi.healthchecker.web.view.Viewer;
import io.siggi.http.HTTPRequest;

import java.io.IOException;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import static io.siggi.healthchecker.web.page.PageSupport.write;

/**
 * Scheduled maintenance in the dashboard:
 * <ul>
 *   <li>{@code GET /maintenance/new[?service=id]}: the "Schedule maintenance" form, {@code POST} creates the
 *       window (one-off or recurring) and redirects to its page.</li>
 *   <li>{@code GET /maintenance/{id}}: one window, occurrence or series: status, times, affected services,
 *       description and notes, plus the edit form and the cancel / end now / extend / skip actions for users
 *       with {@code maintenance.manage}. {@code POST} there saves an edit; {@code .../cancel}, {@code .../end},
 *       {@code .../extend}, {@code .../occurrence/{date}/skip|restore|edit} act; {@code .../notes},
 *       {@code .../note/{noteId}} and {@code .../note/{noteId}/delete} change notes
 *       ({@code events.annotate}).</li>
 *   <li>{@code GET /api/maintenance?from=…&to=…} and {@code GET /api/maintenance/{id}}: the same as JSON.</li>
 * </ul>
 * What a viewer sees follows {@link MaintenanceViews}: a window hidden from them is {@code 404}, everywhere,
 * before any permission check. Missing permission: anonymous visitors go to {@code /login}, others get
 * {@code 403}. Every POST needs the CSRF token. Edits name the window's version ({@code 409} when stale).
 */
public final class MaintenancePage {
    static final String PREFIX = "/maintenance";
    static final String API = "/api/maintenance";
    /** Default length of {@code /api/maintenance} without {@code from}/{@code to}. */
    static final int API_DEFAULT_DAYS = 30;
    /** The longest range one {@code /api/maintenance} request may cover. */
    static final int API_MAX_DAYS = 400;
    /** How many upcoming occurrences a series page lists. */
    static final int SERIES_OCCURRENCES = 10;
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9-]{1,64}(_[0-9]{4}-[0-9]{2}-[0-9]{2})?");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter INPUT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");
    private static final String ENDPOINT_PREFIX = "endpoint.";
    private static final String CATEGORY_PREFIX = "category.";
    private static final String CHANNEL_PREFIX = "channel.";
    private static final String WEEKDAY_PREFIX = "weekday.";

    private final SiteResolver sites;
    private final Permissions permissions;
    private final SessionManager sessions;
    private final MaintenanceViews views;
    private final MaintenanceScheduler scheduler;
    private final Supplier<? extends Collection<String>> channels;
    private final ViewFilter filter = new ViewFilter();

    /**
     * @param scheduler applies "End now" and "Extend" at once, or {@code null} to only write them
     * @param channels  the configured notification channels
     */
    public MaintenancePage(SiteResolver sites, Permissions permissions, SessionManager sessions,
                           MaintenanceViews views, MaintenanceScheduler scheduler,
                           Supplier<? extends Collection<String>> channels) {
        this.sites = sites;
        this.permissions = permissions;
        this.sessions = sessions;
        this.views = views;
        this.scheduler = scheduler;
        this.channels = channels;
    }

    public MaintenanceViews views() {
        return views;
    }

    public void register(WebServer server) {
        server.register(PREFIX + "/", this::handle);
        server.register(API, this::api);
        server.register(API + "/", this::api);
    }

    /** Whether the request's user may schedule, edit and cancel windows. */
    boolean canManage(HTTPRequest request) {
        return permissions.has(request, Permission.MAINTENANCE_MANAGE);
    }

    private boolean canAnnotate(HTTPRequest request) {
        return permissions.has(request, Permission.EVENTS_ANNOTATE);
    }

    private ZoneId zone() {
        return views.zone();
    }

    // ---- shared rendering (also used by the main page and the service pages) ----

    static String statusId(MaintenanceWindow.Status status) {
        return status.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    static String statusLabel(MaintenanceWindow.Status status) {
        return switch (status) {
            case SCHEDULED -> "Scheduled";
            case IN_PROGRESS -> "In progress";
            case COMPLETED -> "Completed";
            case CANCELLED -> "Cancelled";
        };
    }

    private String time(Instant instant) {
        return TIME.format(instant.atZone(zone()));
    }

    private String timeTag(Instant instant) {
        return "<time datetime=\"" + instant + "\">" + time(instant) + "</time>";
    }

    /** {@code 2026-11-20 02:00 – 04:00} (same day) or with both dates. */
    String range(MaintenanceWindow w) {
        ZonedDateTime s = w.start().atZone(zone());
        ZonedDateTime e = w.end().atZone(zone());
        String end = s.toLocalDate().equals(e.toLocalDate())
                ? DateTimeFormatter.ofPattern("HH:mm").format(e) : TIME.format(e);
        return "<time datetime=\"" + w.start() + "\">" + TIME.format(s) + "</time> – <time datetime=\"" + w.end()
                + "\">" + end + "</time>";
    }

    /**
     * The "Upcoming maintenance" box: each window with its title (linking to its page), status, time
     * range and affected services; for managers an "Edit" link and, while scheduled, a "Cancel" button.
     *
     * @param next where the cancel button returns to (a path on this site)
     */
    void upcomingBox(StringBuilder html, Site site, List<MaintenanceViews.Visible> windows, HTTPRequest request,
                     String csrfToken, String next) {
        if (windows.isEmpty()) return;
        boolean manage = canManage(request);
        Instant now = views.now();
        html.append("<section class=\"maintenance-box\" aria-labelledby=\"maintenance-box-title\">\n")
                .append("<h2 id=\"maintenance-box-title\" class=\"maintenance-box-title\">Upcoming maintenance</h2>\n")
                .append("<ul class=\"maintenance-list\">\n");
        for (MaintenanceViews.Visible v : windows) {
            MaintenanceWindow w = v.window();
            MaintenanceWindow.Status status = w.status(now);
            html.append("<li class=\"maintenance-item maintenance-").append(statusId(status)).append("\" data-maintenance=\"")
                    .append(Html.escape(w.id())).append("\">\n<p class=\"maintenance-head\"><a class=\"maintenance-title\" href=\"")
                    .append(Html.escape(site.link(PREFIX + "/" + w.id()))).append("\">").append(Html.escape(w.title()))
                    .append("</a> <span class=\"badge badge-maintenance\">").append(statusLabel(status)).append("</span></p>\n")
                    .append("<p class=\"maintenance-when\">").append(range(w)).append("</p>\n");
            affectedHtml(html, site, v.affected());
            if (manage) {
                html.append("<p class=\"maintenance-actions\"><a class=\"button\" href=\"")
                        .append(Html.escape(site.link(PREFIX + "/" + w.id() + "#edit"))).append("\">Edit</a>");
                if (status == MaintenanceWindow.Status.SCHEDULED && w.seriesId() == null) {
                    html.append(' ');
                    postButton(html, site.link(PREFIX + "/" + w.id() + "/cancel"), csrfToken, next,
                            Map.of("version", Integer.toString(w.version())), "Cancel", "button-danger",
                            "Cancel this maintenance?");
                } else if (status == MaintenanceWindow.Status.SCHEDULED) {
                    html.append(' ');
                    postButton(html, site.link(PREFIX + "/" + w.seriesId() + "/occurrence/" + w.occurrence() + "/skip"),
                            csrfToken, next, Map.of("version", Integer.toString(w.version())), "Skip", "button-danger",
                            "Skip this occurrence?");
                }
                html.append("</p>\n");
            }
            html.append("</li>\n");
        }
        html.append("</ul>\n</section>\n");
    }

    private static void affectedHtml(StringBuilder html, Site site, List<MaintenanceViews.Affected> affected) {
        html.append("<p class=\"maintenance-affected\"><span class=\"muted\">Affects:</span> ");
        if (affected.isEmpty()) {
            html.append("<span class=\"muted\">no current services</span>");
        }
        for (int i = 0; i < affected.size(); i++) {
            MaintenanceViews.Affected a = affected.get(i);
            if (i > 0) html.append(", ");
            html.append("<a href=\"").append(Html.escape(site.link("/service/" + a.id()))).append("\">")
                    .append(Html.escape(a.name())).append("</a>");
        }
        html.append("</p>\n");
    }

    /**
     * The maintenance windows on one day of a service (day page and popover), listed apart from the events.
     */
    void dayWindowsHtml(StringBuilder html, Site site, List<MaintenanceWindow> windows) {
        if (windows.isEmpty()) return;
        Instant now = views.now();
        html.append("<h4 class=\"day-maintenance-title\">Planned maintenance</h4>\n<ul class=\"day-maintenance\">\n");
        for (MaintenanceWindow w : windows) {
            MaintenanceWindow.Status status = w.status(now);
            html.append("<li class=\"day-maintenance-item\" data-maintenance=\"").append(Html.escape(w.id()))
                    .append("\"><a href=\"").append(Html.escape(site.link(PREFIX + "/" + w.id()))).append("\">")
                    .append(Html.escape(w.title())).append("</a> <span class=\"badge badge-maintenance\">")
                    .append(statusLabel(status)).append("</span> <span class=\"muted\">").append(range(w))
                    .append("</span></li>\n");
        }
        html.append("</ul>\n");
    }

    private static void postButton(StringBuilder html, String action, String csrfToken, String next,
                                   Map<String, String> fields, String label, String cls, String confirm) {
        html.append("<form class=\"inline-form\" method=\"post\" action=\"").append(Html.escape(action)).append("\">");
        hidden(html, SessionManager.CSRF_FIELD, csrfToken == null ? "" : csrfToken);
        if (next != null) hidden(html, "next", next);
        for (Map.Entry<String, String> f : fields.entrySet()) hidden(html, f.getKey(), f.getValue());
        html.append("<button type=\"submit\" class=\"button ").append(cls).append('"');
        if (confirm != null) html.append(" data-confirm=\"").append(Html.escape(confirm)).append('"');
        html.append('>').append(Html.escape(label)).append("</button></form>");
    }

    private static void hidden(StringBuilder html, String name, String value) {
        html.append("<input type=\"hidden\" name=\"").append(Html.escape(name)).append("\" value=\"")
                .append(Html.escape(value)).append("\">");
    }

    // ---- JSON (also used by /api/status) ----

    /** The notes of a window as the viewer may see them: all for {@code view.private}, else public ones only. */
    static List<NoteView> notes(Viewer viewer, MaintenanceWindow w) {
        List<NoteView> out = new ArrayList<>();
        for (EventNote n : w.notes()) {
            if (viewer.viewPrivate()) {
                out.add(new NoteView(n.id(), n.author(), n.createdAt(), n.editedAt(), n.text(), n.visibility()));
            } else if (n.visibility() == EventNote.Visibility.PUBLIC) {
                out.add(new NoteView(n.id(), n.author(), n.createdAt(), n.editedAt(), n.text(), null));
            }
        }
        return out;
    }

    /**
     * A window as JSON: id, title, description, status, start, end, recurrence links and the affected
     * services the viewer can see; with {@code withNotes} its visible notes. Endpoint and category ids,
     * channels, the creator and the rule only for {@code view.private}.
     */
    static JsonObject windowJson(MaintenanceViews.Visible v, Viewer viewer, Instant now, boolean withNotes) {
        MaintenanceWindow w = v.window();
        JsonObject json = new JsonObject();
        json.addProperty("id", w.id());
        json.addProperty("title", w.title());
        if (w.description() != null) json.addProperty("description", w.description());
        json.addProperty("status", w.status(now).name().toLowerCase(Locale.ROOT));
        json.addProperty("start", w.start().toString());
        json.addProperty("end", w.end().toString());
        json.addProperty("series", w.isSeries());
        if (w.seriesId() != null) {
            json.addProperty("seriesId", w.seriesId());
            json.addProperty("occurrence", w.occurrence().toString());
        }
        JsonArray affected = new JsonArray();
        for (MaintenanceViews.Affected a : v.affected()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", a.id());
            o.addProperty("name", a.name());
            affected.add(o);
        }
        json.add("affected", affected);
        if (viewer.viewPrivate()) {
            JsonArray endpoints = new JsonArray();
            w.endpoints().forEach(endpoints::add);
            json.add("endpoints", endpoints);
            JsonArray categories = new JsonArray();
            w.categories().forEach(categories::add);
            json.add("categories", categories);
            JsonArray channels = new JsonArray();
            w.notifyChannels().forEach(channels::add);
            json.add("notifyChannels", channels);
            if (w.createdByName() != null) json.addProperty("createdBy", w.createdByName());
            if (w.cancelledAt() != null) json.addProperty("cancelledAt", w.cancelledAt().toString());
            if (w.recurrence() != null) json.add("recurrence", recurrenceJson(w.recurrence()));
        }
        if (withNotes) {
            JsonArray notes = new JsonArray();
            for (NoteView n : notes(viewer, w)) notes.add(JsonViews.note(n));
            json.add("notes", notes);
        }
        return json;
    }

    private static JsonObject recurrenceJson(Recurrence r) {
        JsonObject o = new JsonObject();
        o.addProperty("frequency", r.frequency().name().toLowerCase(Locale.ROOT));
        if (!r.weekdays().isEmpty()) {
            JsonArray days = new JsonArray();
            r.weekdays().forEach(d -> days.add(d.name().toLowerCase(Locale.ROOT)));
            o.add("weekdays", days);
        }
        if (r.dayOfMonth() != 0) o.addProperty("dayOfMonth", r.dayOfMonth());
        if (r.week() != 0) o.addProperty("week", r.week());
        if (r.weekday() != null) o.addProperty("weekday", r.weekday().name().toLowerCase(Locale.ROOT));
        if (r.until() != null) o.addProperty("until", r.until().toString());
        return o;
    }

    private void api(HTTPRequest request) throws Exception {
        String path = request.path();
        Optional<Site> site = sites.resolve(request);
        if (site.isEmpty()) return;
        Viewer viewer = permissions.viewer(request);
        Instant now = views.now();
        if (path.equals(API)) {
            request.allowedMethods("GET");
            apiList(request, site.get(), viewer, now);
            return;
        }
        if (!path.startsWith(API + "/")) return;
        String id = path.substring(API.length() + 1);
        if (!ID.matcher(id).matches()) return;
        Optional<MaintenanceViews.Visible> found = views.find(viewer, site.get(), id);
        if (found.isEmpty()) return; // nothing written → 404
        request.allowedMethods("GET");
        JsonObject json = windowJson(found.get(), viewer, now, true);
        json.addProperty("viewer", JsonViews.viewer(viewer));
        write(request, "200 OK", JsonViews.CONTENT_TYPE, JsonViews.GSON.toJson(json));
    }

    private void apiList(HTTPRequest request, Site site, Viewer viewer, Instant now) throws Exception {
        String fromText = request.get().get("from");
        String toText = request.get().get("to");
        Instant from;
        Instant to;
        JsonObject root = new JsonObject();
        if (fromText == null && toText == null) {
            from = now;
            to = now.plus(Duration.ofDays(API_DEFAULT_DAYS));
            root.addProperty("from", from.toString());
            root.addProperty("to", to.toString());
        } else {
            LocalDate fromDate = fromText == null ? LocalDate.ofInstant(now, zone()) : ServicePage.parseDate(fromText);
            LocalDate toDate = toText == null ? (fromDate == null ? null : fromDate.plusDays(API_DEFAULT_DAYS))
                    : ServicePage.parseDate(toText);
            if (fromDate == null || toDate == null) {
                JsonViews.error(request, "400 Bad Request", "from and to must be dates (yyyy-mm-dd)");
                return;
            }
            if (fromDate.isAfter(toDate)) {
                JsonViews.error(request, "400 Bad Request", "from must not be after to");
                return;
            }
            if (fromDate.plusDays(API_MAX_DAYS).isBefore(toDate.plusDays(1))) {
                JsonViews.error(request, "400 Bad Request", "at most " + API_MAX_DAYS + " days per request");
                return;
            }
            from = fromDate.atStartOfDay(zone()).toInstant();
            to = toDate.plusDays(1).atStartOfDay(zone()).toInstant();
            root.addProperty("from", fromDate.toString());
            root.addProperty("to", toDate.toString());
        }
        root.addProperty("viewer", JsonViews.viewer(viewer));
        JsonArray windows = new JsonArray();
        for (MaintenanceViews.Visible v : views.between(viewer, site, from, to)) {
            windows.add(windowJson(v, viewer, now, false));
        }
        root.add("windows", windows);
        write(request, "200 OK", JsonViews.CONTENT_TYPE, JsonViews.GSON.toJson(root));
    }

    // ---- pages ----

    private void handle(HTTPRequest request) throws Exception {
        String path = request.path();
        if (!path.startsWith(PREFIX + "/")) return;
        String[] parts = path.substring(PREFIX.length() + 1).split("/", -1);
        Optional<Site> site = sites.resolve(request);
        if (site.isEmpty()) return;
        if (parts.length == 1 && parts[0].equals("new")) {
            create(request, site.get());
            return;
        }
        if (!ID.matcher(parts[0]).matches()) return;
        Viewer viewer = permissions.viewer(request);
        Optional<MaintenanceViews.Visible> found = views.find(viewer, site.get(), parts[0]);
        if (found.isEmpty()) return; // nothing written → 404
        Page page = new Page(site.get(), viewer, found.get());
        if (parts.length == 1) {
            if (request.method().equals("POST")) {
                edit(request, page);
            } else {
                request.allowedMethods("GET");
                show(request, page, null, 200);
            }
        } else if (parts.length == 2) {
            switch (parts[1]) {
                case "cancel" -> cancel(request, page);
                case "end" -> endNow(request, page);
                case "extend" -> extend(request, page);
                case "notes" -> addNote(request, page);
                default -> {
                }
            }
        } else if (parts.length == 3 && parts[1].equals("note")) {
            editNote(request, page, parts[2]);
        } else if (parts.length == 4 && parts[1].equals("note") && parts[3].equals("delete")) {
            deleteNote(request, page, parts[2]);
        } else if (parts.length == 4 && parts[1].equals("occurrence")) {
            occurrence(request, page, parts[2], parts[3]);
        }
    }

    /** The window shown on a page, for whom and where. */
    private record Page(Site site, Viewer viewer, MaintenanceViews.Visible visible) {
        MaintenanceWindow window() {
            return visible.window();
        }

        String path() {
            return PREFIX + "/" + window().id();
        }
    }

    /** The form's values, as entered or taken from a window. */
    private static final class Form {
        final Map<String, String> fields = new LinkedHashMap<>();
        final Set<String> endpoints = new LinkedHashSet<>();
        final Set<String> categories = new LinkedHashSet<>();
        final Set<String> channels = new LinkedHashSet<>();
        final Set<DayOfWeek> weekdays = new TreeSet<>();

        String get(String key) {
            return fields.getOrDefault(key, "");
        }

        static Form fromPost(Map<String, String> post) {
            Form form = new Form();
            if (post == null) return form;
            for (Map.Entry<String, String> e : post.entrySet()) {
                String key = e.getKey();
                if (key.startsWith(ENDPOINT_PREFIX)) form.endpoints.add(key.substring(ENDPOINT_PREFIX.length()));
                else if (key.startsWith(CATEGORY_PREFIX)) form.categories.add(key.substring(CATEGORY_PREFIX.length()));
                else if (key.startsWith(CHANNEL_PREFIX)) form.channels.add(key.substring(CHANNEL_PREFIX.length()));
                else if (key.startsWith(WEEKDAY_PREFIX)) {
                    try {
                        form.weekdays.add(DayOfWeek.valueOf(key.substring(WEEKDAY_PREFIX.length())));
                    } catch (IllegalArgumentException ignored) {
                        // not a weekday; ignored like any unknown field
                    }
                } else form.fields.put(key, e.getValue() == null ? "" : e.getValue());
            }
            return form;
        }
    }

    private Form fromWindow(MaintenanceWindow w) {
        Form form = new Form();
        form.fields.put("title", w.title());
        form.fields.put("description", w.description() == null ? "" : w.description());
        form.fields.put("start", INPUT.format(w.start().atZone(zone())));
        form.fields.put("end", INPUT.format(w.end().atZone(zone())));
        form.fields.put("version", Integer.toString(w.version()));
        form.endpoints.addAll(w.endpoints());
        form.categories.addAll(w.categories());
        form.channels.addAll(w.notifyChannels());
        Recurrence r = w.recurrence();
        if (r == null) {
            form.fields.put("repeat", "none");
        } else {
            switch (r.frequency()) {
                case DAILY -> form.fields.put("repeat", "daily");
                case WEEKLY -> {
                    form.fields.put("repeat", "weekly");
                    form.weekdays.addAll(r.weekdays());
                }
                case MONTHLY -> {
                    if (r.dayOfMonth() != 0) {
                        form.fields.put("repeat", "monthly-day");
                        form.fields.put("dayOfMonth", Integer.toString(r.dayOfMonth()));
                    } else {
                        form.fields.put("repeat", "monthly-weekday");
                        form.fields.put("week", Integer.toString(r.week()));
                        form.fields.put("monthWeekday", r.weekday().name());
                    }
                }
            }
            if (r.until() != null) form.fields.put("until", r.until().toString());
        }
        return form;
    }

    /** The services and categories the viewer may pick on this site, in layout order. */
    private record Choices(Map<String, String> endpoints, Map<String, String> categories) {
    }

    private Choices choices(Site site, Viewer viewer) {
        ResolvedLayout scoped = site.scope(views.layout());
        Map<String, String> endpoints = new LinkedHashMap<>();
        Map<String, String> categories = new LinkedHashMap<>();
        for (ResolvedLayout.Category c : scoped.categories()) {
            boolean any = false;
            for (String id : c.endpointIds()) {
                Optional<Endpoint> e = views.endpoint(id);
                if (e.isEmpty() || !filter.canSee(viewer, e.get())) continue;
                endpoints.put(id, e.get().name());
                any = true;
            }
            if (!c.isOther() && (any || viewer.viewPrivate())) categories.put(c.id(), c.config().name());
        }
        return new Choices(endpoints, categories);
    }

    private void create(HTTPRequest request, Site site) throws Exception {
        request.allowedMethods("GET", "POST");
        if (!permissions.require(request, site, Permission.MAINTENANCE_MANAGE)) return;
        Viewer viewer = permissions.viewer(request);
        Choices choices = choices(site, viewer);
        if (request.method().equals("GET")) {
            Form form = new Form();
            form.fields.put("repeat", "none");
            String service = request.get().get("service");
            if (service != null && choices.endpoints().containsKey(service)) form.endpoints.add(service);
            createPage(request, site, viewer, choices, form, List.of(), 200);
            return;
        }
        if (!sessions.requireCsrf(request)) return;
        Form form = Form.fromPost(request.post());
        List<String> errors = new ArrayList<>();
        MaintenanceStore.Draft draft = draft(form, null, choices, viewer, errors, true);
        if (!errors.isEmpty()) {
            createPage(request, site, viewer, choices, form, errors, 400);
            return;
        }
        MaintenanceWindow created;
        try {
            created = views.store().create(draft, actor(request));
        } catch (MaintenanceStore.InvalidWindowException e) {
            createPage(request, site, viewer, choices, form, e.errors(), 400);
            return;
        } catch (IOException e) {
            saveFailed(request, "Creating a maintenance window failed", e);
            return;
        }
        redirect(request, site, PREFIX + "/" + created.id());
    }

    private void createPage(HTTPRequest request, Site site, Viewer viewer, Choices choices, Form form,
                            List<String> errors, int status) throws Exception {
        StringBuilder html = new StringBuilder(8192);
        PageSupport.head(html, site, "Schedule maintenance · " + site.title(), false, viewer);
        html.append("<p class=\"crumbs\"><a href=\"").append(Html.escape(site.link("/"))).append("\">All services</a></p>\n")
                .append("<h2>Schedule maintenance</h2>\n");
        formHtml(html, site, viewer, choices, form, errors, site.link(PREFIX + "/new"), null, "Schedule");
        PageSupport.foot(html, site);
        noFraming(request);
        write(request, status == 200 ? "200 OK" : "400 Bad Request", "text/html; charset=utf-8", html.toString());
    }

    private static void noFraming(HTTPRequest request) {
        request.response.setHeader("X-Frame-Options", "DENY");
        request.response.setHeader("Content-Security-Policy", "frame-ancestors 'none'");
    }

    /**
     * The window form. {@code old} is the window being edited ({@code null} when creating): its passed
     * times are read-only, a one-off can't become a series or back, and the services and categories the
     * viewer can't pick stay as they are.
     */
    private void formHtml(StringBuilder html, Site site, Viewer viewer, Choices choices, Form form,
                          List<String> errors, String action, MaintenanceWindow old, String submit) {
        Instant now = views.now();
        html.append("<form class=\"maintenance-form\" method=\"post\" action=\"").append(Html.escape(action)).append("\">\n");
        hidden(html, SessionManager.CSRF_FIELD, viewer.csrfToken() == null ? "" : viewer.csrfToken());
        if (old != null) hidden(html, "version", form.get("version"));
        if (!errors.isEmpty()) {
            html.append("<ul class=\"form-error-list\" role=\"alert\">");
            for (String e : errors) html.append("<li>").append(Html.escape(e)).append("</li>");
            html.append("</ul>\n");
        }
        html.append("<div class=\"field\"><label class=\"field-label\" for=\"m-title\">Title</label>")
                .append("<input type=\"text\" id=\"m-title\" name=\"title\" required maxlength=\"")
                .append(MaintenanceStore.MAX_TITLE_LENGTH).append("\" value=\"").append(Html.escape(form.get("title")))
                .append("\" placeholder=\"e.g. Weekly reboot\"></div>\n")
                .append("<div class=\"field\"><label class=\"field-label\" for=\"m-description\">Description (optional)</label>")
                .append("<textarea id=\"m-description\" name=\"description\" rows=\"3\" maxlength=\"")
                .append(MaintenanceStore.MAX_TEXT_LENGTH).append("\">").append(Html.escape(form.get("description")))
                .append("</textarea></div>\n");
        boolean startFixed = old != null && !old.start().isAfter(now);
        boolean endFixed = old != null && !old.end().isAfter(now);
        String zoneName = zone().getId();
        html.append("<div class=\"field\"><label class=\"field-label\" for=\"m-start\">Start (").append(Html.escape(zoneName))
                .append(")</label><input type=\"datetime-local\" id=\"m-start\" name=\"start\" required value=\"")
                .append(Html.escape(form.get("start"))).append('"').append(startFixed ? " readonly" : "").append("></div>\n")
                .append("<div class=\"field\"><label class=\"field-label\" for=\"m-end\">End (").append(Html.escape(zoneName))
                .append(")</label><input type=\"datetime-local\" id=\"m-end\" name=\"end\" required value=\"")
                .append(Html.escape(form.get("end"))).append('"').append(endFixed ? " readonly" : "").append("></div>\n");
        if (old != null && old.recurrence() == null && old.seriesId() == null) {
            // A one-off window stays one.
        } else if (old == null || old.isSeries()) {
            recurrenceHtml(html, form, old != null);
        }
        html.append("<fieldset class=\"field\"><legend class=\"field-label\">Affected services</legend>\n");
        if (choices.endpoints().isEmpty()) html.append("<p class=\"muted\">No services.</p>");
        for (Map.Entry<String, String> e : choices.endpoints().entrySet()) {
            check(html, "m-endpoint-" + e.getKey(), ENDPOINT_PREFIX + e.getKey(), e.getValue(),
                    form.endpoints.contains(e.getKey()));
        }
        html.append("</fieldset>\n");
        if (!choices.categories().isEmpty()) {
            html.append("<fieldset class=\"field\"><legend class=\"field-label\">Whole categories</legend>\n")
                    .append("<p class=\"muted field-help\">A category covers whatever is in it while the window runs.</p>");
            for (Map.Entry<String, String> e : choices.categories().entrySet()) {
                check(html, "m-category-" + e.getKey(), CATEGORY_PREFIX + e.getKey(), e.getValue(),
                        form.categories.contains(e.getKey()));
            }
            html.append("</fieldset>\n");
        }
        Set<String> configured = new TreeSet<>(channels.get());
        if (viewer.viewPrivate() && !configured.isEmpty()) {
            html.append("<fieldset class=\"field\"><legend class=\"field-label\">Announce to</legend>\n");
            for (String channel : configured) {
                check(html, "m-channel-" + channel, CHANNEL_PREFIX + channel, channel, form.channels.contains(channel));
            }
            html.append("</fieldset>\n");
        }
        html.append("<p><button type=\"submit\" class=\"button button-primary\">").append(Html.escape(submit))
                .append("</button></p>\n</form>\n");
    }

    private static void check(StringBuilder html, String id, String name, String label, boolean checked) {
        html.append("<label class=\"m-check\" for=\"").append(Html.escape(id)).append("\"><input type=\"checkbox\" id=\"")
                .append(Html.escape(id)).append("\" name=\"").append(Html.escape(name)).append("\" value=\"on\"")
                .append(checked ? " checked" : "").append("> ").append(Html.escape(label)).append("</label>\n");
    }

    private static void recurrenceHtml(StringBuilder html, Form form, boolean series) {
        String repeat = form.get("repeat");
        html.append("<fieldset class=\"field m-recurrence\"><legend class=\"field-label\">Repeat</legend>\n")
                .append("<select id=\"m-repeat\" name=\"repeat\" aria-label=\"Repeat\">");
        if (!series) option(html, "none", "Does not repeat", repeat);
        option(html, "daily", "Daily", repeat);
        option(html, "weekly", "Weekly, on the days below", repeat);
        option(html, "monthly-day", "Monthly, on a day of the month", repeat);
        option(html, "monthly-weekday", "Monthly, on a weekday (e.g. first Sunday)", repeat);
        html.append("</select>\n<p class=\"m-weekdays\">");
        for (DayOfWeek d : DayOfWeek.values()) {
            check(html, "m-weekday-" + d, WEEKDAY_PREFIX + d.name(), d.getDisplayName(TextStyle.SHORT, Locale.ENGLISH),
                    form.weekdays.contains(d));
        }
        html.append("</p>\n<p><label for=\"m-day\">Day of month</label> <input type=\"number\" id=\"m-day\" name=\"dayOfMonth\" min=\"1\" max=\"31\" value=\"")
                .append(Html.escape(form.get("dayOfMonth"))).append("\"></p>\n")
                .append("<p><label for=\"m-week\">Week</label> <select id=\"m-week\" name=\"week\">");
        String week = form.get("week");
        option(html, "1", "First", week);
        option(html, "2", "Second", week);
        option(html, "3", "Third", week);
        option(html, "4", "Fourth", week);
        option(html, "5", "Fifth", week);
        option(html, "-1", "Last", week);
        html.append("</select> <label for=\"m-month-weekday\">weekday</label> <select id=\"m-month-weekday\" name=\"monthWeekday\">");
        for (DayOfWeek d : DayOfWeek.values()) {
            option(html, d.name(), d.getDisplayName(TextStyle.FULL, Locale.ENGLISH), form.get("monthWeekday"));
        }
        html.append("</select></p>\n<p><label for=\"m-until\">Until (optional)</label> <input type=\"date\" id=\"m-until\" name=\"until\" value=\"")
                .append(Html.escape(form.get("until"))).append("\"></p>\n</fieldset>\n");
    }

    private static void option(StringBuilder html, String value, String label, String selected) {
        html.append("<option value=\"").append(Html.escape(value)).append('"').append(value.equals(selected) ? " selected" : "")
                .append('>').append(Html.escape(label)).append("</option>");
    }

    /**
     * The draft from the form. Ids the viewer can't pick are rejected, except those {@code old} already
     * has (kept as they are, as are its channels when the viewer can't see channels).
     */
    private MaintenanceStore.Draft draft(Form form, MaintenanceWindow old, Choices choices, Viewer viewer,
                                         List<String> errors, boolean allowRecurrence) {
        Instant start = instant(form.get("start"), old == null ? null : old.start(), "start", errors);
        Instant end = instant(form.get("end"), old == null ? null : old.end(), "end", errors);
        List<String> endpoints = merge(form.endpoints, choices.endpoints().keySet(),
                old == null ? List.of() : old.endpoints(), "endpoints", "service", errors);
        List<String> categories = merge(form.categories, choices.categories().keySet(),
                old == null ? List.of() : old.categories(), "categories", "category", errors);
        List<String> notify;
        if (viewer.viewPrivate()) {
            notify = new ArrayList<>(form.channels);
        } else {
            notify = old == null ? List.of() : old.notifyChannels();
        }
        Recurrence recurrence = allowRecurrence ? recurrence(form, errors) : null;
        String description = form.get("description");
        return new MaintenanceStore.Draft(form.get("title"), description.isBlank() ? null : description, start, end,
                endpoints, categories, notify, recurrence);
    }

    private static List<String> merge(Set<String> selected, Set<String> selectable, List<String> old, String key,
                                      String what, List<String> errors) {
        List<String> out = new ArrayList<>();
        for (String id : selected) {
            if (selectable.contains(id)) {
                out.add(id);
            } else if (!old.contains(id)) {
                errors.add(key + ": unknown " + what + " \"" + id + "\"");
            }
        }
        for (String id : old) {
            if (!selectable.contains(id) && !out.contains(id)) out.add(id);
        }
        return out;
    }

    /**
     * A {@code datetime-local} value in the configured timezone ({@code null} when empty). The old value is
     * kept when the field shows it unchanged (the form has minutes only).
     */
    private Instant instant(String value, Instant old, String key, List<String> errors) {
        if (value == null || value.isBlank()) return null;
        if (old != null && value.equals(INPUT.format(old.atZone(zone())))) return old;
        try {
            return ZonedDateTime.ofLocal(LocalDateTime.parse(value.strip()), zone(), null).toInstant();
        } catch (DateTimeParseException e) {
            errors.add(key + ": not a valid date and time");
            return null;
        }
    }

    private static Recurrence recurrence(Form form, List<String> errors) {
        String repeat = form.get("repeat");
        LocalDate until = null;
        if (!form.get("until").isBlank()) {
            until = ServicePage.parseDate(form.get("until").strip());
            if (until == null) {
                errors.add("recurrence: \"until\" isn't a valid date");
                return null;
            }
        }
        switch (repeat) {
            case "", "none":
                return null;
            case "daily":
                return Recurrence.daily(until);
            case "weekly":
                return Recurrence.weekly(List.copyOf(form.weekdays), until);
            case "monthly-day": {
                Integer day = number(form.get("dayOfMonth"));
                if (day == null) {
                    errors.add("recurrence: choose a day of the month");
                    return null;
                }
                return Recurrence.monthlyOnDay(day, until);
            }
            case "monthly-weekday": {
                Integer week = number(form.get("week"));
                DayOfWeek weekday;
                try {
                    weekday = DayOfWeek.valueOf(form.get("monthWeekday"));
                } catch (IllegalArgumentException e) {
                    weekday = null;
                }
                if (week == null || weekday == null) {
                    errors.add("recurrence: choose a week and a weekday");
                    return null;
                }
                return Recurrence.monthlyOnWeekday(week, weekday, until);
            }
            default:
                errors.add("recurrence: unknown repeat \"" + repeat + "\"");
                return null;
        }
    }

    private static Integer number(String value) {
        try {
            return Integer.parseInt(value.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private MaintenanceStore.Actor actor(HTTPRequest request) {
        User user = permissions.user(request).orElseThrow();
        return new MaintenanceStore.Actor(user.id(), user.displayName());
    }

    /** The window page; {@code editForm} replaces the edit form's values (after a failed save). */
    private void show(HTTPRequest request, Page page, EditState editForm, int status) throws Exception {
        Site site = page.site();
        Viewer viewer = page.viewer();
        MaintenanceWindow w = page.window();
        Instant now = views.now();
        MaintenanceWindow.Status st = w.status(now);
        boolean manage = canManage(request);
        boolean stored = views.store().window(w.id()).isPresent();
        String csrf = viewer.csrfToken();
        StringBuilder html = new StringBuilder(16384);
        PageSupport.head(html, site, w.title() + " · " + site.title(), false, viewer);
        html.append("<p class=\"crumbs\"><a href=\"").append(Html.escape(site.link("/"))).append("\">All services</a></p>\n")
                .append("<article class=\"maintenance-page maintenance-").append(statusId(st)).append("\" data-maintenance=\"")
                .append(Html.escape(w.id())).append("\">\n<h2 class=\"maintenance-page-title\">").append(Html.escape(w.title()))
                .append(" <span class=\"badge badge-maintenance\">").append(w.isSeries() ? (st == MaintenanceWindow.Status.CANCELLED ? "Cancelled" : "Recurring") : statusLabel(st))
                .append("</span></h2>\n<dl class=\"facts\">\n");
        if (w.isSeries()) {
            fact(html, "repeat", "Repeats", describe(w.recurrence()) + ", " + timeOfDay(w));
        } else {
            factHtml(html, "when", "When", range(w));
            fact(html, "duration", "Duration", Durations.format(Duration.between(w.start(), w.end())));
        }
        if (w.seriesId() != null) {
            factHtml(html, "series", "Part of", "<a href=\"" + Html.escape(site.link(PREFIX + "/" + w.seriesId()))
                    + "\">a recurring series</a>");
        }
        html.append("<div class=\"fact fact-affected\"><dt>Affects</dt><dd>");
        List<MaintenanceViews.Affected> affected = page.visible().affected();
        if (affected.isEmpty()) html.append("<span class=\"muted\">no current services</span>");
        for (int i = 0; i < affected.size(); i++) {
            if (i > 0) html.append(", ");
            html.append("<a href=\"").append(Html.escape(site.link("/service/" + affected.get(i).id()))).append("\">")
                    .append(Html.escape(affected.get(i).name())).append("</a>");
        }
        html.append("</dd></div>\n");
        if (viewer.viewPrivate()) {
            if (!w.categories().isEmpty()) fact(html, "categories", "Categories", String.join(", ", w.categories()));
            fact(html, "channels", "Announced to", w.notifyChannels().isEmpty() ? "none" : String.join(", ", w.notifyChannels()));
            if (w.createdByName() != null) fact(html, "created-by", "Scheduled by", w.createdByName());
        }
        html.append("</dl>\n");
        if (w.description() != null) {
            html.append("<p class=\"maintenance-description\">").append(Html.escape(w.description())).append("</p>\n");
        }
        if (manage) actionsHtml(html, site, w, st, csrf, stored);
        if (w.isSeries()) seriesHtml(html, site, viewer, w, manage);
        if (manage && editable(w, st)) {
            html.append("<details class=\"maintenance-edit\" id=\"edit\"").append(editForm != null ? " open" : "")
                    .append("><summary>Edit</summary>\n");
            if (stored) {
                Form form = editForm != null ? editForm.form : fromWindow(w);
                formHtml(html, site, viewer, choices(site, viewer), form, editForm != null ? editForm.errors : List.of(),
                        site.link(page.path()), w, "Save");
            } else {
                // An occurrence that isn't stored yet: changes to it are stored on its series.
                occurrenceFormHtml(html, site, viewer, w, editForm);
            }
            html.append("</details>\n");
        }
        html.append("<h3 class=\"notes-title\">Notes</h3>\n");
        notesHtml(html, site, viewer, w, stored && canAnnotate(request), page.path());
        html.append("</article>\n");
        PageSupport.foot(html, site);
        noFraming(request);
        String statusLine = switch (status) {
            case 400 -> "400 Bad Request";
            case 409 -> "409 Conflict";
            default -> "200 OK";
        };
        write(request, statusLine, "text/html; charset=utf-8", html.toString());
    }

    private record EditState(Form form, List<String> errors) {
    }

    private static boolean editable(MaintenanceWindow w, MaintenanceWindow.Status st) {
        return st != MaintenanceWindow.Status.CANCELLED;
    }

    private String timeOfDay(MaintenanceWindow series) {
        ZonedDateTime s = series.start().atZone(zone());
        ZonedDateTime e = series.end().atZone(zone());
        DateTimeFormatter hm = DateTimeFormatter.ofPattern("HH:mm");
        return hm.format(s) + " – " + hm.format(e) + " (first " + s.toLocalDate() + ")";
    }

    static String describe(Recurrence r) {
        String base = switch (r.frequency()) {
            case DAILY -> "Daily";
            case WEEKLY -> "Weekly on " + String.join(", ", r.weekdays().stream()
                    .map(d -> d.getDisplayName(TextStyle.FULL, Locale.ENGLISH)).toList());
            case MONTHLY -> r.dayOfMonth() != 0 ? "Monthly on day " + r.dayOfMonth()
                    : "Monthly on the " + switch (r.week()) {
                        case 1 -> "first";
                        case 2 -> "second";
                        case 3 -> "third";
                        case 4 -> "fourth";
                        case 5 -> "fifth";
                        default -> "last";
                    } + " " + r.weekday().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
        };
        return r.until() == null ? base : base + " until " + r.until();
    }

    private void actionsHtml(StringBuilder html, Site site, MaintenanceWindow w, MaintenanceWindow.Status st,
                             String csrf, boolean stored) {
        String base = PREFIX + "/" + w.id();
        Map<String, String> version = Map.of("version", Integer.toString(w.version()));
        StringBuilder actions = new StringBuilder();
        if (st == MaintenanceWindow.Status.SCHEDULED && stored) {
            postButton(actions, site.link(base + "/cancel"), csrf, base, version,
                    w.isSeries() ? "Cancel series" : "Cancel", "button-danger",
                    w.isSeries() ? "Cancel all future occurrences?" : "Cancel this maintenance?");
        } else if (st == MaintenanceWindow.Status.SCHEDULED && w.seriesId() != null) {
            postButton(actions, site.link(PREFIX + "/" + w.seriesId() + "/occurrence/" + w.occurrence() + "/skip"),
                    csrf, PREFIX + "/" + w.seriesId(), version, "Skip this occurrence", "button-danger",
                    "Skip this occurrence?");
        } else if (st == MaintenanceWindow.Status.IN_PROGRESS) {
            postButton(actions, site.link(base + "/end"), csrf, base, version, "End now", "button-danger",
                    "End this maintenance now?");
            actions.append("<form class=\"inline-form m-extend\" method=\"post\" action=\"")
                    .append(Html.escape(site.link(base + "/extend"))).append("\">");
            hidden(actions, SessionManager.CSRF_FIELD, csrf == null ? "" : csrf);
            hidden(actions, "version", Integer.toString(w.version()));
            actions.append("<label for=\"m-extend-end\">New end</label> <input type=\"datetime-local\" id=\"m-extend-end\" name=\"end\" required value=\"")
                    .append(Html.escape(INPUT.format(w.end().atZone(zone())))).append("\"> ")
                    .append("<button type=\"submit\" class=\"button\">Extend</button></form>");
        }
        if (!actions.isEmpty()) html.append("<div class=\"page-actions maintenance-actions\">").append(actions).append("</div>\n");
    }

    /** A series: its upcoming occurrences (each linking to its page) and its skipped dates. */
    private void seriesHtml(StringBuilder html, Site site, Viewer viewer, MaintenanceWindow series, boolean manage) {
        Instant now = views.now();
        html.append("<section class=\"series\">\n<h3>Upcoming occurrences</h3>\n");
        if (series.cancelledAt() != null) {
            html.append("<p class=\"muted\">The series was cancelled.</p>\n</section>\n");
            return;
        }
        List<MaintenanceWindow> next = new ArrayList<>();
        for (MaintenanceWindow w : views.store().occurrences(now, now.plus(Duration.ofDays(API_MAX_DAYS)))) {
            if (series.id().equals(w.seriesId()) && w.status(now) != MaintenanceWindow.Status.COMPLETED) next.add(w);
            if (next.size() >= SERIES_OCCURRENCES) break;
        }
        if (next.isEmpty()) {
            html.append("<p class=\"muted\">None planned.</p>\n");
        } else {
            html.append("<ul class=\"maintenance-list\">\n");
            for (MaintenanceWindow w : next) {
                html.append("<li class=\"maintenance-item\" data-maintenance=\"").append(Html.escape(w.id()))
                        .append("\"><a href=\"").append(Html.escape(site.link(PREFIX + "/" + w.id()))).append("\">")
                        .append(Html.escape(w.title())).append("</a> <span class=\"muted\">").append(range(w))
                        .append("</span>");
                if (manage && w.status(now) == MaintenanceWindow.Status.SCHEDULED && views.store().window(w.id()).isEmpty()) {
                    html.append(' ');
                    postButton(html, site.link(PREFIX + "/" + series.id() + "/occurrence/" + w.occurrence() + "/skip"),
                            viewer.csrfToken(), PREFIX + "/" + series.id(), Map.of("version", Integer.toString(series.version())),
                            "Skip", "button-danger", "Skip this occurrence?");
                }
                html.append("</li>\n");
            }
            html.append("</ul>\n");
        }
        List<OccurrenceChange> skipped = series.exceptions().stream().filter(OccurrenceChange::skipped).toList();
        if (!skipped.isEmpty()) {
            html.append("<h4>Skipped</h4>\n<ul class=\"maintenance-list\">\n");
            for (OccurrenceChange c : skipped) {
                html.append("<li>").append(c.date());
                if (manage) {
                    html.append(' ');
                    postButton(html, site.link(PREFIX + "/" + series.id() + "/occurrence/" + c.date() + "/restore"),
                            viewer.csrfToken(), PREFIX + "/" + series.id(), Map.of("version", Integer.toString(series.version())),
                            "Restore", "", null);
                }
                html.append("</li>\n");
            }
            html.append("</ul>\n");
        }
        html.append("</section>\n");
    }

    /** The edit form of an occurrence that isn't stored yet: its own title, description and times. */
    private void occurrenceFormHtml(StringBuilder html, Site site, Viewer viewer, MaintenanceWindow w, EditState state) {
        Form form = state != null ? state.form : fromWindow(w);
        html.append("<form class=\"maintenance-form\" method=\"post\" action=\"")
                .append(Html.escape(site.link(PREFIX + "/" + w.seriesId() + "/occurrence/" + w.occurrence() + "/edit")))
                .append("\">\n");
        hidden(html, SessionManager.CSRF_FIELD, viewer.csrfToken() == null ? "" : viewer.csrfToken());
        hidden(html, "version", Integer.toString(w.version()));
        if (state != null && !state.errors.isEmpty()) {
            html.append("<ul class=\"form-error-list\" role=\"alert\">");
            for (String e : state.errors) html.append("<li>").append(Html.escape(e)).append("</li>");
            html.append("</ul>\n");
        }
        html.append("<p class=\"muted field-help\">Changes only this occurrence; services and channels follow the series.</p>\n")
                .append("<div class=\"field\"><label class=\"field-label\" for=\"m-title\">Title</label>")
                .append("<input type=\"text\" id=\"m-title\" name=\"title\" required maxlength=\"")
                .append(MaintenanceStore.MAX_TITLE_LENGTH).append("\" value=\"").append(Html.escape(form.get("title")))
                .append("\"></div>\n<div class=\"field\"><label class=\"field-label\" for=\"m-description\">Description (optional)</label>")
                .append("<textarea id=\"m-description\" name=\"description\" rows=\"3\">").append(Html.escape(form.get("description")))
                .append("</textarea></div>\n<div class=\"field\"><label class=\"field-label\" for=\"m-start\">Start</label>")
                .append("<input type=\"datetime-local\" id=\"m-start\" name=\"start\" required value=\"").append(Html.escape(form.get("start")))
                .append("\"></div>\n<div class=\"field\"><label class=\"field-label\" for=\"m-end\">End</label>")
                .append("<input type=\"datetime-local\" id=\"m-end\" name=\"end\" required value=\"").append(Html.escape(form.get("end")))
                .append("\"></div>\n<p><button type=\"submit\" class=\"button button-primary\">Save occurrence</button></p>\n</form>\n");
    }

    private void notesHtml(StringBuilder html, Site site, Viewer viewer, MaintenanceWindow w, boolean annotate,
                           String path) {
        List<NoteView> notes = notes(viewer, w);
        String csrf = viewer.csrfToken();
        if (notes.isEmpty()) {
            html.append("<p class=\"muted notes-empty\">No notes.</p>\n");
        } else {
            html.append("<ul class=\"notes\">\n");
            for (NoteView note : notes) {
                html.append("<li class=\"note\" id=\"note-").append(Html.escape(note.id())).append("\">")
                        .append("<p class=\"note-meta\"><span class=\"note-author\">").append(Html.escape(note.author()))
                        .append("</span> · ").append(timeTag(note.createdAt()));
                if (note.editedAt() != null) {
                    html.append(" <span class=\"muted\">(edited ").append(timeTag(note.editedAt())).append(")</span>");
                }
                if (note.visibility() != null) {
                    String vis = note.visibility() == EventNote.Visibility.PRIVATE ? "private" : "public";
                    html.append(" <span class=\"badge badge-").append(vis).append("\">").append(vis).append("</span>");
                }
                html.append("</p>\n<p class=\"note-text\">").append(Html.escape(note.text())).append("</p>\n");
                if (annotate) {
                    String action = site.link(path + "/note/" + note.id());
                    boolean isPrivate = note.visibility() == EventNote.Visibility.PRIVATE;
                    html.append("<div class=\"note-actions\"><details class=\"note-edit\"><summary>Edit</summary>")
                            .append("<form class=\"note-form\" method=\"post\" action=\"").append(Html.escape(action)).append("\">");
                    hidden(html, SessionManager.CSRF_FIELD, csrf == null ? "" : csrf);
                    html.append("<label for=\"note-text-").append(Html.escape(note.id())).append("\">Note</label>")
                            .append("<textarea id=\"note-text-").append(Html.escape(note.id())).append("\" name=\"text\" rows=\"4\" maxlength=\"")
                            .append(MaintenanceStore.MAX_TEXT_LENGTH).append("\" required>").append(Html.escape(note.text())).append("</textarea>");
                    visibilitySelect(html, "note-vis-" + note.id(), isPrivate);
                    html.append("<button type=\"submit\">Save</button></form></details>");
                    postButton(html, action + "/delete", csrf, null, Map.of(), "Delete", "", "Delete this note?");
                    html.append("</div>\n");
                }
                html.append("</li>\n");
            }
            html.append("</ul>\n");
        }
        if (annotate) {
            html.append("<details class=\"note-add\"><summary>Add note</summary><form class=\"note-form\" method=\"post\" action=\"")
                    .append(Html.escape(site.link(path + "/notes"))).append("\">");
            hidden(html, SessionManager.CSRF_FIELD, csrf == null ? "" : csrf);
            html.append("<label for=\"new-note\">Note</label><textarea id=\"new-note\" name=\"text\" rows=\"4\" maxlength=\"")
                    .append(MaintenanceStore.MAX_TEXT_LENGTH).append("\" required></textarea>");
            visibilitySelect(html, "new-note-vis", true);
            html.append("<button type=\"submit\">Add note</button></form></details>\n");
        }
    }

    private static void visibilitySelect(StringBuilder html, String id, boolean isPrivate) {
        html.append("<label for=\"").append(Html.escape(id)).append("\">Visible to</label>")
                .append("<select id=\"").append(Html.escape(id)).append("\" name=\"visibility\">")
                .append("<option value=\"private\"").append(isPrivate ? " selected" : "").append(">Signed-in users (private)</option>")
                .append("<option value=\"public\"").append(isPrivate ? "" : " selected").append(">Everyone (public)</option>")
                .append("</select>");
    }

    private static void fact(StringBuilder html, String key, String label, String value) {
        factHtml(html, key, label, Html.escape(value));
    }

    private static void factHtml(StringBuilder html, String key, String label, String valueHtml) {
        html.append("<div class=\"fact fact-").append(key).append("\"><dt>").append(Html.escape(label))
                .append("</dt><dd>").append(valueHtml).append("</dd></div>\n");
    }

    // ---- actions ----

    /** Permission, then CSRF; writes the answer and returns {@code false} when either fails. */
    private boolean checkManage(HTTPRequest request, Page page) throws Exception {
        request.allowedMethods("POST");
        return permissions.require(request, page.site(), Permission.MAINTENANCE_MANAGE) && sessions.requireCsrf(request);
    }

    private static int version(Map<String, String> post) {
        Integer v = post == null || post.get("version") == null ? null : number(post.get("version"));
        return v == null ? -1 : v;
    }

    private void edit(HTTPRequest request, Page page) throws Exception {
        if (!checkManage(request, page)) return;
        MaintenanceWindow w = views.store().window(page.window().id()).orElse(null);
        if (w == null) return; // an occurrence that isn't stored: edited through its series → 404 here
        Map<String, String> post = request.post();
        Form form = Form.fromPost(post);
        List<String> errors = new ArrayList<>();
        MaintenanceStore.Draft draft = draft(form, w, choices(page.site(), page.viewer()), page.viewer(), errors,
                w.isSeries());
        if (!errors.isEmpty()) {
            show(request, page, new EditState(form, errors), 400);
            return;
        }
        try {
            if (views.store().update(w.id(), version(post), draft, actor(request)).isEmpty()) return;
        } catch (MaintenanceStore.InvalidWindowException e) {
            show(request, page, new EditState(form, e.errors()), 400);
            return;
        } catch (MaintenanceStore.VersionConflictException e) {
            conflict(request, page);
            return;
        } catch (IOException e) {
            saveFailed(request, "Editing maintenance window " + w.id() + " failed", e);
            return;
        }
        redirect(request, page.site(), page.path());
    }

    private void conflict(HTTPRequest request, Page page) throws Exception {
        String body = "<!DOCTYPE html>\n<html lang=\"en\"><head><meta charset=\"utf-8\"><title>Changed meanwhile</title></head>"
                + "<body><p>Someone else changed this maintenance window in the meantime. <a href=\""
                + Html.escape(page.site().link(page.path())) + "\">Reload it</a> and try again.</p></body></html>\n";
        write(request, "409 Conflict", "text/html; charset=utf-8", body);
    }

    private void cancel(HTTPRequest request, Page page) throws Exception {
        if (!checkManage(request, page)) return;
        Map<String, String> post = request.post();
        try {
            if (views.store().cancel(page.window().id(), version(post), actor(request)).isEmpty()) return;
        } catch (MaintenanceStore.InvalidWindowException e) {
            badRequest(request, String.join("; ", e.errors()));
            return;
        } catch (MaintenanceStore.VersionConflictException e) {
            conflict(request, page);
            return;
        } catch (IOException e) {
            saveFailed(request, "Cancelling maintenance window " + page.window().id() + " failed", e);
            return;
        }
        redirectNext(request, page.site(), post, page.path());
    }

    private void endNow(HTTPRequest request, Page page) throws Exception {
        if (!checkManage(request, page)) return;
        Map<String, String> post = request.post();
        String id = page.window().id();
        try {
            Optional<MaintenanceWindow> ended = scheduler != null
                    ? scheduler.endNow(id, version(post), actor(request))
                    : views.store().endNow(id, version(post), actor(request));
            if (ended.isEmpty()) return;
        } catch (MaintenanceStore.InvalidWindowException e) {
            badRequest(request, String.join("; ", e.errors()));
            return;
        } catch (MaintenanceStore.VersionConflictException e) {
            conflict(request, page);
            return;
        } catch (IOException e) {
            saveFailed(request, "Ending maintenance window " + id + " failed", e);
            return;
        }
        redirectNext(request, page.site(), post, page.path());
    }

    private void extend(HTTPRequest request, Page page) throws Exception {
        if (!checkManage(request, page)) return;
        Map<String, String> post = request.post();
        String id = page.window().id();
        List<String> errors = new ArrayList<>();
        Instant end = instant(post == null ? null : post.get("end"), null, "end", errors);
        if (!errors.isEmpty()) {
            badRequest(request, String.join("; ", errors));
            return;
        }
        try {
            Optional<MaintenanceWindow> extended = scheduler != null
                    ? scheduler.extend(id, version(post), end, actor(request))
                    : views.store().extend(id, version(post), end, actor(request));
            if (extended.isEmpty()) return;
        } catch (MaintenanceStore.InvalidWindowException e) {
            badRequest(request, String.join("; ", e.errors()));
            return;
        } catch (MaintenanceStore.VersionConflictException e) {
            conflict(request, page);
            return;
        } catch (IOException e) {
            saveFailed(request, "Extending maintenance window " + id + " failed", e);
            return;
        }
        redirectNext(request, page.site(), post, page.path());
    }

    /** {@code skip}, {@code restore} or {@code edit} of one occurrence of the series shown on {@code page}. */
    private void occurrence(HTTPRequest request, Page page, String dateText, String action) throws Exception {
        MaintenanceWindow series = page.window();
        LocalDate date = ServicePage.parseDate(dateText);
        if (!series.isSeries() || date == null || !Set.of("skip", "restore", "edit").contains(action)) return;
        if (!checkManage(request, page)) return;
        Map<String, String> post = request.post();
        MaintenanceStore store = views.store();
        MaintenanceStore.Actor actor = actor(request);
        String occurrenceId = MaintenanceStore.occurrenceId(series.id(), date);
        try {
            Optional<MaintenanceWindow> result = switch (action) {
                case "skip" -> store.skipOccurrence(series.id(), version(post), date, actor);
                case "restore" -> store.restoreOccurrence(series.id(), version(post), date, actor);
                default -> {
                    Form form = Form.fromPost(post);
                    List<String> errors = new ArrayList<>();
                    MaintenanceWindow current = store.occurrence(occurrenceId).orElse(null);
                    Instant start = instant(form.get("start"), current == null ? null : current.start(), "start", errors);
                    Instant end = instant(form.get("end"), current == null ? null : current.end(), "end", errors);
                    if (!errors.isEmpty()) throw new FormErrors(errors);
                    String description = form.get("description");
                    yield store.editOccurrence(series.id(), version(post), date, new MaintenanceStore.OccurrenceDraft(
                            form.get("title"), description.isBlank() ? null : description, start, end), actor);
                }
            };
            if (result.isEmpty()) return;
        } catch (FormErrors e) {
            badRequest(request, String.join("; ", e.errors));
            return;
        } catch (MaintenanceStore.InvalidWindowException e) {
            badRequest(request, String.join("; ", e.errors()));
            return;
        } catch (MaintenanceStore.VersionConflictException e) {
            conflict(request, page);
            return;
        } catch (IOException e) {
            saveFailed(request, "Changing occurrence " + date + " of series " + series.id() + " failed", e);
            return;
        }
        String target = action.equals("edit") ? PREFIX + "/" + occurrenceId : page.path();
        redirectNext(request, page.site(), post, target);
    }

    private static final class FormErrors extends Exception {
        final List<String> errors;

        FormErrors(List<String> errors) {
            super(String.join("; ", errors));
            this.errors = errors;
        }
    }

    // ---- notes ----

    private boolean checkAnnotate(HTTPRequest request, Page page) throws Exception {
        request.allowedMethods("POST");
        return permissions.require(request, page.site(), Permission.EVENTS_ANNOTATE) && sessions.requireCsrf(request);
    }

    private static EventNote.Visibility visibility(Map<String, String> post, EventNote.Visibility fallback) {
        String value = post == null ? null : post.get("visibility");
        if (value == null) return fallback;
        return switch (value) {
            case "public" -> EventNote.Visibility.PUBLIC;
            case "private" -> EventNote.Visibility.PRIVATE;
            default -> null;
        };
    }

    private void addNote(HTTPRequest request, Page page) throws Exception {
        if (views.store().window(page.window().id()).isEmpty()) return; // not stored yet → 404
        if (!checkAnnotate(request, page)) return;
        Map<String, String> post = request.post();
        EventNote.Visibility visibility = visibility(post, EventNote.Visibility.PRIVATE);
        if (visibility == null) {
            badRequest(request, "The visibility must be \"public\" or \"private\".");
            return;
        }
        Optional<EventNote> added;
        try {
            added = views.store().addNote(page.window().id(), post == null ? null : post.get("text"), visibility,
                    actor(request));
        } catch (MaintenanceStore.InvalidWindowException e) {
            badRequest(request, String.join("; ", e.errors()));
            return;
        } catch (IOException e) {
            saveFailed(request, "Adding a note to maintenance window " + page.window().id() + " failed", e);
            return;
        }
        if (added.isEmpty()) return;
        redirect(request, page.site(), page.path() + "#note-" + added.get().id());
    }

    /** The note, if the viewer can see it (a private note for a viewer without {@code view.private} is {@code 404}). */
    private Optional<EventNote> visibleNote(Page page, String noteId) {
        Optional<MaintenanceWindow> stored = views.store().window(page.window().id());
        return stored.flatMap(w -> w.note(noteId))
                .filter(n -> page.viewer().viewPrivate() || n.visibility() == EventNote.Visibility.PUBLIC);
    }

    private void editNote(HTTPRequest request, Page page, String noteId) throws Exception {
        Optional<EventNote> before = visibleNote(page, noteId);
        if (before.isEmpty()) return;
        if (!checkAnnotate(request, page)) return;
        Map<String, String> post = request.post();
        String text = post != null && post.containsKey("text") ? post.get("text") : before.get().text();
        EventNote.Visibility visibility = visibility(post, before.get().visibility());
        if (visibility == null) {
            badRequest(request, "The visibility must be \"public\" or \"private\".");
            return;
        }
        try {
            if (views.store().editNote(page.window().id(), noteId, text, visibility, actor(request)).isEmpty()) return;
        } catch (MaintenanceStore.InvalidWindowException e) {
            badRequest(request, String.join("; ", e.errors()));
            return;
        } catch (IOException e) {
            saveFailed(request, "Editing note " + noteId + " of maintenance window " + page.window().id() + " failed", e);
            return;
        }
        redirect(request, page.site(), page.path() + "#note-" + noteId);
    }

    private void deleteNote(HTTPRequest request, Page page, String noteId) throws Exception {
        if (visibleNote(page, noteId).isEmpty()) return;
        if (!checkAnnotate(request, page)) return;
        try {
            if (!views.store().deleteNote(page.window().id(), noteId, actor(request))) return;
        } catch (IOException e) {
            saveFailed(request, "Deleting note " + noteId + " of maintenance window " + page.window().id() + " failed", e);
            return;
        }
        redirect(request, page.site(), page.path());
    }

    // ---- responses ----

    private static void badRequest(HTTPRequest request, String message) throws Exception {
        write(request, "400 Bad Request", "text/plain; charset=utf-8", message + "\n");
    }

    private static void saveFailed(HTTPRequest request, String what, IOException e) throws Exception {
        System.getLogger(MaintenancePage.class.getName()).log(System.Logger.Level.ERROR, what, e);
        write(request, "500 Internal Server Error", "text/plain; charset=utf-8", "Saving failed. Try again later.\n");
    }

    private static void redirectNext(HTTPRequest request, Site site, Map<String, String> post, String fallback)
            throws Exception {
        String next = post == null ? null : post.get("next");
        redirect(request, site, next == null || next.isEmpty() ? fallback : LoginPage.safeNext(next));
    }

    private static void redirect(HTTPRequest request, Site site, String path) throws Exception {
        request.response.setHeader("303 See Other");
        request.response.setHeader("Location", site.link(path));
        request.response.setHeader("Cache-Control", "no-store");
        request.response.setHeader("Content-Length", "0");
        request.response.write(new byte[0]);
    }
}
