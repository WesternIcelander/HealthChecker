package io.siggi.healthchecker.web.page;

import io.siggi.healthchecker.config.DashboardConfig;
import io.siggi.healthchecker.history.DaySummary;
import io.siggi.healthchecker.history.Uptime;
import io.siggi.healthchecker.history.UptimeCalculator;
import io.siggi.healthchecker.plugin.JsonEndpointConfig.Target;
import io.siggi.healthchecker.schedule.Endpoint;
import io.siggi.healthchecker.status.EndpointStatus;
import io.siggi.healthchecker.web.Html;
import io.siggi.healthchecker.web.WebServer;
import io.siggi.healthchecker.web.layout.ResolvedLayout;
import io.siggi.healthchecker.web.site.Site;
import io.siggi.healthchecker.web.site.SiteResolver;
import io.siggi.healthchecker.web.view.CheckView;
import io.siggi.healthchecker.web.view.EndpointView;
import io.siggi.healthchecker.web.view.ViewFilter;
import io.siggi.healthchecker.web.view.Viewer;
import io.siggi.http.HTTPRequest;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
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
 * of the last check (and, for privileged viewers, type, visibility and targets).
 *
 * <p>Answers {@code 404} (by writing nothing) for unknown ids, for endpoints outside the
 * request's site scope (even for privileged viewers), for endpoints the viewer may not see
 * ({@code private} without {@code view.private}), and for rejected hosts, so a private
 * endpoint's existence isn't revealed.
 */
public final class ServicePage {
    private static final String PREFIX = "/service/";
    private static final Pattern ID = Pattern.compile("[a-z0-9-]+");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final SiteResolver sites;
    private final Supplier<ResolvedLayout> layout;
    private final Function<String, Optional<Endpoint>> endpoints;
    private final Function<String, Optional<EndpointStatus>> statuses;
    private final Function<HTTPRequest, Viewer> viewers;
    private final UptimeCalculator uptime;
    private final DashboardConfig dashboard;
    private final ViewFilter filter = new ViewFilter();

    /** Same sources as {@link StatusPage}. */
    public ServicePage(SiteResolver sites, Supplier<ResolvedLayout> layout,
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
        server.register(PREFIX, this::page);
    }

    /** What the header shows. */
    record Header(Site site, Viewer viewer, EndpointView view, Uptime day, Uptime sixtyDays, List<DaySummary> days) {
    }

    /**
     * The header for the service, or empty when the request must get a {@code 404}: rejected host,
     * unknown id, out of the site's scope, or not visible to the viewer.
     */
    Optional<Header> header(HTTPRequest request, String id) {
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
        if (view.isEmpty()) {
            return Optional.empty();
        }
        LocalDate today = uptime.today();
        return Optional.of(new Header(site.get(), viewer, view.get(), uptime.last24Hours(id), uptime.last60Days(id),
                uptime.days(id, today.minusDays(StatusPage.BAR_DAYS - 1), today)));
    }

    private void page(HTTPRequest request) throws Exception {
        String path = request.path();
        if (!path.startsWith(PREFIX)) return;
        String id = path.substring(PREFIX.length());
        Optional<Header> found = header(request, id);
        if (found.isEmpty()) return; // nothing written → 404
        request.allowedMethods("GET");
        Header header = found.get();
        Site site = header.site();
        EndpointView service = header.view();
        StringBuilder html = new StringBuilder(16384);
        PageSupport.head(html, site, service.name() + " – " + site.title(), false);
        html.append("<p class=\"crumbs\"><a href=\"").append(Html.escape(site.link("/"))).append("\">All services</a></p>\n");
        headerHtml(html, header);
        PageSupport.foot(html, site);
        write(request, "200 OK", "text/html; charset=utf-8", html.toString());
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
