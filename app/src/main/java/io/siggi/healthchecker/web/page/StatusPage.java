package io.siggi.healthchecker.web.page;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import io.siggi.healthchecker.config.DashboardConfig;
import io.siggi.healthchecker.history.DaySummary;
import io.siggi.healthchecker.history.Uptime;
import io.siggi.healthchecker.history.UptimeCalculator;
import io.siggi.healthchecker.plugin.JsonEndpointConfig.Target;
import io.siggi.healthchecker.schedule.Endpoint;
import io.siggi.healthchecker.status.EndpointStatus;
import io.siggi.healthchecker.status.State;
import io.siggi.healthchecker.web.Html;
import io.siggi.healthchecker.web.WebServer;
import io.siggi.healthchecker.web.layout.ResolvedLayout;
import io.siggi.healthchecker.web.site.Site;
import io.siggi.healthchecker.web.site.SiteResolver;
import io.siggi.healthchecker.web.view.CategoryGrouping;
import io.siggi.healthchecker.web.view.CategorySection;
import io.siggi.healthchecker.web.view.CheckView;
import io.siggi.healthchecker.web.view.EndpointView;
import io.siggi.healthchecker.web.view.ViewFilter;
import io.siggi.healthchecker.web.view.Viewer;
import io.siggi.http.HTTPRequest;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Function;
import java.util.function.Supplier;

import static io.siggi.healthchecker.web.page.PageSupport.stateId;
import static io.siggi.healthchecker.web.page.PageSupport.stateLabel;
import static io.siggi.healthchecker.web.page.PageSupport.write;

/**
 * The main page ({@code /}) and {@code GET /api/status}. Both are built from the same data:
 * the effective layout reduced to the request's {@link Site} scope first, then grouped for the
 * viewer (visibility applied on top), plus uptime and the last 60 day summaries of each visible
 * service. Overall state and the API's 200/503 code come from the services the viewer can see
 * within the scope (the HTML page is always 200). Links use the site's configured base URL only.
 */
public final class StatusPage {
    /** Number of day segments in the bar, oldest first, ending today. */
    public static final int BAR_DAYS = 60;
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();

    private final SiteResolver sites;
    private final Supplier<ResolvedLayout> layout;
    private final Function<String, Optional<Endpoint>> endpoints;
    private final Function<String, Optional<EndpointStatus>> statuses;
    private final Function<HTTPRequest, Viewer> viewers;
    private final UptimeCalculator uptime;
    private final DashboardConfig dashboard;
    private final CategoryGrouping grouping = new CategoryGrouping(new ViewFilter());

    /**
     * @param layout    the current effective (unscoped) layout
     * @param endpoints live endpoint by id
     * @param statuses  live status by endpoint id
     * @param viewers   who is making the request
     * @param uptime    uptime and day summaries (and today's date in the configured timezone)
     * @param dashboard colour thresholds for the day segments
     */
    public StatusPage(SiteResolver sites, Supplier<ResolvedLayout> layout,
                      Function<String, Optional<Endpoint>> endpoints,
                      Function<String, Optional<EndpointStatus>> statuses,
                      Function<HTTPRequest, Viewer> viewers,
                      UptimeCalculator uptime, DashboardConfig dashboard) {
        this.sites = sites;
        this.layout = layout;
        this.endpoints = endpoints;
        this.statuses = statuses;
        this.viewers = viewers;
        this.uptime = uptime;
        this.dashboard = dashboard;
    }

    public void register(WebServer server) {
        server.register("/", this::page);
        server.register("/api/status", this::api);
    }

    /** One visible service with its uptime figures. */
    record Row(EndpointView view, Uptime day, Uptime sixtyDays, List<DaySummary> days) {
    }

    /** One visible category with its rows. */
    record Section(CategorySection category, List<Row> rows) {
    }

    /** What both representations show: the site, the viewer's sections and the overall state. */
    record Model(Site site, Viewer viewer, List<Section> sections, State overall, int down, int unknown) {
    }

    /** The model for the request, or empty when its host is rejected. */
    Optional<Model> model(HTTPRequest request) {
        Optional<Site> site = sites.resolve(request);
        if (site.isEmpty()) {
            return Optional.empty();
        }
        Viewer viewer = viewers.apply(request);
        ResolvedLayout scoped = site.get().scope(layout.get());
        List<CategorySection> grouped = grouping.group(viewer, scoped, endpoints, statuses);
        LocalDate today = uptime.today();
        LocalDate first = today.minusDays(BAR_DAYS - 1);
        int down = 0;
        int unknown = 0;
        List<Section> sections = new ArrayList<>(grouped.size());
        for (CategorySection category : grouped) {
            down += category.summary().down();
            unknown += category.summary().unknown();
            List<Row> rows = new ArrayList<>(category.services().size());
            for (EndpointView view : category.services()) {
                rows.add(new Row(view, uptime.last24Hours(view.id()), uptime.last60Days(view.id()),
                        uptime.days(view.id(), first, today)));
            }
            sections.add(new Section(category, rows));
        }
        State overall = down > 0 ? State.DOWN : unknown > 0 ? State.UNKNOWN : State.UP;
        return Optional.of(new Model(site.get(), viewer, sections, overall, down, unknown));
    }

    // ---- HTML ----

    private void page(HTTPRequest request) throws Exception {
        if (!request.path().equals("/")) return; // nothing written → 404
        Optional<Model> found = model(request);
        if (found.isEmpty()) return;
        request.allowedMethods("GET");
        Model model = found.get();
        Site site = model.site();
        StringBuilder html = new StringBuilder(16384);
        PageSupport.head(html, site, site.title(), true);
        String overall = stateId(model.overall());
        html.append("<p class=\"banner banner-").append(overall).append("\" role=\"status\">")
                .append("<span class=\"dot state-").append(overall).append("\" aria-hidden=\"true\"></span>")
                .append(Html.escape(bannerText(model))).append("</p>\n");
        for (Section section : model.sections()) {
            CategorySection category = section.category();
            html.append("<section class=\"category\">\n");
            if (category.hasHeading()) {
                html.append("<div class=\"category-head\"><h2>").append(Html.escape(category.name())).append("</h2>")
                        .append("<span class=\"summary ").append(category.summary().down() > 0 ? "summary-down" : "summary-ok")
                        .append("\">").append(Html.escape(category.summary().text())).append("</span></div>\n");
                if (category.description() != null) {
                    html.append("<p class=\"description\">").append(Html.escape(category.description())).append("</p>\n");
                }
            }
            html.append("<ul class=\"services\">\n");
            for (Row row : section.rows()) {
                serviceRow(html, site, row);
            }
            html.append("</ul>\n</section>\n");
        }
        if (model.sections().isEmpty()) {
            html.append("<p class=\"empty muted\">No services to show.</p>\n");
        }
        PageSupport.foot(html, site);
        write(request, "200 OK", "text/html; charset=utf-8", html.toString());
    }

    private void serviceRow(StringBuilder html, Site site, Row row) {
        EndpointView service = row.view();
        String state = stateId(service.state());
        html.append("<li class=\"service state-").append(state).append("\" data-service=\"").append(Html.escape(service.id())).append("\">\n")
                .append("<a class=\"service-head\" href=\"").append(Html.escape(site.link("/service/" + service.id()))).append("\">")
                .append("<span class=\"dot state-").append(state).append("\"></span>")
                .append("<span class=\"visually-hidden\">").append(stateLabel(service.state())).append(": </span>")
                .append("<span class=\"name\">").append(Html.escape(service.name())).append("</span>")
                .append("<span class=\"uptimes\">")
                .append("<span class=\"uptime\"><span class=\"label\">24h</span> ").append(Html.escape(row.day().format())).append("</span>")
                .append("<span class=\"uptime\"><span class=\"label\">60d</span> ").append(Html.escape(row.sixtyDays().format())).append("</span>")
                .append("</span></a>\n");
        PageSupport.bar(html, site, service.id(), service.name(), row.days(), dashboard);
        html.append("</li>\n");
    }

    private static String bannerText(Model model) {
        if (model.down() > 0) {
            return model.down() == 1 ? "1 service down" : model.down() + " services down";
        }
        if (model.unknown() > 0) {
            return "Waiting for the first checks";
        }
        return "All systems operational";
    }

    // ---- JSON ----

    private void api(HTTPRequest request) throws Exception {
        if (!request.path().equals("/api/status")) return;
        Optional<Model> found = model(request);
        if (found.isEmpty()) return;
        request.allowedMethods("GET");
        Model model = found.get();
        JsonObject root = new JsonObject();
        root.addProperty("overall", model.overall().name());
        root.addProperty("viewer", model.viewer().anonymous() ? "anonymous" : model.viewer().userName());
        JsonArray categories = new JsonArray();
        for (Section section : model.sections()) {
            CategorySection cs = section.category();
            JsonObject category = new JsonObject();
            if (cs.id() != null) category.addProperty("id", cs.id());
            if (cs.name() != null) category.addProperty("name", cs.name());
            if (cs.description() != null) category.addProperty("description", cs.description());
            JsonObject summary = new JsonObject();
            summary.addProperty("up", cs.summary().total() - cs.summary().down() - cs.summary().unknown());
            summary.addProperty("down", cs.summary().down());
            summary.addProperty("unknown", cs.summary().unknown());
            category.add("summary", summary);
            JsonArray services = new JsonArray();
            for (Row row : section.rows()) {
                services.add(endpointJson(row));
            }
            category.add("endpoints", services);
            categories.add(category);
        }
        root.add("categories", categories);
        write(request, model.overall() == State.DOWN ? "503 Service Unavailable" : "200 OK",
                "application/json; charset=utf-8", GSON.toJson(root));
    }

    private JsonObject endpointJson(Row row) {
        EndpointView service = row.view();
        JsonObject endpoint = new JsonObject();
        endpoint.addProperty("id", service.id());
        endpoint.addProperty("name", service.name());
        if (service.type() != null) endpoint.addProperty("type", service.type());
        endpoint.addProperty("state", service.state().name());
        if (service.since() != null) endpoint.addProperty("since", service.since().toString());
        JsonObject uptimes = new JsonObject();
        uptimes.add("24h", percent(row.day()));
        uptimes.add("60d", percent(row.sixtyDays()));
        endpoint.add("uptime", uptimes);
        JsonArray days = new JsonArray();
        for (DaySummary day : row.days()) {
            JsonObject d = new JsonObject();
            d.addProperty("date", day.date().toString());
            d.add("uptime", percent(day.uptime()));
            d.addProperty("downtimeSeconds", day.uptime().downtime().getSeconds());
            d.addProperty("events", day.events());
            if (day.maintenanceEvents() > 0) d.addProperty("maintenanceEvents", day.maintenanceEvents());
            d.addProperty("color", DayColor.of(day, dashboard).id());
            days.add(d);
        }
        endpoint.add("days", days);
        if (service.lastCheck() != null) {
            endpoint.add("last", checkJson(service.lastCheck()));
        }
        // Privileged viewers only (ViewFilter leaves these null for everyone else).
        if (service.visibility() != null) endpoint.addProperty("visibility", service.visibility().configName());
        if (service.targets() != null) {
            JsonArray targets = new JsonArray();
            for (Target target : service.targets()) {
                JsonObject t = new JsonObject();
                t.addProperty("label", target.label());
                t.addProperty("value", target.value());
                targets.add(t);
            }
            endpoint.add("targets", targets);
        }
        return endpoint;
    }

    private static JsonObject checkJson(CheckView check) {
        JsonObject last = new JsonObject();
        last.addProperty("at", check.at().toString());
        if (check.latency() != null) last.addProperty("latencyMs", check.latency().toMillis());
        last.addProperty("success", check.success());
        if (check.message() != null) last.addProperty("message", check.message());
        if (check.details() != null) {
            JsonObject details = new JsonObject();
            for (Map.Entry<String, Object> e : check.details().entrySet()) {
                Object v = e.getValue();
                if (v instanceof Number n) details.addProperty(e.getKey(), n);
                else if (v instanceof Boolean b) details.addProperty(e.getKey(), b);
                else details.addProperty(e.getKey(), String.valueOf(v));
            }
            last.add("details", details);
        }
        return last;
    }

    /** The percentage as a number, or JSON {@code null} without data. */
    private static JsonElement percent(Uptime uptime) {
        OptionalDouble p = uptime.percent();
        return p.isPresent() ? new JsonPrimitive(p.getAsDouble()) : JsonNull.INSTANCE;
    }
}
