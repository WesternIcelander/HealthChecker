package io.siggi.healthchecker.web.page;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
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
import io.siggi.healthchecker.web.view.EndpointView;
import io.siggi.healthchecker.web.view.ViewFilter;
import io.siggi.healthchecker.web.view.Viewer;
import io.siggi.http.HTTPRequest;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The main page ({@code /}) and {@code GET /api/status}. Both are built from the same data:
 * the effective layout reduced to the request's {@link Site} scope first, then grouped for the
 * viewer (visibility applied on top). Overall state and the API's 200/503 code come from the services
 * the viewer can see within the scope (the HTML page is always 200). Links use the site's configured base URL only.
 */
public final class StatusPage {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final SiteResolver sites;
    private final Supplier<ResolvedLayout> layout;
    private final Function<String, Optional<Endpoint>> endpoints;
    private final Function<String, Optional<EndpointStatus>> statuses;
    private final Function<HTTPRequest, Viewer> viewers;
    private final CategoryGrouping grouping = new CategoryGrouping(new ViewFilter());

    /**
     * @param layout    the current effective (unscoped) layout
     * @param endpoints live endpoint by id
     * @param statuses  live status by endpoint id
     * @param viewers   who is making the request
     */
    public StatusPage(SiteResolver sites, Supplier<ResolvedLayout> layout,
                      Function<String, Optional<Endpoint>> endpoints,
                      Function<String, Optional<EndpointStatus>> statuses,
                      Function<HTTPRequest, Viewer> viewers) {
        this.sites = sites;
        this.layout = layout;
        this.endpoints = endpoints;
        this.statuses = statuses;
        this.viewers = viewers;
    }

    public void register(WebServer server) {
        server.register("/", this::page);
        server.register("/api/status", this::api);
    }

    /** What both representations show: the site, the viewer's sections and the overall state. */
    record Model(Site site, Viewer viewer, List<CategorySection> sections, State overall, int down, int unknown) {
    }

    /** The model for the request, or empty when its host is rejected. */
    Optional<Model> model(HTTPRequest request) {
        Optional<Site> site = sites.resolve(request);
        if (site.isEmpty()) {
            return Optional.empty();
        }
        Viewer viewer = viewers.apply(request);
        ResolvedLayout scoped = site.get().scope(layout.get());
        List<CategorySection> sections = grouping.group(viewer, scoped, endpoints, statuses);
        int down = 0;
        int unknown = 0;
        for (CategorySection section : sections) {
            down += section.summary().down();
            unknown += section.summary().unknown();
        }
        State overall = down > 0 ? State.DOWN : unknown > 0 ? State.UNKNOWN : State.UP;
        return Optional.of(new Model(site.get(), viewer, sections, overall, down, unknown));
    }

    private void page(HTTPRequest request) throws Exception {
        if (!request.path().equals("/")) return; // nothing written → 404
        Optional<Model> found = model(request);
        if (found.isEmpty()) return;
        request.allowedMethods("GET");
        Model model = found.get();
        Site site = model.site();
        StringBuilder html = new StringBuilder(4096);
        html.append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
                .append("<title>").append(Html.escape(site.title())).append("</title>\n")
                .append("<link rel=\"stylesheet\" href=\"").append(Html.escape(site.link("/static/style.css"))).append("\">\n")
                .append("</head>\n<body>\n<header><h1><a href=\"").append(Html.escape(site.link("/"))).append("\">")
                .append(Html.escape(site.title())).append("</a></h1></header>\n<main>\n")
                .append("<p class=\"banner banner-").append(model.overall().name().toLowerCase(Locale.ROOT)).append("\">")
                .append(Html.escape(bannerText(model))).append("</p>\n");
        for (CategorySection section : model.sections()) {
            html.append("<section class=\"category\">\n");
            if (section.hasHeading()) {
                html.append("<h2>").append(Html.escape(section.name())).append("</h2>\n");
                if (section.description() != null) {
                    html.append("<p class=\"description\">").append(Html.escape(section.description())).append("</p>\n");
                }
                html.append("<p class=\"summary\">").append(Html.escape(section.summary().text())).append("</p>\n");
            }
            html.append("<ul class=\"services\">\n");
            for (EndpointView service : section.services()) {
                html.append("<li class=\"service state-").append(service.state().name().toLowerCase(Locale.ROOT)).append("\">")
                        .append("<a href=\"").append(Html.escape(site.link("/service/" + service.id()))).append("\">")
                        .append(Html.escape(service.name())).append("</a></li>\n");
            }
            html.append("</ul>\n</section>\n");
        }
        html.append("</main>\n<script src=\"").append(Html.escape(site.link("/static/app.js")))
                .append("\" defer></script>\n</body>\n</html>\n");
        write(request, "200 OK", "text/html; charset=utf-8", html.toString());
    }

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
        for (CategorySection section : model.sections()) {
            JsonObject category = new JsonObject();
            if (section.id() != null) category.addProperty("id", section.id());
            if (section.name() != null) category.addProperty("name", section.name());
            if (section.description() != null) category.addProperty("description", section.description());
            JsonObject summary = new JsonObject();
            summary.addProperty("up", section.summary().total() - section.summary().down() - section.summary().unknown());
            summary.addProperty("down", section.summary().down());
            category.add("summary", summary);
            JsonArray services = new JsonArray();
            for (EndpointView service : section.services()) {
                JsonObject endpoint = new JsonObject();
                endpoint.addProperty("id", service.id());
                endpoint.addProperty("name", service.name());
                if (service.type() != null) endpoint.addProperty("type", service.type());
                endpoint.addProperty("state", service.state().name());
                if (service.since() != null) endpoint.addProperty("since", service.since().toString());
                services.add(endpoint);
            }
            category.add("endpoints", services);
            categories.add(category);
        }
        root.add("categories", categories);
        write(request, model.overall() == State.DOWN ? "503 Service Unavailable" : "200 OK",
                "application/json; charset=utf-8", GSON.toJson(root));
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

    private static void write(HTTPRequest request, String status, String contentType, String body) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        request.response.setHeader(status);
        request.response.setHeader("Content-Type", contentType);
        request.response.setHeader("Cache-Control", "no-store");
        request.response.setHeader("X-Content-Type-Options", "nosniff");
        request.response.setHeader("Content-Length", Integer.toString(bytes.length));
        request.response.write(bytes);
    }
}
