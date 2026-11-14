package io.siggi.healthchecker.web.page;

import io.siggi.healthchecker.auth.ActionRateLimiter;
import io.siggi.healthchecker.auth.Permission;
import io.siggi.healthchecker.auth.Permissions;
import io.siggi.healthchecker.auth.SessionManager;
import io.siggi.healthchecker.auth.User;
import io.siggi.healthchecker.notify.ChannelStatus;
import io.siggi.healthchecker.notify.Notification;
import io.siggi.healthchecker.notify.NotificationDispatcher;
import io.siggi.healthchecker.web.Html;
import io.siggi.healthchecker.web.WebServer;
import io.siggi.healthchecker.web.site.Site;
import io.siggi.healthchecker.web.site.SiteResolver;
import io.siggi.healthchecker.web.view.Viewer;
import io.siggi.http.HTTPRequest;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Optional;

/**
 * The notification channels and "Send test notification".
 * <ul>
 *   <li>{@code GET /admin/notifications} lists every channel (name, type) with a test button. Delivery
 *       state (last success, last error, pending) is shown only with {@code view.private}.</li>
 *   <li>{@code POST /admin/notify-test} ({@code channel}, {@code csrf}) queues a test message for that
 *       channel right away (no batch window), then answers {@code 303} to the list.</li>
 * </ul>
 * Both need {@code notifications.test}: anonymous visitors are sent to {@code /login}, others get
 * {@code 403}; then CSRF. Tests count against a per-user limit ({@value #TESTS_PER_MINUTE} per minute).
 */
public final class NotificationsPage {
    public static final String PATH = "/admin/notifications";
    public static final String TEST_PATH = "/admin/notify-test";
    public static final int TESTS_PER_MINUTE = 5;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z");

    private final SiteResolver sites;
    private final Permissions permissions;
    private final SessionManager sessions;
    private final NotificationDispatcher dispatcher;
    private final Map<String, String> types;
    private final Clock clock;
    private final ZoneId zone;
    private final ActionRateLimiter limiter;

    /**
     * @param types channel name → type, for display
     * @param zone  the configured timezone, for showing times
     */
    public NotificationsPage(SiteResolver sites, Permissions permissions, SessionManager sessions,
                             NotificationDispatcher dispatcher, Map<String, String> types, Clock clock, ZoneId zone) {
        this.sites = sites;
        this.permissions = permissions;
        this.sessions = sessions;
        this.dispatcher = dispatcher;
        this.types = Map.copyOf(types);
        this.clock = clock;
        this.zone = zone;
        this.limiter = new ActionRateLimiter(TESTS_PER_MINUTE, Duration.ofMinutes(1), clock);
    }

    public void register(WebServer server) {
        server.register(PATH, this::page);
        server.register(TEST_PATH, this::test);
    }

    /** Whether the request's user may send test notifications (for showing the link). */
    public boolean canTest(HTTPRequest request) {
        return permissions.has(request, Permission.NOTIFICATIONS_TEST);
    }

    private void page(HTTPRequest request) throws Exception {
        if (!request.path().equals(PATH)) return; // nothing written → 404
        Optional<Site> found = sites.resolve(request);
        if (found.isEmpty()) return;
        Site site = found.get();
        request.allowedMethods("GET");
        if (!permissions.require(request, site, Permission.NOTIFICATIONS_TEST)) return;
        String sent = request.get().get("sent");
        String notice = sent != null && dispatcher.channels().contains(sent)
                ? "Test notification queued for " + sent + "." : null;
        render(request, site, "200 OK", notice, null);
    }

    private void test(HTTPRequest request) throws Exception {
        if (!request.path().equals(TEST_PATH)) return;
        Optional<Site> found = sites.resolve(request);
        if (found.isEmpty()) return;
        Site site = found.get();
        request.allowedMethods("POST");
        if (!permissions.require(request, site, Permission.NOTIFICATIONS_TEST)) return;
        if (!sessions.requireCsrf(request)) return;
        String channel = request.post().get("channel");
        if (channel == null || !dispatcher.channels().contains(channel)) {
            render(request, site, "400 Bad Request", null, "Unknown notification channel.");
            return;
        }
        User user = permissions.user(request).orElseThrow();
        Optional<Duration> wait = limiter.tryAcquire(user.id());
        if (wait.isPresent()) {
            long seconds = Math.max(1, (wait.get().toMillis() + 999) / 1000);
            request.response.setHeader("Retry-After", Long.toString(seconds));
            render(request, site, "429 Too Many Requests", null,
                    "Too many test notifications. Try again in " + seconds + " s.");
            return;
        }
        String home = site.link("/");
        dispatcher.sendNow(channel, new Notification(Notification.Kind.TEST, null, null, clock.instant(), null, null,
                "Test notification for channel " + channel + ", sent by " + user.displayName() + ".",
                home.startsWith("http") ? home : null));
        request.response.setHeader("303 See Other");
        request.response.setHeader("Location", site.link(PATH + "?sent="
                + java.net.URLEncoder.encode(channel, java.nio.charset.StandardCharsets.UTF_8)));
        request.response.setHeader("Cache-Control", "no-store");
        request.response.setHeader("Content-Length", "0");
        request.response.write(new byte[0]);
    }

    private void render(HTTPRequest request, Site site, String status, String notice, String error) throws Exception {
        Viewer viewer = permissions.viewer(request);
        boolean details = permissions.has(request, Permission.VIEW_PRIVATE);
        String csrf = sessions.csrfToken(request);
        StringBuilder html = new StringBuilder(4096);
        PageSupport.head(html, site, "Notifications · " + site.title(), false, viewer);
        html.append("<p class=\"crumbs\"><a href=\"").append(Html.escape(site.link("/"))).append("\">All services</a></p>\n")
                .append("<h2>Notification channels</h2>\n");
        if (notice != null) {
            html.append("<p class=\"ee-notice\" role=\"status\">").append(Html.escape(notice)).append("</p>\n");
        }
        if (error != null) {
            html.append("<p class=\"form-error\" role=\"alert\">").append(Html.escape(error)).append("</p>\n");
        }
        if (dispatcher.channels().isEmpty()) {
            html.append("<p class=\"muted\">No notification channels are configured.</p>\n");
        } else {
            html.append("<ul class=\"channel-list\">\n");
            for (String name : dispatcher.channels()) {
                html.append("<li class=\"channel\"><strong>").append(Html.escape(name)).append("</strong> <span class=\"muted\">")
                        .append(Html.escape(types.getOrDefault(name, ""))).append("</span>");
                if (details) {
                    dispatcher.status(name).ifPresent(s -> status(html, s));
                }
                html.append("<form method=\"post\" action=\"").append(Html.escape(site.link(TEST_PATH))).append("\">")
                        .append("<input type=\"hidden\" name=\"csrf\" value=\"").append(Html.escape(csrf)).append("\">")
                        .append("<input type=\"hidden\" name=\"channel\" value=\"").append(Html.escape(name)).append("\">")
                        .append("<button type=\"submit\">Send test notification</button></form></li>\n");
            }
            html.append("</ul>\n");
        }
        PageSupport.foot(html, site);
        request.response.setHeader("X-Frame-Options", "DENY");
        request.response.setHeader("Content-Security-Policy", "frame-ancestors 'none'");
        PageSupport.write(request, status, "text/html; charset=utf-8", html.toString());
    }

    private void status(StringBuilder html, ChannelStatus s) {
        html.append("<dl class=\"channel-status\">")
                .append("<dt>Last delivered</dt><dd>").append(Html.escape(time(s.lastSuccess()))).append("</dd>");
        if (s.lastError() != null) {
            boolean current = s.lastSuccess() == null || s.lastErrorTime().isAfter(s.lastSuccess());
            html.append("<dt>Last error</dt><dd>").append(Html.escape(time(s.lastErrorTime()))).append(": ")
                    .append(Html.escape(s.lastError()));
            if (!current) html.append(" <span class=\"muted\">(delivered since)</span>");
            html.append("</dd>");
        }
        html.append("<dt>Pending</dt><dd>").append(s.pending()).append("</dd></dl>");
    }

    private String time(Instant t) {
        return t == null ? "never" : TIME.format(t.atZone(zone));
    }
}
