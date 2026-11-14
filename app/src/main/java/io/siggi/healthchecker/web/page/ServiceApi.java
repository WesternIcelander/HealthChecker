package io.siggi.healthchecker.web.page;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.siggi.healthchecker.config.DashboardConfig;
import io.siggi.healthchecker.status.State;
import io.siggi.healthchecker.web.WebServer;
import io.siggi.healthchecker.web.page.ServicePage.EventPage;
import io.siggi.healthchecker.web.page.ServicePage.Header;
import io.siggi.healthchecker.web.page.ServicePage.Service;
import io.siggi.healthchecker.web.view.CheckView;
import io.siggi.healthchecker.web.view.EndpointView;
import io.siggi.healthchecker.web.view.EventView;
import io.siggi.http.HTTPRequest;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static io.siggi.healthchecker.web.page.PageSupport.write;

/**
 * The per-service JSON API, built from the same data and with the same {@code 404} rules as the
 * service page ({@link ServicePage#service}: rejected host, unknown or malformed id, out of the
 * site scope, not visible to the viewer):
 * <ul>
 *     <li>{@code GET /api/service/{id}}: the endpoint as in {@code /api/status} plus its recent
 *     checks; {@code 503} while it's down, like {@code /api/status}.</li>
 *     <li>{@code GET /api/service/{id}/events?page=N}: one page of the event list, newest first.</li>
 *     <li>{@code GET /api/service/{id}/days?from=…&to=…}: day summaries, at most
 *     {@value #MAX_DAYS} days per request.</li>
 *     <li>{@code GET /api/service/{id}/event/{eventId}}: one event with its visible notes.</li>
 * </ul>
 */
final class ServiceApi {
    private static final String PREFIX = "/api/service/";
    /** The most days one {@code /days} request may cover. */
    static final int MAX_DAYS = 400;

    private final ServicePage pages;
    private final DashboardConfig dashboard;
    private final Clock clock;

    ServiceApi(ServicePage pages, DashboardConfig dashboard, Clock clock) {
        this.pages = pages;
        this.dashboard = dashboard;
        this.clock = clock;
    }

    void register(WebServer server) {
        server.register(PREFIX, this::api);
    }

    private void api(HTTPRequest request) throws Exception {
        String path = request.path();
        if (!path.startsWith(PREFIX)) return;
        String[] parts = path.substring(PREFIX.length()).split("/", -1);
        if (parts.length == 1) {
            service(request, parts[0]);
        } else if (parts.length == 2 && parts[1].equals("events")) {
            events(request, parts[0]);
        } else if (parts.length == 2 && parts[1].equals("days")) {
            days(request, parts[0]);
        } else if (parts.length == 3 && parts[1].equals("event")) {
            event(request, parts[0], parts[2]);
        }
        // Anything else: nothing written → 404.
    }

    private void service(HTTPRequest request, String id) throws Exception {
        Optional<Service> found = pages.service(request, id);
        if (found.isEmpty()) return; // nothing written → 404
        request.allowedMethods("GET");
        Service service = found.get();
        Header header = pages.header(service);
        EndpointView view = service.view();
        JsonObject json = JsonViews.endpoint(view, header.day(), header.sixtyDays(), header.days(), dashboard,
                header.maintenanceDays());
        JsonArray checks = new JsonArray();
        for (CheckView check : view.recentChecks()) {
            checks.add(JsonViews.check(check));
        }
        json.add("recentChecks", checks);
        // Privileged viewers only (null for everyone else).
        if (view.notifyChannels() != null) {
            JsonArray notify = new JsonArray();
            view.notifyChannels().forEach(notify::add);
            json.add("notify", notify);
        }
        if (view.reminders() != null) {
            JsonArray reminders = new JsonArray();
            view.reminders().forEach(reminders::add);
            json.add("reminders", reminders);
        }
        if (view.reminderRepeatMinutes() != null) json.addProperty("reminderRepeatMinutes", view.reminderRepeatMinutes());
        json.addProperty("viewer", JsonViews.viewer(service.viewer()));
        write(request, view.state() == State.DOWN ? "503 Service Unavailable" : "200 OK",
                JsonViews.CONTENT_TYPE, JsonViews.GSON.toJson(json));
    }

    private void events(HTTPRequest request, String id) throws Exception {
        Optional<Service> found = pages.service(request, id);
        if (found.isEmpty()) return;
        request.allowedMethods("GET");
        EventPage page = pages.events(found.get(), ServicePage.pageNumber(request.get().get("page")),
                ServicePage.EventFilter.parse(request.get().get("filter")));
        JsonObject json = new JsonObject();
        json.addProperty("page", page.page());
        json.addProperty("pages", page.pages());
        json.addProperty("pageSize", ServicePage.PAGE_SIZE);
        json.addProperty("total", page.total());
        JsonArray events = new JsonArray();
        for (EventView event : page.events()) {
            events.add(JsonViews.event(event, clock.instant()));
        }
        json.add("events", events);
        write(request, "200 OK", JsonViews.CONTENT_TYPE, JsonViews.GSON.toJson(json));
    }

    private void days(HTTPRequest request, String id) throws Exception {
        Optional<Service> found = pages.service(request, id);
        if (found.isEmpty()) return;
        request.allowedMethods("GET");
        String fromText = request.get().get("from");
        String toText = request.get().get("to");
        LocalDate to = toText == null ? pages.today() : ServicePage.parseDate(toText);
        if (to == null) {
            JsonViews.error(request, "400 Bad Request", "to must be a date (yyyy-mm-dd)");
            return;
        }
        LocalDate from = fromText == null ? to.minusDays(StatusPage.BAR_DAYS - 1) : ServicePage.parseDate(fromText);
        if (from == null) {
            JsonViews.error(request, "400 Bad Request", "from must be a date (yyyy-mm-dd)");
            return;
        }
        if (from.isAfter(to)) {
            JsonViews.error(request, "400 Bad Request", "from must not be after to");
            return;
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_DAYS) {
            JsonViews.error(request, "400 Bad Request", "at most " + MAX_DAYS + " days per request");
            return;
        }
        JsonObject json = new JsonObject();
        json.addProperty("from", from.toString());
        json.addProperty("to", to.toString());
        json.add("days", JsonViews.days(pages.days(found.get(), from, to), dashboard,
                pages.maintenanceDays(found.get().endpoint().id(), from, to)));
        write(request, "200 OK", JsonViews.CONTENT_TYPE, JsonViews.GSON.toJson(json));
    }

    private void event(HTTPRequest request, String id, String eventId) throws Exception {
        Optional<Service> found = pages.service(request, id);
        if (found.isEmpty()) return;
        Optional<EventView> event = pages.event(found.get(), eventId);
        if (event.isEmpty()) return;
        request.allowedMethods("GET");
        JsonObject json = JsonViews.event(event.get(), clock.instant());
        json.addProperty("service", found.get().view().id());
        write(request, "200 OK", JsonViews.CONTENT_TYPE, JsonViews.GSON.toJson(json));
    }
}
