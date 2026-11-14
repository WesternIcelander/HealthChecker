package io.siggi.healthchecker.web.page;

import io.siggi.healthchecker.config.DashboardConfig;
import io.siggi.healthchecker.history.DaySummary;
import io.siggi.healthchecker.history.EventNote;
import io.siggi.healthchecker.history.HistoryEvent;
import io.siggi.healthchecker.history.HistoryStore;
import io.siggi.healthchecker.history.Uptime;
import io.siggi.healthchecker.history.UptimeCalculator;
import io.siggi.healthchecker.plugin.JsonEndpointConfig.Target;
import io.siggi.healthchecker.schedule.Endpoint;
import io.siggi.healthchecker.status.EndpointStatus;
import io.siggi.healthchecker.web.Durations;
import io.siggi.healthchecker.web.Html;
import io.siggi.healthchecker.web.WebServer;
import io.siggi.healthchecker.web.layout.ResolvedLayout;
import io.siggi.healthchecker.web.site.Site;
import io.siggi.healthchecker.web.site.SiteResolver;
import io.siggi.healthchecker.web.view.CheckView;
import io.siggi.healthchecker.web.view.EndpointView;
import io.siggi.healthchecker.web.view.EventView;
import io.siggi.healthchecker.web.view.NoteView;
import io.siggi.healthchecker.web.view.ViewFilter;
import io.siggi.healthchecker.web.view.Viewer;
import io.siggi.http.HTTPRequest;

import java.time.Clock;
import java.time.Instant;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import static io.siggi.healthchecker.web.page.PageSupport.stateId;
import static io.siggi.healthchecker.web.page.PageSupport.stateLabel;
import static io.siggi.healthchecker.web.page.PageSupport.write;

/**
 * The service page {@code /service/{id}}: a header with name, state, 24 h / 60 d uptime, the
 * 60-day bar and the recent checks as a pass/fail strip, plus whatever else the viewer may see
 * of the last check (and, for privileged viewers, type, visibility and targets). Below it, the
 * list view ({@code ?view=list&page=N}, the default): downtime events newest first,
 * {@value #PAGE_SIZE} per page, each expandable to show its visible notes. Each event also has a
 * permalink page, {@code /service/{id}/event/{eventId}}. The calendar view
 * ({@code ?view=calendar&page=N}) shows {@value #CALENDAR_MONTHS} months per page as Monday-first
 * grids of colour-only day blocks; page 0 ends with the current month.
 *
 * <p>Answers {@code 404} (by writing nothing) for unknown ids, for endpoints outside the
 * request's site scope (even for privileged viewers), for endpoints the viewer may not see
 * ({@code private} without {@code view.private}), and for rejected hosts, so a private
 * endpoint's existence isn't revealed. The event page also answers {@code 404} for an unknown
 * event id or one of another endpoint.
 */
public final class ServicePage {
    private static final String PREFIX = "/service/";
    private static final Pattern ID = Pattern.compile("[a-z0-9-]+");
    private static final Pattern EVENT_ID = Pattern.compile("[A-Za-z0-9-]{1,64}");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /** Events per page of the list view. */
    static final int PAGE_SIZE = 25;
    /** Months per page of the calendar view. */
    static final int CALENDAR_MONTHS = 3;
    /** The last calendar page that can be shown (1000 years back); higher page numbers show this one. */
    static final int MAX_CALENDAR_PAGE = 4000;

    private final SiteResolver sites;
    private final Supplier<ResolvedLayout> layout;
    private final Function<String, Optional<Endpoint>> endpoints;
    private final Function<String, Optional<EndpointStatus>> statuses;
    private final Function<HTTPRequest, Viewer> viewers;
    private final UptimeCalculator uptime;
    private final HistoryStore history;
    private final Clock clock;
    private final DashboardConfig dashboard;
    private final ViewFilter filter = new ViewFilter();

    /**
     * Same sources as {@link StatusPage}, plus the history for the event list and the clock
     * that ongoing events' durations run to.
     */
    public ServicePage(SiteResolver sites, Supplier<ResolvedLayout> layout,
                       Function<String, Optional<Endpoint>> endpoints,
                       Function<String, Optional<EndpointStatus>> statuses,
                       Function<HTTPRequest, Viewer> viewers,
                       UptimeCalculator uptime, HistoryStore history, Clock clock, DashboardConfig dashboard) {
        this.sites = sites;
        this.layout = layout;
        this.endpoints = endpoints;
        this.statuses = statuses;
        this.viewers = viewers;
        this.uptime = uptime;
        this.history = history;
        this.clock = clock;
        this.dashboard = dashboard;
    }

    public void register(WebServer server) {
        server.register(PREFIX, this::page);
    }

    /** A service the request may see: its site, the viewer, the endpoint and its filtered view. */
    record Service(Site site, Viewer viewer, Endpoint endpoint, EndpointView view) {
    }

    /** What the header shows. */
    record Header(Site site, Viewer viewer, EndpointView view, Uptime day, Uptime sixtyDays, List<DaySummary> days) {
    }

    /**
     * One page of the event list, newest first.
     *
     * @param page  the page shown (0 = newest), clamped to the existing pages
     * @param pages number of pages (at least 1)
     * @param total number of events
     */
    record EventPage(List<EventView> events, int page, int pages, int total) {
        boolean hasNewer() {
            return page > 0;
        }

        boolean hasOlder() {
            return page < pages - 1;
        }
    }

    /** One day block of the calendar; {@code summary} and {@code color} are {@code null} for future days. */
    record CalendarDay(LocalDate date, DaySummary summary, DayColor color) {
        boolean future() {
            return summary == null;
        }
    }

    /**
     * One month of the calendar.
     *
     * @param leadingBlanks empty cells before the 1st, so that weeks start on Monday (0–6)
     */
    record CalendarMonth(YearMonth month, int leadingBlanks, List<CalendarDay> days) {
    }

    /** One page of the calendar view: its months, oldest first. */
    record CalendarPage(int page, List<CalendarMonth> months) {
        boolean hasLater() {
            return page > 0;
        }

        boolean hasEarlier() {
            return page < MAX_CALENDAR_PAGE;
        }
    }

    /**
     * The months on calendar page {@code page}, oldest first: the {@value #CALENDAR_MONTHS} months
     * ending {@value #CALENDAR_MONTHS} × {@code page} months before {@code current}.
     */
    static List<YearMonth> calendarMonths(YearMonth current, int page) {
        YearMonth last = current.minusMonths((long) CALENDAR_MONTHS * page);
        List<YearMonth> months = new ArrayList<>(CALENDAR_MONTHS);
        for (int i = CALENDAR_MONTHS - 1; i >= 0; i--) {
            months.add(last.minusMonths(i));
        }
        return months;
    }

    /**
     * Calendar page {@code page} (0 = the current month and the two before it, clamped to
     * {@link #MAX_CALENDAR_PAGE}). Days up to today are coloured from their summaries; days
     * before the endpoint's first coverage have no data (grey) and aren't computed at all;
     * days after today are future days.
     */
    CalendarPage calendar(Service service, int page) {
        String id = service.endpoint().id();
        int shown = Math.max(0, Math.min(page, MAX_CALENDAR_PAGE));
        LocalDate today = uptime.today();
        LocalDate firstCovered = history.coverage(id).stream()
                .map(p -> LocalDate.ofInstant(p.start(), uptime.zone()))
                .min(Comparator.naturalOrder()).orElse(null);
        List<CalendarMonth> months = new ArrayList<>(CALENDAR_MONTHS);
        for (YearMonth month : calendarMonths(YearMonth.from(today), shown)) {
            LocalDate first = month.atDay(1);
            LocalDate last = month.atEndOfMonth();
            Map<LocalDate, DaySummary> computed = new HashMap<>();
            if (firstCovered != null) {
                LocalDate from = first.isBefore(firstCovered) ? firstCovered : first;
                LocalDate to = last.isAfter(today) ? today : last;
                if (!to.isBefore(from)) {
                    for (DaySummary day : uptime.days(id, from, to)) computed.put(day.date(), day);
                }
            }
            List<CalendarDay> days = new ArrayList<>(last.getDayOfMonth());
            for (LocalDate date = first; !date.isAfter(last); date = date.plusDays(1)) {
                if (date.isAfter(today)) {
                    days.add(new CalendarDay(date, null, null));
                } else {
                    DaySummary summary = computed.getOrDefault(date, new DaySummary(date, Uptime.NONE, 0, 0));
                    days.add(new CalendarDay(date, summary, DayColor.of(summary, dashboard)));
                }
            }
            int blanks = first.getDayOfWeek().getValue() - DayOfWeek.MONDAY.getValue();
            months.add(new CalendarMonth(month, blanks, days));
        }
        return new CalendarPage(shown, months);
    }

    /**
     * The service, or empty when the request must get a {@code 404}: rejected host, unknown id,
     * out of the site's scope, or not visible to the viewer.
     */
    Optional<Service> service(HTTPRequest request, String id) {
        Optional<Site> site = sites.resolve(request);
        if (site.isEmpty() || !ID.matcher(id).matches()) {
            return Optional.empty();
        }
        if (!site.get().includes(id, layout.get())) {
            return Optional.empty();
        }
        Optional<Endpoint> endpoint = endpoints.apply(id);
        if (endpoint.isEmpty()) {
            return Optional.empty();
        }
        Viewer viewer = viewers.apply(request);
        Optional<EndpointView> view = filter.endpoint(viewer, endpoint.get(), statuses.apply(id).orElse(null));
        return view.map(v -> new Service(site.get(), viewer, endpoint.get(), v));
    }

    /** The header for the service, or empty when the request must get a {@code 404} (see {@link #service}). */
    Optional<Header> header(HTTPRequest request, String id) {
        return service(request, id).map(this::header);
    }

    private Header header(Service service) {
        String id = service.endpoint().id();
        LocalDate today = uptime.today();
        return new Header(service.site(), service.viewer(), service.view(), uptime.last24Hours(id),
                uptime.last60Days(id), uptime.days(id, today.minusDays(StatusPage.BAR_DAYS - 1), today));
    }

    /**
     * Page {@code page} (0 = newest) of the service's events as the viewer may see them, newest
     * first. A page past the last one gives the last page.
     */
    EventPage events(Service service, int page) {
        List<HistoryEvent> all = new ArrayList<>(history.events(service.endpoint().id()));
        all.sort(Comparator.comparing(HistoryEvent::start).thenComparing(HistoryEvent::id).reversed());
        int total = all.size();
        int pages = Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE);
        int shown = Math.max(0, Math.min(page, pages - 1));
        List<EventView> views = new ArrayList<>();
        int from = Math.min(total, shown * PAGE_SIZE);
        int to = Math.min(total, from + PAGE_SIZE);
        for (HistoryEvent event : all.subList(from, to)) {
            filter.event(service.viewer(), service.endpoint(), event).ifPresent(views::add);
        }
        return new EventPage(views, shown, pages, total);
    }

    /** One event of the service as the viewer may see it, or empty when there's no such event for this endpoint. */
    Optional<EventView> event(Service service, String eventId) {
        if (!EVENT_ID.matcher(eventId).matches()) {
            return Optional.empty();
        }
        return history.event(service.endpoint().id(), eventId)
                .flatMap(e -> filter.event(service.viewer(), service.endpoint(), e));
    }

    /** {@code ?page=N}: a non-negative decimal number, else 0. */
    static int pageNumber(String value) {
        if (value == null || value.isEmpty() || value.length() > 9) return 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') return 0;
        }
        return Integer.parseInt(value);
    }

    private void page(HTTPRequest request) throws Exception {
        String path = request.path();
        if (!path.startsWith(PREFIX)) return;
        String[] parts = path.substring(PREFIX.length()).split("/", -1);
        if (parts.length == 1) {
            servicePage(request, parts[0]);
        } else if (parts.length == 3 && parts[1].equals("event")) {
            eventPage(request, parts[0], parts[2]);
        }
        // Anything else: nothing written → 404.
    }

    private void servicePage(HTTPRequest request, String id) throws Exception {
        Optional<Service> found = service(request, id);
        if (found.isEmpty()) return; // nothing written → 404
        request.allowedMethods("GET");
        Service service = found.get();
        Site site = service.site();
        StringBuilder html = new StringBuilder(16384);
        PageSupport.head(html, site, service.view().name() + " – " + site.title(), false);
        html.append("<p class=\"crumbs\"><a href=\"").append(Html.escape(site.link("/"))).append("\">All services</a></p>\n");
        headerHtml(html, header(service));
        // The list view is the default: any ?view= other than "calendar" shows it.
        boolean calendar = "calendar".equals(request.get().get("view"));
        tabsHtml(html, service, calendar);
        int page = pageNumber(request.get().get("page"));
        if (calendar) {
            calendarHtml(html, service, calendar(service, page));
        } else {
            listHtml(html, service, events(service, page));
        }
        PageSupport.foot(html, site);
        write(request, "200 OK", "text/html; charset=utf-8", html.toString());
    }

    private void eventPage(HTTPRequest request, String id, String eventId) throws Exception {
        Optional<Service> found = service(request, id);
        if (found.isEmpty()) return; // nothing written → 404
        Optional<EventView> event = event(found.get(), eventId);
        if (event.isEmpty()) return;
        request.allowedMethods("GET");
        Service service = found.get();
        Site site = service.site();
        EventView e = event.get();
        String serviceLink = site.link(PREFIX + service.view().id());
        StringBuilder html = new StringBuilder(8192);
        PageSupport.head(html, site, e.title() + " – " + site.title(), false);
        html.append("<p class=\"crumbs\"><a href=\"").append(Html.escape(site.link("/"))).append("\">All services</a> › ")
                .append("<a href=\"").append(Html.escape(serviceLink)).append("\">")
                .append(Html.escape(service.view().name())).append("</a></p>\n")
                .append("<article class=\"event-page\" data-event=\"").append(Html.escape(e.id())).append("\">\n")
                .append("<h2 class=\"event-page-title\">").append(Html.escape(e.title())).append("</h2>\n")
                .append("<dl class=\"facts\">\n")
                .append("<div class=\"fact fact-service\"><dt>Service</dt><dd><a href=\"").append(Html.escape(serviceLink))
                .append("\">").append(Html.escape(service.view().name())).append("</a></dd></div>\n");
        fact(html, "start", "Start", time(e.start()));
        fact(html, "end", "End", e.ongoing() ? "ongoing" : time(e.end()));
        fact(html, "duration", "Duration", Durations.format(e.duration(clock.instant())));
        failureFacts(html, e);
        html.append("</dl>\n");
        flagsHtml(html, e);
        html.append("<h3 class=\"notes-title\">Notes</h3>\n");
        notesHtml(html, e.notes());
        html.append("</article>\n");
        PageSupport.foot(html, site);
        write(request, "200 OK", "text/html; charset=utf-8", html.toString());
    }

    /** The view tabs: plain links, the current one marked with {@code aria-current}. */
    private static void tabsHtml(StringBuilder html, Service service, boolean calendar) {
        String base = PREFIX + service.view().id() + "?view=";
        html.append("<nav class=\"tabs\" aria-label=\"Views\">");
        tab(html, service.site().link(base + "list"), "List", !calendar);
        tab(html, service.site().link(base + "calendar"), "Calendar", calendar);
        html.append("</nav>\n");
    }

    private static void tab(StringBuilder html, String href, String label, boolean current) {
        html.append("<a class=\"tab").append(current ? " tab-current" : "").append("\" href=\"").append(Html.escape(href))
                .append('"').append(current ? " aria-current=\"page\"" : "").append('>').append(label).append("</a>");
    }

    /**
     * The calendar view: each month a Monday-first grid of day blocks without text, coloured
     * like the bar (future days are empty outlines), with Earlier/Later links.
     */
    private void calendarHtml(StringBuilder html, Service service, CalendarPage page) {
        Site site = service.site();
        String base = PREFIX + service.view().id() + "?view=calendar&page=";
        html.append("<section class=\"calendar\" id=\"calendar\">\n<h3 class=\"visually-hidden\">Calendar</h3>\n")
                .append("<div class=\"cal-months\">\n");
        for (CalendarMonth month : page.months()) {
            String title = month.month().getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + month.month().getYear();
            html.append("<div class=\"cal-month\" data-month=\"").append(month.month()).append("\">\n")
                    .append("<h4 class=\"cal-month-title\">").append(title).append("</h4>\n")
                    .append("<div class=\"cal-grid\" role=\"group\" aria-label=\"").append(title).append("\">");
            for (DayOfWeek weekday : DayOfWeek.values()) {
                html.append("<span class=\"cal-weekday\" aria-hidden=\"true\">")
                        .append(weekday.getDisplayName(TextStyle.SHORT, Locale.ENGLISH), 0, 2).append("</span>");
            }
            for (int i = 0; i < month.leadingBlanks(); i++) {
                html.append("<span class=\"cal-blank\" aria-hidden=\"true\"></span>");
            }
            for (CalendarDay day : month.days()) {
                String tip = day.future() ? day.date() + ": upcoming" : PageSupport.tooltip(day.summary());
                html.append("<span class=\"cal-day cal-").append(day.future() ? "future" : day.color().id())
                        .append("\" data-date=\"").append(day.date()).append("\" role=\"img\" title=\"").append(Html.escape(tip))
                        .append("\" aria-label=\"").append(Html.escape(tip)).append("\"></span>");
            }
            html.append("</div>\n</div>\n");
        }
        html.append("</div>\n<nav class=\"pager\" aria-label=\"Calendar pages\">");
        if (page.hasEarlier()) {
            html.append("<a rel=\"next\" href=\"").append(Html.escape(site.link(base + (page.page() + 1))))
                    .append("#calendar\">← Earlier</a>");
        } else {
            html.append("<span></span>");
        }
        if (page.hasLater()) {
            html.append("<a rel=\"prev\" href=\"").append(Html.escape(site.link(base + (page.page() - 1))))
                    .append("#calendar\">Later →</a>");
        } else {
            html.append("<span></span>");
        }
        html.append("</nav>\n</section>\n");
    }

    /** The list view: events newest first, each a {@code <details>} with its notes, plus paging links. */
    private void listHtml(StringBuilder html, Service service, EventPage page) {
        Site site = service.site();
        String servicePath = PREFIX + service.view().id();
        html.append("<section class=\"events\" id=\"events\">\n<h3 class=\"events-title\">Downtime history</h3>\n");
        if (page.total() == 0) {
            html.append("<p class=\"muted events-empty\">No downtime recorded.</p>\n</section>\n");
            return;
        }
        Instant now = clock.instant();
        html.append("<ol class=\"events-list\">\n");
        for (EventView e : page.events()) {
            int notes = e.notes().size();
            html.append("<li class=\"event").append(e.ongoing() ? " event-ongoing" : "").append("\" id=\"event-")
                    .append(Html.escape(e.id())).append("\">\n<details>\n<summary>")
                    .append("<span class=\"event-title\">").append(Html.escape(e.title())).append("</span>")
                    .append("<span class=\"event-meta\"><span class=\"event-when\">").append(timeTag(e.start()))
                    .append(" – <span class=\"event-end\">").append(e.ongoing() ? "ongoing" : timeTag(e.end()))
                    .append("</span></span><span class=\"event-duration\"><span class=\"visually-hidden\">Duration: </span>")
                    .append(Durations.format(e.duration(now))).append("</span><span class=\"event-note-count\">")
                    .append(notes).append(notes == 1 ? " note" : " notes")
                    .append("</span></span></summary>\n<div class=\"event-body\">\n");
            if (e.firstFailureMessage() != null || e.lastFailureMessage() != null) {
                html.append("<dl class=\"facts\">\n");
                failureFacts(html, e);
                html.append("</dl>\n");
            }
            flagsHtml(html, e);
            notesHtml(html, e.notes());
            html.append("<p class=\"event-link\"><a href=\"")
                    .append(Html.escape(site.link(servicePath + "/event/" + e.id()))).append("\">Permalink</a></p>\n")
                    .append("</div>\n</details>\n</li>\n");
        }
        html.append("</ol>\n");
        if (page.pages() > 1) {
            String base = servicePath + "?view=list&page=";
            html.append("<nav class=\"pager\" aria-label=\"Event pages\">");
            if (page.hasNewer()) {
                html.append("<a rel=\"prev\" href=\"").append(Html.escape(site.link(base + (page.page() - 1))))
                        .append("#events\">← Newer</a>");
            } else {
                html.append("<span></span>");
            }
            html.append("<span class=\"muted\">Page ").append(page.page() + 1).append(" of ").append(page.pages()).append("</span>");
            if (page.hasOlder()) {
                html.append("<a rel=\"next\" href=\"").append(Html.escape(site.link(base + (page.page() + 1))))
                        .append("#events\">Older →</a>");
            } else {
                html.append("<span></span>");
            }
            html.append("</nav>\n");
        }
        html.append("</section>\n");
    }

    /** Failure messages, where {@link ViewFilter} provides them. */
    private static void failureFacts(StringBuilder html, EventView e) {
        if (e.firstFailureMessage() != null) fact(html, "first-failure", "First failure", e.firstFailureMessage());
        if (e.lastFailureMessage() != null) fact(html, "last-failure", "Last failure", e.lastFailureMessage());
    }

    /** How a closed event ended, for privileged viewers (the flags are {@code null} for everyone else). */
    private static void flagsHtml(StringBuilder html, EventView e) {
        if (Boolean.TRUE.equals(e.endedUnobserved())) {
            html.append("<p class=\"muted event-flag\">Recovery not observed: monitoring stopped while it was down.</p>\n");
        }
        if (Boolean.TRUE.equals(e.endedByConfigChange())) {
            html.append("<p class=\"muted event-flag\">Ended by a change to the check settings.</p>\n");
        }
    }

    /** The visible notes, oldest first; the text is escaped and keeps its newlines (via CSS). */
    private void notesHtml(StringBuilder html, List<NoteView> notes) {
        if (notes.isEmpty()) {
            html.append("<p class=\"muted notes-empty\">No notes.</p>\n");
            return;
        }
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
            html.append("</p>\n<p class=\"note-text\">").append(Html.escape(note.text())).append("</p></li>\n");
        }
        html.append("</ul>\n");
    }

    /** A {@code <time>} element: the UTC instant as {@code datetime}, the local time as text. */
    private String timeTag(Instant instant) {
        return "<time datetime=\"" + instant + "\">" + time(instant) + "</time>";
    }

    private void headerHtml(StringBuilder html, Header header) {
        Site site = header.site();
        EndpointView service = header.view();
        String state = stateId(service.state());
        html.append("<section class=\"service-header state-").append(state).append("\" data-service=\"")
                .append(Html.escape(service.id())).append("\">\n")
                .append("<h2 class=\"service-title\"><span class=\"dot state-").append(state).append("\" aria-hidden=\"true\"></span>")
                .append("<span class=\"name\">").append(Html.escape(service.name())).append("</span></h2>\n")
                .append("<p class=\"state-line\"><span class=\"state-label state-").append(state).append("\">")
                .append(stateLabel(service.state())).append("</span>");
        if (service.since() != null) {
            html.append(" <span class=\"muted\">since ").append(time(service.since())).append("</span>");
        }
        html.append("</p>\n<dl class=\"facts\">\n");
        fact(html, "uptime-24h", "Uptime 24h", header.day().format());
        fact(html, "uptime-60d", "Uptime 60d", header.sixtyDays().format());
        if (service.type() != null) fact(html, "type", "Type", service.type());
        if (service.visibility() != null) fact(html, "visibility", "Visibility", service.visibility().configName());
        if (service.targets() != null && !service.targets().isEmpty()) {
            html.append("<div class=\"fact fact-targets\"><dt>Target</dt><dd><ul class=\"plain\">");
            for (Target target : service.targets()) {
                html.append("<li><span class=\"muted\">").append(Html.escape(target.label())).append(":</span> ")
                        .append("<code>").append(Html.escape(target.value())).append("</code></li>");
            }
            html.append("</ul></dd></div>\n");
        }
        CheckView last = service.lastCheck();
        if (last != null) {
            fact(html, "last-check", "Last check", (last.success() ? "pass" : "fail") + " at " + time(last.at()));
            if (last.latency() != null) fact(html, "latency", "Latency", last.latency().toMillis() + " ms");
            if (last.message() != null) fact(html, "message", "Message", last.message());
            if (last.details() != null && !last.details().isEmpty()) {
                html.append("<div class=\"fact fact-details\"><dt>Details</dt><dd><ul class=\"plain\">");
                for (Map.Entry<String, Object> e : last.details().entrySet()) {
                    html.append("<li><span class=\"muted\">").append(Html.escape(e.getKey())).append(":</span> ")
                            .append(Html.escape(String.valueOf(e.getValue()))).append("</li>");
                }
                html.append("</ul></dd></div>\n");
            }
        }
        html.append("</dl>\n");
        PageSupport.bar(html, site, service.id(), service.name(), header.days(), dashboard);
        checkStrip(html, service.recentChecks());
        html.append("</section>\n");
    }

    /** Recent checks, oldest first, as pass/fail blocks with a summary for screen readers. */
    private void checkStrip(StringBuilder html, List<CheckView> checks) {
        html.append("<h3 class=\"checks-title\">Recent checks</h3>\n");
        if (checks.isEmpty()) {
            html.append("<p class=\"muted checks-empty\">No checks yet.</p>\n");
            return;
        }
        long failed = checks.stream().filter(c -> !c.success()).count();
        String summary = checks.size() + (checks.size() == 1 ? " check: " : " checks: ")
                + (checks.size() - failed) + " passed, " + failed + " failed";
        html.append("<div class=\"checks\" role=\"img\" aria-label=\"").append(Html.escape(summary)).append("\">");
        for (CheckView check : checks) {
            StringBuilder tip = new StringBuilder(time(check.at())).append(": ").append(check.success() ? "pass" : "fail");
            if (check.latency() != null) tip.append(", ").append(check.latency().toMillis()).append(" ms");
            if (check.message() != null && !check.message().isEmpty()) tip.append(", ").append(check.message());
            html.append("<span class=\"check check-").append(check.success() ? "pass" : "fail")
                    .append("\" title=\"").append(Html.escape(tip.toString())).append("\"></span>");
        }
        html.append("</div>\n<div class=\"bar-legend\" aria-hidden=\"true\"><span>older</span><span>latest</span></div>\n");
    }

    private static void fact(StringBuilder html, String key, String label, String value) {
        html.append("<div class=\"fact fact-").append(key).append("\"><dt>").append(Html.escape(label))
                .append("</dt><dd>").append(Html.escape(value)).append("</dd></div>\n");
    }

    /** A timestamp in the configured timezone, e.g. {@code 2026-11-13 09:00:00}. */
    private String time(Instant instant) {
        return TIME.format(instant.atZone(uptime.zone()));
    }
}
