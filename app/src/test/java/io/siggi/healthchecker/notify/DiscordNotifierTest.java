package io.siggi.healthchecker.notify;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link DiscordNotifier} against an in-process fake webhook. */
class DiscordNotifierTest {
    private static final Instant T0 = Instant.parse("2026-01-01T12:00:00Z");
    private static final String ROLE = "<@&123456789>";

    private final FakeWebhook webhook;
    private final HttpClient client = DiscordNotifier.httpClient();

    DiscordNotifierTest() throws Exception {
        webhook = new FakeWebhook();
    }

    @AfterEach
    void stop() {
        webhook.close();
    }

    private DiscordNotifier notifier(String username, String mention) {
        return new DiscordNotifier(new NotificationConfig.Discord(webhook.url(), username, mention), client);
    }

    private static Notification down(String name, Instant start) {
        return new Notification(Notification.Kind.DOWN, name.toLowerCase(), name, start.plusSeconds(60), start,
                "status 503, expected at most 2xx", null, "https://status.example.com/service/x/event/e1");
    }

    private static Notification reminder(String name) {
        return new Notification(Notification.Kind.REMINDER, name.toLowerCase(), name, T0.plus(Duration.ofMinutes(60)),
                T0, "timeout after 5000 ms", null, null);
    }

    private static Notification recovered(String name) {
        return new Notification(Notification.Kind.RECOVERED, name.toLowerCase(), name,
                T0.plus(Duration.ofMinutes(134)), T0, null, null, "https://status.example.com/service/x/event/e1");
    }

    private static NotificationBatch batch(Notification... notifications) {
        return new NotificationBatch(UUID.randomUUID(), "discord", List.of(notifications));
    }

    private static JsonArray embeds(FakeWebhook.Post post) {
        return post.json().getAsJsonArray("embeds");
    }

    private static JsonObject embed(FakeWebhook.Post post, int i) {
        return embeds(post).get(i).getAsJsonObject();
    }

    @Test
    void payloadShape() throws Exception {
        notifier("Status bot", ROLE).send(batch(down("Website", T0), reminder("Admin"), recovered("Shop")));

        List<FakeWebhook.Post> posts = webhook.posts();
        assertEquals(1, posts.size());
        FakeWebhook.Post post = posts.getFirst();
        assertTrue(post.contentType().startsWith("application/json"));
        assertEquals("HealthChecker", post.userAgent());
        JsonObject json = post.json();
        assertEquals(ROLE, json.get("content").getAsString());
        assertEquals("Status bot", json.get("username").getAsString());
        assertEquals(JsonParser.parseString("{\"parse\":[],\"roles\":[\"123456789\"]}"), json.get("allowed_mentions"));
        assertEquals(3, embeds(post).size());

        JsonObject d = embed(post, 0);
        assertEquals("Website is down", d.get("title").getAsString());
        assertEquals(DiscordNotifier.RED, d.get("color").getAsInt());
        assertEquals(T0.plusSeconds(60).toString(), d.get("timestamp").getAsString());
        assertEquals("https://status.example.com/service/x/event/e1", d.get("url").getAsString());
        String desc = d.get("description").getAsString();
        assertTrue(desc.contains("Down since <t:" + T0.getEpochSecond() + ":f>"), desc);
        assertTrue(desc.contains("Latest failure: status 503, expected at most 2xx"), desc);

        JsonObject r = embed(post, 1);
        assertEquals("Admin is still down", r.get("title").getAsString());
        assertEquals(DiscordNotifier.ORANGE, r.get("color").getAsInt());
        assertTrue(r.get("description").getAsString().startsWith("Down for 1h, since <t:"));
        assertFalse(r.has("url"), "no link without a public base URL");

        JsonObject g = embed(post, 2);
        assertEquals("Shop recovered", g.get("title").getAsString());
        assertEquals(DiscordNotifier.GREEN, g.get("color").getAsInt());
        assertTrue(g.get("description").getAsString().startsWith("Back up after 2h 14m"));
    }

    @Test
    void namesAndMessagesAreEscapedAndCannotPing() throws Exception {
        Notification n = new Notification(Notification.Kind.DOWN, "x", "*Big* @everyone", T0, T0,
                "<@&999> [click](https://evil.example)", null, "/service/x");
        notifier(null, null).send(batch(n));

        JsonObject json = webhook.posts().getFirst().json();
        assertFalse(json.has("content"));
        assertFalse(json.has("username"));
        assertEquals(JsonParser.parseString("{\"parse\":[]}"), json.get("allowed_mentions"));
        JsonObject e = json.getAsJsonArray("embeds").get(0).getAsJsonObject();
        assertEquals("\\*Big\\* \\@everyone is down", e.get("title").getAsString());
        assertTrue(e.get("description").getAsString().contains("\\<\\@&999\\> \\[click\\]\\(https\\://evil.example\\)"));
        assertFalse(e.has("url"), "relative links are left out");
    }

    @Test
    void moreThanTenEmbedsAreSplit() throws Exception {
        List<Notification> many = new ArrayList<>();
        for (int i = 0; i < 23; i++) many.add(down("Service " + i, T0));
        notifier(null, ROLE).send(new NotificationBatch(UUID.randomUUID(), "discord", many));

        List<FakeWebhook.Post> posts = webhook.posts();
        assertEquals(3, posts.size());
        assertEquals(List.of(10, 10, 3), posts.stream().map(p -> embeds(p).size()).toList());
        assertEquals("Service 0 is down", embed(posts.get(0), 0).get("title").getAsString());
        assertEquals("Service 22 is down", embed(posts.get(2), 2).get("title").getAsString());
        // the mention pings once per batch, on the first message
        assertEquals(ROLE, posts.get(0).json().get("content").getAsString());
        assertFalse(posts.get(1).json().has("content"));
        assertFalse(posts.get(2).json().has("content"));
        for (FakeWebhook.Post p : posts.subList(1, 3)) {
            assertEquals(JsonParser.parseString("{\"parse\":[]}"), p.json().get("allowed_mentions"));
        }
    }

    @Test
    void splitAlsoByMessageSize() {
        List<Notification> big = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            big.add(new Notification(Notification.Kind.DOWN, "s" + i, "S" + i, T0, T0, "x".repeat(2000), null, null));
        }
        List<JsonObject> parts = DiscordNotifier.payloads(new NotificationBatch(UUID.randomUUID(), "d", big), null, null);
        assertTrue(parts.size() > 1);
        for (JsonObject part : parts) {
            int chars = 0;
            for (var e : part.getAsJsonArray("embeds")) {
                JsonObject o = e.getAsJsonObject();
                chars += o.get("title").getAsString().length() + o.get("description").getAsString().length();
            }
            assertTrue(chars <= DiscordNotifier.MAX_MESSAGE_CHARS, "message has " + chars + " characters");
        }
        assertEquals(8, parts.stream().mapToInt(p -> p.getAsJsonArray("embeds").size()).sum());
    }

    @Test
    void mentionOnlyOnDownAndReminder() throws Exception {
        DiscordNotifier n = notifier(null, ROLE);
        n.send(batch(recovered("Website")));
        n.send(batch(reminder("Website")));
        n.send(batch(down("Website", T0)));
        Notification test = new Notification(Notification.Kind.TEST, null, null, T0, null, null, "hello", null);
        n.send(batch(test));

        List<FakeWebhook.Post> posts = webhook.posts();
        assertFalse(posts.get(0).json().has("content"), "recovery doesn't ping");
        assertEquals(JsonParser.parseString("{\"parse\":[]}"), posts.get(0).json().get("allowed_mentions"));
        assertEquals(ROLE, posts.get(1).json().get("content").getAsString());
        assertEquals(ROLE, posts.get(2).json().get("content").getAsString());
        assertFalse(posts.get(3).json().has("content"), "a test doesn't ping");
        JsonObject t = embed(posts.get(3), 0);
        assertEquals("Test notification", t.get("title").getAsString());
        assertEquals(DiscordNotifier.GREY, t.get("color").getAsInt());
    }

    @Test
    void mentionGoesToTheFirstMessageWithADownEmbed() throws Exception {
        List<Notification> list = new ArrayList<>();
        for (int i = 0; i < 10; i++) list.add(recovered("R" + i));
        list.add(down("Website", T0));
        notifier(null, "<@42> @here").send(new NotificationBatch(UUID.randomUUID(), "discord", list));

        List<FakeWebhook.Post> posts = webhook.posts();
        assertEquals(2, posts.size());
        assertFalse(posts.get(0).json().has("content"));
        assertEquals("<@42> @here", posts.get(1).json().get("content").getAsString());
        assertEquals(JsonParser.parseString("{\"parse\":[\"everyone\"],\"users\":[\"42\"]}"),
                posts.get(1).json().get("allowed_mentions"));
    }

    @Test
    void rateLimitedThenSuccess() throws Exception {
        webhook.respond(429, "{\"message\":\"You are being rate limited.\",\"retry_after\":1.5,\"global\":false}");
        DiscordNotifier n = notifier(null, null);
        NotificationBatch b = batch(down("Website", T0));

        NotifierException e = assertThrows(NotifierException.class, () -> n.send(b));
        assertFalse(e.permanent());
        assertEquals(Duration.ofMillis(1500), e.retryAfter());
        assertTrue(e.getMessage().contains("429"));

        n.send(b);
        assertEquals(2, webhook.posts().size());
        assertEquals(webhook.posts().get(0).json(), webhook.posts().get(1).json(), "the retry sends the same message");
    }

    @Test
    void rateLimitFromHeaderWhenBodyHasNone() throws Exception {
        webhook.respond(429, "", Map.of("Retry-After", "3"));
        NotifierException e = assertThrows(NotifierException.class,
                () -> notifier(null, null).send(batch(down("Website", T0))));
        assertEquals(Duration.ofSeconds(3), e.retryAfter());
    }

    @Test
    void rateLimitThroughTheDispatcherWaitsRetryAfterThenSucceeds() throws Exception {
        ManualScheduler scheduler = new ManualScheduler(T0);
        webhook.respond(429, "{\"retry_after\":45}");
        List<String> log = new ArrayList<>();
        NotificationDispatcher dispatcher = new NotificationDispatcher(Map.of("discord", notifier(null, null)),
                Duration.ZERO, RetryPolicy.DEFAULT, scheduler, scheduler, new NotificationDispatcherTest.CapturingLogger(log));
        dispatcher.sendNow("discord", down("Website", T0));
        scheduler.runDue();
        assertEquals(1, webhook.posts().size());
        assertEquals(1, dispatcher.status("discord").orElseThrow().pending());

        scheduler.advance(Duration.ofSeconds(44)); // longer than the 10 s backoff, shorter than retry_after
        assertEquals(1, webhook.posts().size());
        scheduler.advance(Duration.ofSeconds(1));
        assertEquals(2, webhook.posts().size());
        ChannelStatus status = dispatcher.status("discord").orElseThrow();
        assertEquals(0, status.pending());
        assertEquals(T0.plusSeconds(45), status.lastSuccess());
    }

    @Test
    void retryOfASplitBatchContinuesWithTheFailedPart() throws Exception {
        List<Notification> many = new ArrayList<>();
        for (int i = 0; i < 25; i++) many.add(down("Service " + i, T0));
        NotificationBatch b = new NotificationBatch(UUID.randomUUID(), "discord", many);
        webhook.respond(204, "");
        webhook.respond(429, "{\"retry_after\":0.5}");
        DiscordNotifier n = notifier(null, ROLE);

        NotifierException e = assertThrows(NotifierException.class, () -> n.send(b));
        assertTrue(e.getMessage().contains("message 2 of 3"), e.getMessage());
        n.send(b);

        List<FakeWebhook.Post> posts = webhook.posts();
        assertEquals(4, posts.size());
        assertEquals("Service 0 is down", embed(posts.get(0), 0).get("title").getAsString());
        assertEquals("Service 10 is down", embed(posts.get(1), 0).get("title").getAsString());
        assertEquals("Service 10 is down", embed(posts.get(2), 0).get("title").getAsString());
        assertEquals("Service 20 is down", embed(posts.get(3), 0).get("title").getAsString());

        // a new batch starts from its first part again
        n.send(batch(down("Website", T0)));
        assertEquals(5, webhook.posts().size());
    }

    @Test
    void otherClientErrorsArePermanent() {
        webhook.respond(404, "{\"message\":\"Unknown Webhook\",\"code\":10015}");
        NotifierException e = assertThrows(NotifierException.class,
                () -> notifier(null, null).send(batch(down("Website", T0))));
        assertTrue(e.permanent());
        assertNull(e.retryAfter());
        assertTrue(e.getMessage().contains("404"), e.getMessage());
        assertTrue(e.getMessage().contains("Unknown Webhook"), e.getMessage());
        assertFalse(e.getMessage().contains("secret-token"), "never names the webhook URL");

        webhook.respond(400, "{\"message\":\"Invalid Form Body\",\"code\":50035}");
        assertTrue(assertThrows(NotifierException.class,
                () -> notifier(null, null).send(batch(down("Website", T0)))).permanent());
    }

    @Test
    void permanentFailureThroughTheDispatcherIsNotRetried() {
        ManualScheduler scheduler = new ManualScheduler(T0);
        webhook.respond(401, "{\"message\":\"Invalid Webhook Token\",\"code\":50027}");
        NotificationDispatcher dispatcher = new NotificationDispatcher(Map.of("discord", notifier(null, null)),
                Duration.ZERO, RetryPolicy.DEFAULT, scheduler, scheduler,
                new NotificationDispatcherTest.CapturingLogger(new ArrayList<>()));
        dispatcher.sendNow("discord", down("Website", T0));
        scheduler.advance(Duration.ofHours(1));
        assertEquals(1, webhook.posts().size());
        ChannelStatus status = dispatcher.status("discord").orElseThrow();
        assertEquals(0, status.pending());
        assertTrue(status.lastError().contains("401"));
    }

    @Test
    void serverErrorsAndConnectionFailuresAreTemporary() throws Exception {
        webhook.respond(502, "");
        NotifierException e = assertThrows(NotifierException.class,
                () -> notifier(null, null).send(batch(down("Website", T0))));
        assertFalse(e.permanent());
        assertTrue(e.getMessage().contains("502"));

        java.net.URI dead;
        try (java.net.ServerSocket s = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            dead = java.net.URI.create("http://127.0.0.1:" + s.getLocalPort() + "/api/webhooks/1/secret-token");
        }
        DiscordNotifier offline = new DiscordNotifier(new NotificationConfig.Discord(dead, null, null), client);
        NotifierException c = assertThrows(NotifierException.class, () -> offline.send(batch(down("Website", T0))));
        assertFalse(c.permanent());
        assertFalse(c.getMessage().contains("secret-token"));
    }
}
