package io.siggi.healthchecker.web.page;

import io.siggi.healthchecker.auth.SessionManager;
import io.siggi.healthchecker.config.DashboardConfig;
import io.siggi.healthchecker.history.DaySummary;
import io.siggi.healthchecker.status.State;
import io.siggi.healthchecker.web.Durations;
import io.siggi.healthchecker.web.Html;
import io.siggi.healthchecker.web.site.Site;
import io.siggi.healthchecker.web.view.Viewer;
import io.siggi.http.HTTPRequest;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/** HTML and response helpers shared by the dashboard pages. */
final class PageSupport {
    private PageSupport() {
    }

    /**
     * Opens a page up to and including {@code <main>}. Every link is built with {@link Site#link}.
     *
     * @param title       the document title (already including the site title where wanted)
     * @param autoRefresh whether to add a 60 s {@code <meta http-equiv="refresh">}
     * @param viewer      who is signed in, for the header (name, role, sign-out button; a "Sign in"
     *                    link for anonymous visitors), or {@code null} for no user area (sign-in pages)
     */
    static void head(StringBuilder html, Site site, String title, boolean autoRefresh, Viewer viewer) {
        html.append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n");
        if (autoRefresh) {
            html.append("<meta http-equiv=\"refresh\" content=\"60\">\n");
        }
        html.append("<title>").append(Html.escape(title)).append("</title>\n")
                .append("<link rel=\"stylesheet\" href=\"").append(Html.escape(site.link("/static/style.css"))).append("\">\n")
                .append("</head>\n<body>\n<header class=\"site-header\"><div class=\"header-inner\"><h1><a href=\"")
                .append(Html.escape(site.link("/"))).append("\">").append(Html.escape(site.title())).append("</a></h1>");
        if (viewer != null) userArea(html, site, viewer);
        html.append("</div></header>\n<main>\n");
    }

    /** The header's user area: name, role and a sign-out button, or a "Sign in" link. */
    private static void userArea(StringBuilder html, Site site, Viewer viewer) {
        html.append("<nav class=\"user-nav\" aria-label=\"Account\">");
        if (viewer.anonymous()) {
            html.append("<a href=\"").append(Html.escape(site.link("/login"))).append("\">Sign in</a>");
        } else {
            html.append("<span class=\"user-name\">").append(Html.escape(viewer.userName())).append("</span>");
            if (viewer.role() != null) {
                html.append(" <span class=\"user-role\">").append(Html.escape(viewer.role())).append("</span>");
            }
            if (viewer.csrfToken() != null) {
                html.append("<form class=\"logout\" method=\"post\" action=\"").append(Html.escape(site.link("/logout")))
                        .append("\"><input type=\"hidden\" name=\"").append(SessionManager.CSRF_FIELD)
                        .append("\" value=\"").append(Html.escape(viewer.csrfToken()))
                        .append("\"><button type=\"submit\">Sign out</button></form>");
            }
        }
        html.append("</nav>");
    }

    /** Closes what {@link #head} opened and adds the script. */
    static void foot(StringBuilder html, Site site) {
        html.append("</main>\n<script src=\"").append(Html.escape(site.link("/static/app.js")))
                .append("\" defer></script>\n</body>\n</html>\n");
    }

    /**
     * The day bar of one service plus its legend: one segment per day, oldest first, each
     * linking to that day on the service's calendar.
     */
    static void bar(StringBuilder html, Site site, String serviceId, String serviceName, List<DaySummary> days,
                    DashboardConfig dashboard) {
        html.append("<div class=\"bar\" role=\"group\" aria-label=\"").append(Html.escape(serviceName))
                .append(": last ").append(days.size()).append(" days\">");
        for (DaySummary day : days) {
            String tip = tooltip(day);
            html.append("<a class=\"seg seg-").append(DayColor.of(day, dashboard).id()).append("\" href=\"")
                    .append(Html.escape(site.link("/service/" + serviceId + "?view=calendar&day=" + day.date())))
                    .append("\" title=\"").append(Html.escape(tip)).append("\" aria-label=\"").append(Html.escape(tip))
                    .append("\"></a>");
        }
        html.append("</div>\n<div class=\"bar-legend\" aria-hidden=\"true\"><span>").append(days.size())
                .append(" days ago</span><span>today</span></div>\n");
    }

    /** Date, uptime, total downtime and number of outages, e.g. {@code 2026-11-13: 99.3% uptime, 10m down, 1 outage}. */
    static String tooltip(DaySummary day) {
        StringBuilder tip = new StringBuilder().append(day.date()).append(": ");
        if (!day.uptime().hasData()) {
            return tip.append("no data").toString();
        }
        tip.append(day.uptime().format()).append(" uptime, ");
        if (day.uptime().downtime().isZero()) {
            tip.append("no downtime");
        } else {
            tip.append(Durations.format(day.uptime().downtime())).append(" down");
        }
        tip.append(", ").append(day.events()).append(day.events() == 1 ? " outage" : " outages");
        if (day.maintenanceEvents() > 0) {
            tip.append(", ").append(day.maintenanceEvents()).append(" planned maintenance");
        }
        return tip.toString();
    }

    /** Lowercase state name, used as CSS class suffix. */
    static String stateId(State state) {
        return state.name().toLowerCase(Locale.ROOT);
    }

    static String stateLabel(State state) {
        return switch (state) {
            case UP -> "Up";
            case DOWN -> "Down";
            case UNKNOWN -> "Not checked yet";
            case MAINTENANCE -> "Maintenance";
        };
    }

    /** Writes a complete, uncached response. */
    static void write(HTTPRequest request, String status, String contentType, String body) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        request.response.setHeader(status);
        request.response.setHeader("Content-Type", contentType);
        request.response.setHeader("Cache-Control", "no-store");
        request.response.setHeader("X-Content-Type-Options", "nosniff");
        request.response.setHeader("Content-Length", Integer.toString(bytes.length));
        request.response.write(bytes);
    }
}
