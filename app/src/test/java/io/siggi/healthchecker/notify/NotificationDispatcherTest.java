package io.siggi.healthchecker.notify;

import io.siggi.healthchecker.api.CheckResult;
import io.siggi.healthchecker.config.Config;
import io.siggi.healthchecker.config.ConfigLoader;
import io.siggi.healthchecker.config.EndpointDefinition;
import io.siggi.healthchecker.status.CheckRecord;
import io.siggi.healthchecker.status.State;
import io.siggi.healthchecker.status.StateChange;
import org.junit.jupiter.api.Test;

import java.lang.System.Logger;
import java.text.MessageFormat;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NotificationDispatcherTest {
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final Duration WINDOW = Duration.ofSeconds(30);
    private static final RetryPolicy RETRY = new RetryPolicy(Duration.ofSeconds(10), Duration.ofSeconds(60), 5);

    private final ManualScheduler scheduler = new ManualScheduler(T0);
    private final List<String> logLines = new ArrayList<>();

    /** A notifier that records every attempt and fails with the queued outcomes first. */
    static final class FakeNotifier implements Notifier {
        record Attempt(Instant time, NotificationBatch batch) {
        }

        final ManualScheduler clock;
        final List<Attempt> attempts = new ArrayList<>();
        final Deque<Object> outcomes = new ArrayDeque<>();

        FakeNotifier(ManualScheduler clock) {
            this.clock = clock;
        }

        @Override
        public void send(NotificationBatch batch) throws NotifierException {
            attempts.add(new Attempt(clock.instant(), batch));
            Object outcome = outcomes.poll();
            if (outcome instanceof NotifierException e) throw e;
            if (outcome instanceof RuntimeException e) throw e;
        }

        List<NotificationBatch> delivered() {
            return attempts.stream().map(Attempt::batch).toList();
        }
    }

    private final FakeNotifier a = new FakeNotifier(scheduler);
    private final FakeNotifier b = new FakeNotifier(scheduler);

    private NotificationDispatcher dispatcher() {
        Map<String, Notifier> notifiers = new LinkedHashMap<>();
        notifiers.put("a", a);
        notifiers.put("b", b);
        return new NotificationDispatcher(notifiers, WINDOW, RETRY, scheduler, scheduler, new CapturingLogger(logLines));
    }

    private static Map<String, EndpointDefinition> endpoints(String defaultsNotify, String... endpointNotify)
            throws Exception {
        StringBuilder json = new StringBuilder("{\"notifications\":{\"channels\":{\"a\":{},\"b\":{}}},");
        if (defaultsNotify != null) {
            json.append("\"defaults\":{\"notify\":").append(defaultsNotify).append("},");
        }
        json.append("\"endpoints\":[");
        for (int i = 0; i < endpointNotify.length; i++) {
            if (i > 0) json.append(',');
            json.append("{\"id\":\"e").append(i).append("\",\"name\":\"E").append(i)
                    .append("\",\"type\":\"http\"");
            if (endpointNotify[i] != null) json.append(",\"notify\":").append(endpointNotify[i]);
            json.append('}');
        }
        json.append("]}");
        Config config = new ConfigLoader(name -> null, ZoneOffset.UTC).load(json.toString());
        Map<String, EndpointDefinition> out = new LinkedHashMap<>();
        for (EndpointDefinition d : config.endpoints()) out.put(d.id(), d);
        return out;
    }

    private static Notification down(EndpointDefinition e, Instant time) {
        return new Notification(Notification.Kind.DOWN, e.id(), e.name(), time, time, "connection refused", null, null);
    }

    private static Notification test(Instant time) {
        return new Notification(Notification.Kind.TEST, null, null, time, null, null, "test", null);
    }

    // ---- routing ------------------------------------------------------------------------

    @Test
    void routingFollowsNotifyAndDefaults() throws Exception {
        // defaults notify both; e0 omitted, e1 false, e2 [], e3 only b
        Map<String, EndpointDefinition> eps = endpoints("[\"a\",\"b\"]", null, "false", "[]", "[\"b\"]");
        try (NotificationDispatcher d = dispatcher()) {
            assertEquals(List.of("a", "b"), d.route(eps.get("e0")));
            assertEquals(List.of(), d.route(eps.get("e1")));
            assertEquals(List.of(), d.route(eps.get("e2")));
            assertEquals(List.of("b"), d.route(eps.get("e3")));

            assertEquals(List.of(), d.notify(eps.get("e1"), down(eps.get("e1"), T0)));
            assertEquals(List.of(), d.notify(eps.get("e2"), down(eps.get("e2"), T0)));
            d.notify(eps.get("e0"), down(eps.get("e0"), T0));
            d.notify(eps.get("e3"), down(eps.get("e3"), T0));
            scheduler.advance(WINDOW);

            assertEquals(1, a.attempts.size());
            assertEquals(List.of("e0"), endpointIds(a.attempts.get(0).batch()));
            assertEquals(1, b.attempts.size());
            assertEquals(List.of("e0", "e3"), endpointIds(b.attempts.get(0).batch()));
        }
    }

    @Test
    void defaultsFalseMakesNotificationsOptIn() throws Exception {
        Map<String, EndpointDefinition> eps = endpoints("false", null, "[\"a\"]", "false");
        try (NotificationDispatcher d = dispatcher()) {
            assertEquals(List.of(), d.route(eps.get("e0")));
            assertEquals(List.of("a"), d.route(eps.get("e1")));
            assertEquals(List.of(), d.route(eps.get("e2")));
            for (EndpointDefinition e : eps.values()) d.notify(e, down(e, T0));
            scheduler.advance(WINDOW);
            assertEquals(1, a.attempts.size());
            assertEquals(List.of("e1"), endpointIds(a.attempts.get(0).batch()));
            assertTrue(b.attempts.isEmpty());
        }
    }

    @Test
    void omittedDefaultsMeanNoNotifications() throws Exception {
        Map<String, EndpointDefinition> eps = endpoints(null, null, "[\"a\",\"b\"]");
        try (NotificationDispatcher d = dispatcher()) {
            assertEquals(List.of(), d.route(eps.get("e0")));
            assertEquals(List.of("a", "b"), d.route(eps.get("e1")));
        }
    }

    @Test
    void sendToChannelsDirectlyRejectsUnknownNames() {
        try (NotificationDispatcher d = dispatcher()) {
            assertThrows(IllegalArgumentException.class, () -> d.send(List.of("a", "nope"), test(T0)));
            scheduler.advance(WINDOW);
            assertTrue(a.attempts.isEmpty(), "nothing queued when a name is unknown");
            d.send(List.of("b", "b"), test(T0));
            scheduler.advance(WINDOW);
            assertEquals(1, b.attempts.size());
            assertEquals(1, b.attempts.get(0).batch().notifications().size());
        }
    }

    @Test
    void stateChangesMapToNotifications() throws Exception {
        EndpointDefinition e = endpoints("[\"a\"]", (String) null).get("e0");
        CheckRecord failed = new CheckRecord(T0.plusSeconds(60), Duration.ofMillis(3), CheckResult.fail("timeout"));
        CheckRecord ok = new CheckRecord(T0.plusSeconds(600), Duration.ofMillis(3), CheckResult.ok("ok"));

        Notification down = Notification.forChange(e,
                new StateChange("e0", State.UP, State.DOWN, failed, T0, "refused", 3), "https://x/e").orElseThrow();
        assertEquals(Notification.Kind.DOWN, down.kind());
        assertEquals(T0, down.outageStart());
        assertEquals("timeout", down.failureMessage());
        assertEquals("E0", down.endpointName());

        Notification up = Notification.forChange(e,
                new StateChange("e0", State.DOWN, State.UP, ok, T0, "refused", 4), null).orElseThrow();
        assertEquals(Notification.Kind.RECOVERED, up.kind());
        assertEquals(Duration.ofMinutes(10), up.outageDuration().orElseThrow());

        assertTrue(Notification.forChange(e,
                new StateChange("e0", State.UNKNOWN, State.UP, ok, null, null, 0), null).isEmpty());
    }

    // ---- batching -----------------------------------------------------------------------

    @Test
    void notificationsWithinTheWindowAreCombined() {
        try (NotificationDispatcher d = dispatcher()) {
            d.send(List.of("a"), test(T0));
            scheduler.advance(Duration.ofSeconds(10));
            d.send(List.of("a"), test(scheduler.instant()));
            scheduler.advance(Duration.ofSeconds(19));
            d.send(List.of("a"), test(scheduler.instant()));
            assertTrue(a.attempts.isEmpty(), "nothing sent before the window closes");
            assertEquals(1, d.status("a").orElseThrow().pending());

            scheduler.advance(Duration.ofSeconds(1));
            assertEquals(1, a.attempts.size());
            assertEquals(T0.plus(WINDOW), a.attempts.get(0).time());
            assertEquals(3, a.attempts.get(0).batch().notifications().size());
            assertEquals("a", a.attempts.get(0).batch().channel());

            // After the window closed, a new one opens
            d.send(List.of("a"), test(scheduler.instant()));
            scheduler.advance(WINDOW);
            assertEquals(2, a.attempts.size());
            assertEquals(1, a.attempts.get(1).batch().notifications().size());
            assertEquals(0, d.status("a").orElseThrow().pending());
        }
    }

    @Test
    void notifyNeverSendsOnTheCallersThread() {
        try (NotificationDispatcher d = dispatcher()) {
            d.send(List.of("a"), test(T0));
            assertTrue(a.attempts.isEmpty());
            assertTrue(scheduler.pendingTasks() > 0);
        }
    }

    // ---- retry & backoff ----------------------------------------------------------------

    @Test
    void temporaryFailuresAreRetriedWithBackoffAndTheSameId() {
        a.outcomes.add(NotifierException.temporary("503"));
        a.outcomes.add(new IllegalStateException("boom"));
        a.outcomes.add(NotifierException.temporary("timeout"));
        try (NotificationDispatcher d = dispatcher()) {
            d.send(List.of("a"), test(T0));
            scheduler.advance(Duration.ofMinutes(5));

            List<Instant> times = a.attempts.stream().map(FakeNotifier.Attempt::time).toList();
            Instant first = T0.plus(WINDOW);
            assertEquals(List.of(first, first.plusSeconds(10), first.plusSeconds(30), first.plusSeconds(70)), times);
            assertEquals(1, a.delivered().stream().map(NotificationBatch::id).distinct().count());
            ChannelStatus s = d.status("a").orElseThrow();
            assertEquals(first.plusSeconds(70), s.lastSuccess());
            assertEquals(0, s.pending());
        }
    }

    @Test
    void backoffIsCappedAndGivesUpAfterMaxAttempts() {
        assertEquals(Duration.ofSeconds(10), RETRY.delayAfter(1));
        assertEquals(Duration.ofSeconds(40), RETRY.delayAfter(3));
        assertEquals(Duration.ofSeconds(60), RETRY.delayAfter(4));
        assertEquals(Duration.ofSeconds(60), RETRY.delayAfter(30));

        for (int i = 0; i < 10; i++) a.outcomes.add(NotifierException.temporary("down " + i));
        try (NotificationDispatcher d = dispatcher()) {
            d.send(List.of("a"), test(T0));
            scheduler.advance(Duration.ofHours(1));
            assertEquals(RETRY.maxAttempts(), a.attempts.size());
            List<Instant> times = a.attempts.stream().map(FakeNotifier.Attempt::time).toList();
            Instant first = T0.plus(WINDOW);
            assertEquals(List.of(first, first.plusSeconds(10), first.plusSeconds(30), first.plusSeconds(70),
                    first.plusSeconds(130)), times);
            assertEquals(0, d.status("a").orElseThrow().pending());
            assertTrue(logLines.stream().anyMatch(l -> l.startsWith("ERROR") && l.contains("after 5 attempts")));
        }
    }

    @Test
    void permanentFailuresAreNotRetried() {
        a.outcomes.add(NotifierException.permanent("401 unauthorized"));
        try (NotificationDispatcher d = dispatcher()) {
            d.send(List.of("a"), test(T0));
            scheduler.advance(Duration.ofHours(1));
            assertEquals(1, a.attempts.size());
            assertTrue(logLines.stream().anyMatch(l -> l.startsWith("ERROR") && l.contains("permanently")));
        }
    }

    @Test
    void retryAfterFromTheReceiverIsHonoured() {
        a.outcomes.add(NotifierException.retryAfter("429", Duration.ofSeconds(45)));
        a.outcomes.add(NotifierException.retryAfter("429", Duration.ofSeconds(1)));
        try (NotificationDispatcher d = dispatcher()) {
            d.send(List.of("a"), test(T0));
            scheduler.advance(Duration.ofMinutes(5));
            Instant first = T0.plus(WINDOW);
            List<Instant> times = a.attempts.stream().map(FakeNotifier.Attempt::time).toList();
            // 45 s asked for > 10 s backoff; then 1 s asked for < 20 s backoff
            assertEquals(List.of(first, first.plusSeconds(45), first.plusSeconds(65)), times);
        }
    }

    @Test
    void laterBatchesWaitBehindARetryAndKeepTheirOrder() {
        a.outcomes.add(NotifierException.temporary("503"));
        try (NotificationDispatcher d = dispatcher()) {
            d.send(List.of("a"), test(T0));
            scheduler.advance(WINDOW);                    // first attempt fails, retry at +10 s
            d.send(List.of("a", "b"), test(scheduler.instant()));
            scheduler.advance(Duration.ofSeconds(5));
            assertEquals(1, a.attempts.size());
            assertEquals(2, d.status("a").orElseThrow().pending());
            scheduler.advance(Duration.ofMinutes(2));
            assertEquals(3, a.attempts.size());
            assertEquals(a.attempts.get(0).batch().id(), a.attempts.get(1).batch().id());
            assertFalse(a.attempts.get(1).batch().id().equals(a.attempts.get(2).batch().id()));
            assertEquals(T0.plus(WINDOW).plus(WINDOW), a.attempts.get(2).time());
            // the other channel isn't held up
            assertEquals(1, b.attempts.size());
            assertEquals(T0.plus(WINDOW).plus(WINDOW), b.attempts.get(0).time());
        }
    }

    // ---- last error ---------------------------------------------------------------------

    @Test
    void lastErrorIsRecordedPerChannel() {
        a.outcomes.add(NotifierException.temporary("HTTP 503"));
        a.outcomes.add(NotifierException.temporary("HTTP 502"));
        try (NotificationDispatcher d = dispatcher()) {
            ChannelStatus initial = d.status("a").orElseThrow();
            assertNull(initial.lastError());
            assertNull(initial.lastSuccess());
            assertTrue(d.status("nope").isEmpty());

            d.send(List.of("a", "b"), test(T0));
            scheduler.advance(WINDOW);
            ChannelStatus s = d.status("a").orElseThrow();
            assertEquals("HTTP 503", s.lastError());
            assertEquals(T0.plus(WINDOW), s.lastErrorTime());
            assertNull(s.lastSuccess());
            assertNull(d.status("b").orElseThrow().lastError());

            scheduler.advance(Duration.ofSeconds(10));
            s = d.status("a").orElseThrow();
            assertEquals("HTTP 502", s.lastError());
            assertEquals(T0.plus(WINDOW).plusSeconds(10), s.lastErrorTime());

            scheduler.advance(Duration.ofSeconds(20));
            s = d.status("a").orElseThrow();
            assertEquals("HTTP 502", s.lastError(), "kept after a later success");
            assertEquals(T0.plus(WINDOW).plusSeconds(30), s.lastSuccess());
            assertTrue(s.lastSuccess().isAfter(s.lastErrorTime()));
            assertTrue(logLines.stream().anyMatch(l -> l.startsWith("WARNING") && l.contains("HTTP 503")));
        }
    }

    @Test
    void closeStopsDeliveries() {
        NotificationDispatcher d = dispatcher();
        d.send(List.of("a"), test(T0));
        d.close();
        assertTrue(scheduler.closed);
        scheduler.advance(Duration.ofMinutes(5));
        assertTrue(a.attempts.isEmpty());
        d.send(List.of("a"), test(T0));
        scheduler.advance(Duration.ofMinutes(5));
        assertTrue(a.attempts.isEmpty());
    }

    @Test
    void realSchedulerDeliversAsynchronously() throws Exception {
        List<NotificationBatch> got = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        try (NotificationDispatcher d = NotificationDispatcher.create(Map.of("a", batch -> {
            got.add(batch);
            latch.countDown();
        }), Duration.ofMillis(20))) {
            d.send(List.of("a"), test(T0));
            d.send(List.of("a"), test(T0));
            assertTrue(latch.await(5, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1, got.size());
            assertEquals(2, got.get(0).notifications().size());
        }
    }

    private static List<String> endpointIds(NotificationBatch batch) {
        return batch.notifications().stream().map(Notification::endpointId).toList();
    }

    static final class CapturingLogger implements Logger {
        private final List<String> lines;

        CapturingLogger(List<String> lines) {
            this.lines = lines;
        }

        @Override
        public String getName() {
            return "test";
        }

        @Override
        public boolean isLoggable(Level level) {
            return true;
        }

        @Override
        public synchronized void log(Level level, ResourceBundle bundle, String msg, Throwable thrown) {
            lines.add(level + " " + msg);
        }

        @Override
        public synchronized void log(Level level, ResourceBundle bundle, String format, Object... params) {
            lines.add(level + " " + (params == null || params.length == 0 ? format : MessageFormat.format(format, params)));
        }

        @Override
        public void log(Level level, Supplier<String> msgSupplier) {
            log(level, null, msgSupplier.get(), (Throwable) null);
        }
    }
}
