package io.siggi.healthchecker.notify;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.siggi.healthchecker.api.CheckResult;
import io.siggi.healthchecker.config.EndpointDefinition;
import io.siggi.healthchecker.config.Visibility;
import io.siggi.healthchecker.status.CheckRecord;
import io.siggi.healthchecker.status.State;
import io.siggi.healthchecker.status.StateChange;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReminderSchedulerTest {
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @TempDir
    Path dataDir;

    private final Map<String, EndpointDefinition> endpoints = new HashMap<>();
    private final List<Notification> sent = new ArrayList<>();
    private final List<String> logLines = new ArrayList<>();

    private static EndpointDefinition endpoint(String id, List<Integer> reminders, int repeat) {
        return new EndpointDefinition(id, "Name " + id, "http", null, Visibility.PUBLIC, 60, 1000, 1,
                List.of("a"), reminders, repeat, new JsonObject());
    }

    private ReminderScheduler reminders(ManualScheduler clock) {
        return new ReminderScheduler(ReminderScheduler.stateFile(dataDir),
                id -> Optional.ofNullable(endpoints.get(id))
                        .map(e -> new ReminderScheduler.Target(e, "connection refused", "https://x/event/" + id)),
                (endpoint, n) -> sent.add(n), clock, clock,
                new NotificationDispatcherTest.CapturingLogger(logLines));
    }

    private static StateChange change(String id, State from, State to, Instant time, Instant streakStart) {
        CheckRecord check = new CheckRecord(time, Duration.ofMillis(3),
                to == State.DOWN ? CheckResult.fail("refused") : CheckResult.ok("ok"));
        return new StateChange(id, from, to, check, streakStart, to == State.DOWN ? "refused" : null, 1);
    }

    private List<Duration> sentOffsets() {
        return sent.stream().map(n -> Duration.between(T0, n.time())).toList();
    }

    @Test
    void defaultRemindersAt30_60_120AndNoneAfter() {
        endpoints.put("web", endpoint("web", List.of(30, 60, 120), 0));
        ManualScheduler clock = new ManualScheduler(T0);
        try (ReminderScheduler r = reminders(clock)) {
            r.onStateChange(change("web", State.UP, State.DOWN, T0, T0));
            assertEquals(Optional.of(T0.plus(Duration.ofMinutes(30))), r.nextReminder("web"));
            clock.advance(Duration.ofHours(24));
        }
        assertEquals(List.of(Duration.ofMinutes(30), Duration.ofMinutes(60), Duration.ofMinutes(120)), sentOffsets());
        for (Notification n : sent) {
            assertEquals(Notification.Kind.REMINDER, n.kind());
            assertEquals("web", n.endpointId());
            assertEquals("Name web", n.endpointName());
            assertEquals(T0, n.outageStart());
            assertEquals("connection refused", n.failureMessage());
            assertEquals("https://x/event/web", n.link());
        }
        assertEquals(Duration.ofHours(2), sent.get(2).outageDuration().orElseThrow());
        assertEquals(0, clock.pendingTasks());
    }

    @Test
    void remindersCountFromStreakStartNotFromDownTime() {
        endpoints.put("web", endpoint("web", List.of(30, 60), 0));
        ManualScheduler clock = new ManualScheduler(T0.plus(Duration.ofMinutes(3)));
        try (ReminderScheduler r = reminders(clock)) {
            // failure threshold reached 3 minutes after the first failure
            r.onStateChange(change("web", State.UP, State.DOWN, clock.instant(), T0));
            clock.advance(Duration.ofHours(2));
        }
        assertEquals(List.of(Duration.ofMinutes(30), Duration.ofMinutes(60)), sentOffsets());
    }

    @Test
    void slotsAlreadyPastWhenDownIsDeclaredAreSkipped() {
        endpoints.put("web", endpoint("web", List.of(1, 60), 0));
        ManualScheduler clock = new ManualScheduler(T0.plus(Duration.ofMinutes(5)));
        try (ReminderScheduler r = reminders(clock)) {
            r.start("web", T0);
            clock.runDue();
            assertTrue(sent.isEmpty());
            clock.advance(Duration.ofHours(2));
        }
        assertEquals(List.of(Duration.ofMinutes(60)), sentOffsets());
    }

    @Test
    void noneAfterRecovery() {
        endpoints.put("web", endpoint("web", List.of(30, 60, 120), 0));
        ManualScheduler clock = new ManualScheduler(T0);
        try (ReminderScheduler r = reminders(clock)) {
            r.onStateChange(change("web", State.UP, State.DOWN, T0, T0));
            clock.advance(Duration.ofMinutes(45));
            r.onStateChange(change("web", State.DOWN, State.UP, clock.instant(), T0));
            assertEquals(List.of(), r.pendingEndpoints());
            assertEquals(Optional.empty(), r.nextReminder("web"));
            clock.advance(Duration.ofHours(24));
        }
        assertEquals(List.of(Duration.ofMinutes(30)), sentOffsets());
    }

    @Test
    void newOutageAfterRecoveryStartsAFreshSchedule() {
        endpoints.put("web", endpoint("web", List.of(30), 0));
        ManualScheduler clock = new ManualScheduler(T0);
        try (ReminderScheduler r = reminders(clock)) {
            r.onStateChange(change("web", State.UP, State.DOWN, T0, T0));
            clock.advance(Duration.ofMinutes(20));
            r.onStateChange(change("web", State.DOWN, State.UP, clock.instant(), T0));
            Instant second = clock.instant().plus(Duration.ofMinutes(5));
            clock.advance(Duration.ofMinutes(5));
            r.onStateChange(change("web", State.UP, State.DOWN, second, second));
            clock.advance(Duration.ofHours(2));
        }
        // only the second outage's reminder, 30 min after its start (T0 + 55 min)
        assertEquals(List.of(Duration.ofMinutes(55)), sentOffsets());
    }

    @Test
    void repeatIntervalAfterLastListedReminder() {
        endpoints.put("web", endpoint("web", List.of(30, 60), 45));
        ManualScheduler clock = new ManualScheduler(T0);
        try (ReminderScheduler r = reminders(clock)) {
            r.start("web", T0);
            clock.advance(Duration.ofMinutes(240));
        }
        assertEquals(List.of(30L, 60L, 105L, 150L, 195L, 240L),
                sentOffsets().stream().map(Duration::toMinutes).toList());
    }

    @Test
    void emptyRemindersDisablesEvenWithRepeat() {
        endpoints.put("web", endpoint("web", List.of(), 10));
        ManualScheduler clock = new ManualScheduler(T0);
        try (ReminderScheduler r = reminders(clock)) {
            r.start("web", T0);
            clock.advance(Duration.ofHours(5));
        }
        assertTrue(sent.isEmpty());
    }

    @Test
    void stateIsPersisted() throws Exception {
        endpoints.put("web", endpoint("web", List.of(30, 60, 120), 0));
        ManualScheduler clock = new ManualScheduler(T0);
        Path file = dataDir.resolve("notifications").resolve("state.json");
        try (ReminderScheduler r = reminders(clock)) {
            r.start("web", T0);
            JsonObject entry = JsonParser.parseString(Files.readString(file)).getAsJsonObject()
                    .getAsJsonArray("reminders").get(0).getAsJsonObject();
            assertEquals("web", entry.get("endpointId").getAsString());
            assertEquals(T0.toString(), entry.get("outageStart").getAsString());
            assertEquals(0, entry.get("sent").getAsInt());
            clock.advance(Duration.ofMinutes(31));
            entry = JsonParser.parseString(Files.readString(file)).getAsJsonObject()
                    .getAsJsonArray("reminders").get(0).getAsJsonObject();
            assertEquals(1, entry.get("sent").getAsInt());
            r.cancel("web");
            assertEquals(0, JsonParser.parseString(Files.readString(file)).getAsJsonObject()
                    .getAsJsonArray("reminders").size());
        }
    }

    @Test
    void restartMidOutageSendsTheOverdueOneOnce() {
        endpoints.put("web", endpoint("web", List.of(30, 60, 120), 0));
        ManualScheduler clock = new ManualScheduler(T0);
        try (ReminderScheduler r = reminders(clock)) {
            r.onStateChange(change("web", State.UP, State.DOWN, T0, T0));
            clock.advance(Duration.ofMinutes(40)); // 30-minute reminder sent
        }
        assertEquals(List.of(Duration.ofMinutes(30)), sentOffsets());

        // down for a while: the 60 and 120 minute slots pass while the app isn't running
        ManualScheduler clock2 = new ManualScheduler(T0.plus(Duration.ofMinutes(150)));
        try (ReminderScheduler r = reminders(clock2)) {
            r.load();
            assertEquals(List.of(Duration.ofMinutes(30), Duration.ofMinutes(150)), sentOffsets());
            Notification overdue = sent.get(1);
            assertEquals(Notification.Kind.REMINDER, overdue.kind());
            assertEquals(T0, overdue.outageStart());
            // the restarted check finds it still down: same outage, nothing re-sent
            Instant later = clock2.instant().plus(Duration.ofMinutes(1));
            clock2.advance(Duration.ofMinutes(1));
            r.onStateChange(change("web", State.UNKNOWN, State.DOWN, later, later));
            clock2.advance(Duration.ofHours(24));
        }
        assertEquals(2, sent.size());

        // and a second restart doesn't send it again
        ManualScheduler clock3 = new ManualScheduler(T0.plus(Duration.ofMinutes(200)));
        try (ReminderScheduler r = reminders(clock3)) {
            r.load();
            clock3.advance(Duration.ofHours(24));
            assertEquals(List.of("web"), r.pendingEndpoints());
        }
        assertEquals(2, sent.size());
    }

    @Test
    void restartWithNothingOverdueContinuesTheSchedule() {
        endpoints.put("web", endpoint("web", List.of(30, 60, 120), 0));
        ManualScheduler clock = new ManualScheduler(T0);
        try (ReminderScheduler r = reminders(clock)) {
            r.start("web", T0);
            clock.advance(Duration.ofMinutes(35));
        }
        ManualScheduler clock2 = new ManualScheduler(T0.plus(Duration.ofMinutes(50)));
        try (ReminderScheduler r = reminders(clock2)) {
            r.load();
            assertEquals(1, sent.size());
            clock2.advance(Duration.ofHours(3));
        }
        assertEquals(List.of(Duration.ofMinutes(30), Duration.ofMinutes(60), Duration.ofMinutes(120)), sentOffsets());
    }

    @Test
    void restartThenUpCancels() {
        endpoints.put("web", endpoint("web", List.of(30, 60, 120), 0));
        ManualScheduler clock = new ManualScheduler(T0);
        try (ReminderScheduler r = reminders(clock)) {
            r.start("web", T0);
        }
        ManualScheduler clock2 = new ManualScheduler(T0.plus(Duration.ofMinutes(10)));
        try (ReminderScheduler r = reminders(clock2)) {
            r.load();
            r.onStateChange(change("web", State.UNKNOWN, State.UP, clock2.instant(), null));
            clock2.advance(Duration.ofHours(3));
            assertEquals(List.of(), r.pendingEndpoints());
        }
        assertTrue(sent.isEmpty());
    }

    @Test
    void restartDropsRemovedEndpointsAndSurvivesCorruptState() throws Exception {
        endpoints.put("web", endpoint("web", List.of(30), 0));
        ManualScheduler clock = new ManualScheduler(T0);
        try (ReminderScheduler r = reminders(clock)) {
            r.start("web", T0);
        }
        endpoints.clear();
        ManualScheduler clock2 = new ManualScheduler(T0.plus(Duration.ofHours(1)));
        try (ReminderScheduler r = reminders(clock2)) {
            r.load();
            assertEquals(List.of(), r.pendingEndpoints());
        }
        assertTrue(sent.isEmpty());

        Files.writeString(ReminderScheduler.stateFile(dataDir), "{not json");
        try (ReminderScheduler r = reminders(new ManualScheduler(T0))) {
            r.load();
            assertEquals(List.of(), r.pendingEndpoints());
        }
        assertFalse(logLines.isEmpty());
    }

    @Test
    void editedRemindersApplyOnRefresh() {
        endpoints.put("web", endpoint("web", List.of(30, 60, 120), 0));
        ManualScheduler clock = new ManualScheduler(T0);
        try (ReminderScheduler r = reminders(clock)) {
            r.start("web", T0);
            clock.advance(Duration.ofMinutes(10));
            endpoints.put("web", endpoint("web", List.of(15), 0));
            r.refresh("web");
            clock.advance(Duration.ofHours(3));
        }
        assertEquals(List.of(Duration.ofMinutes(15)), sentOffsets());
    }

    @Test
    void offsets() {
        assertEquals(Optional.of(Duration.ofMinutes(60)), ReminderScheduler.offset(List.of(30, 60), 0, 1));
        assertEquals(Optional.empty(), ReminderScheduler.offset(List.of(30, 60), 0, 2));
        assertEquals(Optional.of(Duration.ofMinutes(80)), ReminderScheduler.offset(List.of(30, 60), 10, 3));
        assertEquals(Optional.empty(), ReminderScheduler.offset(List.of(), 10, 0));
        assertEquals(3, ReminderScheduler.dueCount(List.of(30, 60), 10, Duration.ofMinutes(79)));
        assertEquals(0, ReminderScheduler.dueCount(List.of(30, 60), 10, Duration.ofMinutes(29)));
    }
}
