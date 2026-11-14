package io.siggi.healthchecker.web.page;

import io.siggi.healthchecker.auth.Permission;
import io.siggi.healthchecker.auth.Permissions;
import io.siggi.healthchecker.auth.Role;
import io.siggi.healthchecker.auth.SessionManager;
import io.siggi.healthchecker.auth.User;
import io.siggi.healthchecker.auth.UserDirectory;
import io.siggi.healthchecker.config.ServerConfig;
import io.siggi.healthchecker.config.UnknownHostsPolicy;
import io.siggi.healthchecker.notify.Notification;
import io.siggi.healthchecker.notify.NotificationBatch;
import io.siggi.healthchecker.notify.NotificationDispatcher;
import io.siggi.healthchecker.notify.Notifier;
import io.siggi.healthchecker.notify.NotifierException;
import io.siggi.healthchecker.web.WebServer;
import io.siggi.healthchecker.web.site.SiteResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** "Send test notification": {@code /admin/notifications} and {@code POST /admin/notify-test}, over HTTP. */
class NotificationsPageTest {
    /** May send tests, but not see private data such as channel errors. */
    private static final Role TESTER = new Role("tester", EnumSet.of(Permission.NOTIFICATIONS_TEST));

    @TempDir
    Path dataDir;

    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final BlockingQueue<NotificationBatch> discord = new LinkedBlockingQueue<>();
    private WebServer web;
    private NotificationDispatcher dispatcher;

    @BeforeEach
    void start() throws Exception {
        Map<String, Notifier> notifiers = new LinkedHashMap<>();
        notifiers.put("discord", discord::add);
        notifiers.put("broken", batch -> {
            throw NotifierException.permanent("Discord rejected the message with 404 (10015 Unknown Webhook)");
        });
        // a long batch window: tests must not wait for it
        dispatcher = NotificationDispatcher.create(notifiers, Duration.ofHours(1));
        UserDirectory users = UserDirectory.of(List.of(
                new User("root", "Root", Role.ADMIN, null, List.of(), false),
                new User("vic", "Vic", Role.VIEWER, null, List.of(), false),
                new User("tess", "Tess", TESTER, null, List.of(), false)));
        web = new WebServer(new ServerConfig(0, "127.0.0.1", null, List.of(), UnknownHostsPolicy.DEFAULT));
        SessionManager sessions = new SessionManager(dataDir.resolve("sessions"), Duration.ofDays(7), web.trustedProxies());
        Permissions permissions = new Permissions(sessions, users::user);
        SiteResolver sites = new SiteResolver(List.of(), "Status", null, UnknownHostsPolicy.DEFAULT, web.trustedProxies());
        web.register("/test-login", request -> {
            sessions.login(request, request.get().get("user"));
            PageSupport.write(request, "200 OK", "text/plain; charset=utf-8", "ok");
        });
        web.register("/test-csrf", request -> PageSupport.write(request, "200 OK", "text/plain; charset=utf-8",
                sessions.csrfToken(request)));
        new NotificationsPage(sites, permissions, sessions, dispatcher,
                Map.of("discord", "discord", "broken", "discord"), Clock.systemUTC(), ZoneOffset.UTC).register(web);
        web.start();
    }

    @AfterEach
    void stop() {
        if (web != null) web.close();
        if (dispatcher != null) dispatcher.close();
    }

    record Session(String cookie, String csrf) {
    }

    private Session login(String user) throws Exception {
        HttpResponse<String> r = get("/test-login?user=" + user, null);
        String cookie = null;
        for (String c : r.headers().allValues("Set-Cookie")) {
            if (c.startsWith("SessID=")) cookie = c.substring(0, c.indexOf(';'));
        }
        assertNotNull(cookie);
        return new Session(cookie, get("/test-csrf", new Session(cookie, null)).body());
    }

    private HttpResponse<String> get(String path, Session session) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + web.port() + path))
                .timeout(Duration.ofSeconds(10));
        if (session != null) b.header("Cookie", session.cookie());
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(Session session, boolean withCsrf, String channel) throws Exception {
        StringBuilder body = new StringBuilder("channel=" + URLEncoder.encode(channel, StandardCharsets.UTF_8));
        if (session != null && withCsrf) body.append("&csrf=").append(session.csrf());
        HttpRequest.Builder b = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + web.port() + NotificationsPage.TEST_PATH))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        if (session != null) b.header("Cookie", session.cookie());
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void testButtonRequiresPermission() throws Exception {
        HttpResponse<String> anonymous = post(null, false, "discord");
        assertEquals(303, anonymous.statusCode());
        assertTrue(anonymous.headers().firstValue("Location").orElseThrow().startsWith("/login"));
        HttpResponse<String> anonymousPage = get(NotificationsPage.PATH, null);
        assertEquals(303, anonymousPage.statusCode());
        assertTrue(anonymousPage.headers().firstValue("Location").orElseThrow().startsWith("/login"));

        Session vic = login("vic");
        assertEquals(403, get(NotificationsPage.PATH, vic).statusCode());
        assertEquals(403, post(vic, true, "discord").statusCode());

        Session root = login("root");
        assertEquals(403, post(root, false, "discord").statusCode(), "CSRF token required");
        assertNull(discord.poll(300, TimeUnit.MILLISECONDS), "nothing was sent");
    }

    @Test
    void sendsATestNotificationRightAway() throws Exception {
        Session tess = login("tess");
        HttpResponse<String> page = get(NotificationsPage.PATH, tess);
        assertEquals(200, page.statusCode());
        assertTrue(page.body().contains("Send test notification"));
        assertTrue(page.body().contains("<strong>discord</strong>"));

        HttpResponse<String> r = post(tess, true, "discord");
        assertEquals(303, r.statusCode(), r.body());
        assertEquals(NotificationsPage.PATH + "?sent=discord", r.headers().firstValue("Location").orElseThrow());

        NotificationBatch batch = discord.poll(5, TimeUnit.SECONDS);
        assertNotNull(batch, "sent without waiting for the batch window");
        assertEquals(1, batch.notifications().size());
        Notification n = batch.notifications().getFirst();
        assertEquals(Notification.Kind.TEST, n.kind());
        assertTrue(n.text().contains("Tess"));

        assertTrue(get(NotificationsPage.PATH + "?sent=discord", tess).body().contains("Test notification queued for discord."));
    }

    @Test
    void unknownChannelIsBadRequest() throws Exception {
        assertEquals(400, post(login("root"), true, "nope").statusCode());
    }

    @Test
    void channelErrorsNeedViewPrivate() throws Exception {
        Session root = login("root");
        assertEquals(303, post(root, true, "broken").statusCode());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (dispatcher.status("broken").orElseThrow().lastError() == null && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(get(NotificationsPage.PATH, root).body().contains("10015 Unknown Webhook"));
        String tester = get(NotificationsPage.PATH, login("tess")).body();
        assertFalse(tester.contains("Unknown Webhook"), "no view.private, no channel errors");
        assertTrue(tester.contains("<strong>broken</strong>"));
    }

    @Test
    void testsAreRateLimitedPerUser() throws Exception {
        Session root = login("root");
        for (int i = 0; i < NotificationsPage.TESTS_PER_MINUTE; i++) {
            assertEquals(303, post(root, true, "discord").statusCode());
        }
        HttpResponse<String> limited = post(root, true, "discord");
        assertEquals(429, limited.statusCode());
        assertTrue(limited.headers().firstValue("Retry-After").isPresent());
        assertEquals(303, post(login("tess"), true, "discord").statusCode(), "other users aren't affected");
    }
}
