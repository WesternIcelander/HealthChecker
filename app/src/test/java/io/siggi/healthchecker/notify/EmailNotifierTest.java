package io.siggi.healthchecker.notify;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link EmailNotifier} against an in-process fake SimpleMailer API. */
class EmailNotifierTest {
    private static final Instant T0 = Instant.parse("2026-01-01T12:00:00Z");
    private static final String KEY = "secret-api-key";
    private static final String LINK = "https://status.example.com/service/website/event/e1";

    private final FakeSimpleMailer mailer;
    private final HttpClient client = DiscordNotifier.httpClient();
    private final List<String> logLines = new ArrayList<>();

    EmailNotifierTest() throws Exception {
        mailer = new FakeSimpleMailer();
    }

    @AfterEach
    void stop() {
        mailer.close();
    }

    private NotificationConfig.Email config(boolean requireTls) {
        return new NotificationConfig.Email(mailer.baseUrl(), KEY, "HealthChecker <health@example.com>",
                List.of("ops@example.com", "oncall@example.com"), requireTls);
    }

    private EmailNotifier notifier(ZoneId zone) {
        return new EmailNotifier(config(false), client, zone, new NotificationDispatcherTest.CapturingLogger(logLines));
    }

    private EmailNotifier notifier() {
        return notifier(ZoneId.of("UTC"));
    }

    private static Notification down(String name, String message, String link) {
        return new Notification(Notification.Kind.DOWN, "website", name, T0.plusSeconds(60), T0, message, null, link);
    }

    private static Notification reminder(String name) {
        return new Notification(Notification.Kind.REMINDER, "admin", name, T0.plus(Duration.ofMinutes(60)), T0,
                "timeout after 5000 ms", null, null);
    }

    private static Notification recovered(String name) {
        return new Notification(Notification.Kind.RECOVERED, "shop", name, T0.plus(Duration.ofMinutes(134)), T0,
                null, null, LINK);
    }

    private static NotificationBatch batch(Notification... notifications) {
        return new NotificationBatch(UUID.randomUUID(), "mail", List.of(notifications));
    }

    private static String str(JsonObject o, String key) {
        return o.get(key).getAsString();
    }

    @Test
    void accepted202WithPayloadShape() throws Exception {
        NotificationBatch batch = batch(down("Website", "status 503, expected at most 2xx", LINK));
        notifier().send(batch);

        List<FakeSimpleMailer.Request> requests = mailer.requests();
        assertEquals(1, requests.size());
        FakeSimpleMailer.Request r = requests.getFirst();
        assertEquals("POST", r.method());
        assertEquals("/mail/v1/messages", r.path());
        assertEquals("Bearer " + KEY, r.authorization());
        assertTrue(r.contentType().startsWith("application/json"));
        assertEquals("HealthChecker", r.userAgent());

        JsonObject json = r.json();
        assertEquals(Set.of("id", "from", "to", "subject", "text", "html", "requireTls"), json.keySet());
        assertEquals(batch.id().toString(), str(json, "id"));
        UUID.fromString(str(json, "id"));
        assertEquals("HealthChecker <health@example.com>", str(json, "from"));
        assertEquals(2, json.getAsJsonArray("to").size());
        assertEquals("ops@example.com", json.getAsJsonArray("to").get(0).getAsString());
        assertEquals("oncall@example.com", json.getAsJsonArray("to").get(1).getAsString());
        assertFalse(json.get("requireTls").getAsBoolean());
        assertEquals("[HealthChecker] Website down", str(json, "subject"));

        String text = str(json, "text");
        assertTrue(text.startsWith("Website is down\n"), text);
        assertTrue(text.contains("Down since Thu 1 Jan 2026 12:00 (UTC)\n"), text);
        assertTrue(text.contains("Latest failure: status 503, expected at most 2xx\n"), text);
        assertTrue(text.contains("Details: " + LINK + "\n"), text);

        String html = str(json, "html");
        assertTrue(html.startsWith("<!DOCTYPE html>"), html);
        assertTrue(html.contains("<h2 style=\"margin:0 0 6px;font-size:18px\">Website is down</h2>"), html);
        assertTrue(html.contains("Down since Thu 1 Jan 2026 12:00 (UTC)<br>"), html);
        assertTrue(html.contains("<a href=\"" + LINK + "\">Details</a>"), html);
        assertTrue(html.contains("#d32f2f"), html);
    }

    @Test
    void requireTlsIsPassedOn() throws Exception {
        new EmailNotifier(config(true), client, ZoneId.of("UTC")).send(batch(reminder("Admin")));
        assertTrue(mailer.requests().getFirst().json().get("requireTls").getAsBoolean());
    }

    @Test
    void subjectSummarisesABatch() throws Exception {
        notifier().send(batch(down("Website", "refused", null), reminder("Admin"), down("Shop", "refused", null),
                recovered("Blog")));
        JsonObject json = mailer.requests().getFirst().json();
        assertEquals("[HealthChecker] 2 down, 1 still down, 1 recovered", str(json, "subject"));
        String text = str(json, "text");
        assertTrue(text.indexOf("Website is down") < text.indexOf("Admin is still down"));
        assertTrue(text.indexOf("Admin is still down") < text.indexOf("Shop is down"));
        assertTrue(text.contains("Down for 1h, since Thu 1 Jan 2026 12:00 (UTC)"), text);
        assertTrue(text.contains("Blog recovered\nBack up after 2h 14m (down since Thu 1 Jan 2026 12:00 (UTC))"), text);

        assertEquals("[HealthChecker] Admin still down (1h)", EmailNotifier.subject(List.of(reminder("Admin"))));
        assertEquals("[HealthChecker] Blog recovered after 2h 14m", EmailNotifier.subject(List.of(recovered("Blog"))));
        Notification test = new Notification(Notification.Kind.TEST, null, null, T0, null, null, "hi", null);
        Notification announcement = new Notification(Notification.Kind.ANNOUNCEMENT, null,
                "Maintenance started: Upgrade", T0, null, null, "line 1\nline 2", null);
        assertEquals("[HealthChecker] Test notification", EmailNotifier.subject(List.of(test)));
        assertEquals("[HealthChecker] Maintenance started: Upgrade", EmailNotifier.subject(List.of(announcement)));
        assertEquals("[HealthChecker] 1 down, 1 test notification, 2 announcements",
                EmailNotifier.subject(List.of(announcement, down("Website", null, null), test, announcement)));
    }

    @Test
    void timesUseTheConfiguredTimezone() throws Exception {
        notifier(ZoneId.of("America/New_York")).send(batch(down("Website", null, null)));
        String text = str(mailer.requests().getFirst().json(), "text");
        assertTrue(text.contains("Down since Thu 1 Jan 2026 07:00 (America/New_York)"), text);
    }

    @Test
    void ok200IsAcceptedToo() throws Exception {
        mailer.respond(200, "{\"status\":\"delivered\"}");
        notifier().send(batch(down("Website", null, null)));
        assertEquals(1, mailer.requests().size());
    }

    @Test
    void retryAfter503ReusesTheSameId() {
        mailer.respond(503, "");
        ManualScheduler scheduler = new ManualScheduler(T0);
        RetryPolicy retry = new RetryPolicy(Duration.ofSeconds(10), Duration.ofSeconds(60), 5);
        try (NotificationDispatcher d = new NotificationDispatcher(Map.of("mail", notifier()), Duration.ofSeconds(30),
                retry, scheduler, scheduler, new NotificationDispatcherTest.CapturingLogger(logLines))) {
            d.send(List.of("mail"), down("Website", "refused", LINK));
            scheduler.advance(Duration.ofSeconds(30));
            assertEquals(1, mailer.requests().size());
            ChannelStatus status = d.status("mail").orElseThrow();
            assertEquals("SimpleMailer answered 503", status.lastError());
            assertEquals(1, status.pending());
            assertNull(status.lastSuccess());

            scheduler.advance(Duration.ofSeconds(10));
            List<FakeSimpleMailer.Request> requests = mailer.requests();
            assertEquals(2, requests.size());
            assertEquals(str(requests.get(0).json(), "id"), str(requests.get(1).json(), "id"));
            // identical payload, so SimpleMailer's idempotency accepts the repeat instead of answering 409
            assertEquals(requests.get(0).body(), requests.get(1).body());
            status = d.status("mail").orElseThrow();
            assertEquals(0, status.pending());
            assertEquals(T0.plusSeconds(40), status.lastSuccess());
        }
    }

    @Test
    void serverErrorsTimeoutsAndRateLimitsAreTemporary() throws Exception {
        mailer.respond(500, "{\"error\":\"internal\",\"message\":\"disk full\"}");
        NotifierException e = assertThrows(NotifierException.class, () -> notifier().send(batch(reminder("A"))));
        assertFalse(e.permanent());
        assertEquals("SimpleMailer answered 500 (internal: disk full)", e.getMessage());

        mailer.respond(429, "", Map.of("Retry-After", "42"));
        e = assertThrows(NotifierException.class, () -> notifier().send(batch(reminder("A"))));
        assertFalse(e.permanent());
        assertEquals(Duration.ofSeconds(42), e.retryAfter());

        URI base = mailer.baseUrl();
        mailer.close();
        EmailNotifier unreachable = new EmailNotifier(new NotificationConfig.Email(base, KEY, "a@example.com",
                List.of("b@example.com"), false), client, ZoneId.of("UTC"));
        e = assertThrows(NotifierException.class, () -> unreachable.send(batch(reminder("A"))));
        assertFalse(e.permanent());
        assertTrue(e.getMessage().startsWith("couldn't reach SimpleMailer"), e.getMessage());
    }

    @Test
    void unauthorized401IsPermanent() {
        mailer.respond(401, "{\"error\":\"unauthorized\",\"message\":\"missing or invalid API key\"}");
        NotifierException e = assertThrows(NotifierException.class, () -> notifier().send(batch(reminder("A"))));
        assertTrue(e.permanent());
        assertEquals("SimpleMailer rejected the API key (401) (unauthorized: missing or invalid API key)",
                e.getMessage());
        assertFalse(e.getMessage().contains(KEY));
    }

    @Test
    void badRequest400IsPermanentWithTheValidationErrors() {
        mailer.respond(400, "{\"error\":\"validation_failed\",\"message\":\"invalid message\","
                + "\"errors\":[\"to[0]: invalid address\"]}");
        NotifierException e = assertThrows(NotifierException.class, () -> notifier().send(batch(reminder("A"))));
        assertTrue(e.permanent());
        assertEquals("SimpleMailer rejected the message with 400 (validation_failed: invalid message;"
                + " to[0]: invalid address)", e.getMessage());
    }

    @Test
    void permanentFailuresAreNotRetriedByTheDispatcher() {
        mailer.respond(401, "");
        ManualScheduler scheduler = new ManualScheduler(T0);
        try (NotificationDispatcher d = new NotificationDispatcher(Map.of("mail", notifier()), Duration.ZERO,
                RetryPolicy.DEFAULT, scheduler, scheduler, new NotificationDispatcherTest.CapturingLogger(logLines))) {
            d.send(List.of("mail"), reminder("A"));
            scheduler.advance(Duration.ofHours(2));
            assertEquals(1, mailer.requests().size());
            assertEquals(0, d.status("mail").orElseThrow().pending());
        }
    }

    @Test
    void conflict409IsLoggedAsABugAndNotRetried() {
        mailer.respond(409, "{\"error\":\"conflict\",\"message\":\"id reused with a different payload\"}");
        NotificationBatch batch = batch(reminder("A"));
        NotifierException e = assertThrows(NotifierException.class, () -> notifier().send(batch));
        assertTrue(e.permanent());
        assertTrue(e.getMessage().contains("409") && e.getMessage().contains(batch.id().toString()), e.getMessage());
        assertTrue(logLines.stream().anyMatch(l -> l.startsWith("ERROR") && l.contains("bug")), logLines.toString());
    }

    @Test
    void htmlEscapesEndpointNamesAndMessages() throws Exception {
        String name = "<script>alert(\"x\")</script> & Co's";
        String message = "<b>bold</b> & <img src=x onerror=alert(1)>";
        String link = "https://status.example.com/service/a?x=1&y=\"><script>";
        Notification announcement = new Notification(Notification.Kind.ANNOUNCEMENT, null, "<i>Maintenance</i>",
                T0, null, null, "Affects: <u>Shop</u>\nline 2", null);
        notifier().send(batch(down(name, message, link), announcement));

        JsonObject json = mailer.requests().getFirst().json();
        String html = str(json, "html");
        assertFalse(html.contains("<script>"), html);
        assertFalse(html.contains("<b>"), html);
        assertFalse(html.contains("<img"), html);
        assertFalse(html.contains("<i>"), html);
        assertFalse(html.contains("<u>"), html);
        assertTrue(html.contains("&lt;script&gt;alert(&quot;x&quot;)&lt;/script&gt; &amp; Co&#39;s is down"), html);
        assertTrue(html.contains("Latest failure: &lt;b&gt;bold&lt;/b&gt; &amp; &lt;img src=x onerror=alert(1)&gt;"),
                html);
        assertTrue(html.contains("href=\"https://status.example.com/service/a?x=1&amp;y=&quot;&gt;&lt;script&gt;\""),
                html);
        assertTrue(html.contains("&lt;i&gt;Maintenance&lt;/i&gt;"), html);
        assertTrue(html.contains("Affects: &lt;u&gt;Shop&lt;/u&gt;<br>\nline 2"), html);

        // the text body is plain text: shown as written
        String text = str(json, "text");
        assertTrue(text.contains(name + " is down\n"), text);
        assertTrue(text.contains("Latest failure: " + message + "\n"), text);
    }

    @Test
    void controlCharactersCantBreakTheSubject() {
        Notification n = new Notification(Notification.Kind.DOWN, "x", "Web\r\nBcc: evil@example.com", T0, T0,
                null, null, null);
        String subject = EmailNotifier.subject(List.of(n));
        assertEquals("[HealthChecker] Web Bcc: evil@example.com down", subject);
    }

    @Test
    void messagesUrlHandlesTrailingSlashes() {
        assertEquals(URI.create("https://mail.example.com/v1/messages"),
                EmailNotifier.messagesUrl(URI.create("https://mail.example.com")));
        assertEquals(URI.create("https://mail.example.com/api/v1/messages"),
                EmailNotifier.messagesUrl(URI.create("https://mail.example.com/api/")));
    }
}
