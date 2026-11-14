package io.siggi.healthchecker.notify;

import com.google.gson.JsonObject;
import io.siggi.healthchecker.api.CheckResult;
import io.siggi.healthchecker.audit.AuditLog;
import io.siggi.healthchecker.config.EndpointDefinition;
import io.siggi.healthchecker.config.MaintenanceSettings;
import io.siggi.healthchecker.config.Visibility;
import io.siggi.healthchecker.history.HistoryStore;
import io.siggi.healthchecker.maintenance.MaintenanceAnnouncer;
import io.siggi.healthchecker.maintenance.MaintenanceScheduler;
import io.siggi.healthchecker.maintenance.MaintenanceStore;
import io.siggi.healthchecker.maintenance.MaintenanceWindow;
import io.siggi.healthchecker.status.CheckRecord;
import io.siggi.healthchecker.status.EndpointStatus;
import io.siggi.healthchecker.status.State;
import io.siggi.healthchecker.status.StateChange;
import io.siggi.healthchecker.status.StatusRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Endpoint alerts and announcements around maintenance windows, wired as in the daemon. */
class MaintenanceNotificationsTest {
    private static final Instant T0 = Instant.parse("2026-11-14T12:00:00Z");
    private static final RetryPolicy RETRY = new RetryPolicy(Duration.ofSeconds(10), Duration.ofSeconds(60), 5);
    private static final MaintenanceStore.Actor SIGGI = new MaintenanceStore.Actor("siggi", "Siggi");

    @TempDir
    Path dir;

    private final ManualScheduler clock = new ManualScheduler(T0);
    private final List<String> logLines = new ArrayList<>();
    private final NotificationDispatcherTest.FakeNotifier ops = new NotificationDispatcherTest.FakeNotifier(clock);
    private final NotificationDispatcherTest.FakeNotifier maint = new NotificationDispatcherTest.FakeNotifier(clock);
    private final NotificationDispatcherTest.FakeNotifier other = new NotificationDispatcherTest.FakeNotifier(clock);
    private final EndpointDefinition web = new EndpointDefinition("web", "Website", "http", null, Visibility.PUBLIC,
            60, 1000, 1, List.of("ops"), List.of(30, 60, 120), 0, new JsonObject());

    private MaintenanceStore store;
    private StatusRegistry status;
    private HistoryStore history;
    private NotificationDispatcher dispatcher;
    private ReminderScheduler reminders;
    private OutageAlerts alerts;
    private MaintenanceScheduler scheduler;

    @BeforeEach
    void setUp() throws Exception {
        System.Logger log = new NotificationDispatcherTest.CapturingLogger(logLines);
        store = new MaintenanceStore(dir, new MaintenanceSettings(false, 7, 7, 1), new MaintenanceStore.References() {
            @Override
            public boolean endpointExists(String id) {
                return id.equals("web");
            }

            @Override
            public boolean categoryExists(String id) {
                return false;
            }

            @Override
            public boolean channelExists(String name) {
                return Set.of("ops", "maint", "other").contains(name);
            }
        }, new AuditLog(dir.resolve(AuditLog.FILE_NAME), clock), clock, ZoneOffset.UTC);
        store.load();
        status = new StatusRegistry(log);
        status.register("web", 1);
        history = HistoryStore.open(dir, clock, log);
        Map<String, Notifier> notifiers = new LinkedHashMap<>();
        notifiers.put("ops", ops);
        notifiers.put("maint", maint);
        notifiers.put("other", other);
        dispatcher = new NotificationDispatcher(notifiers, Duration.ZERO, RETRY, clock, clock, log);
        ReminderScheduler.Source targets = id -> id.equals("web")
                ? Optional.of(new ReminderScheduler.Target(web, "status 503", "https://status.example/web"))
                : Optional.empty();
        reminders = new ReminderScheduler(ReminderScheduler.stateFile(dir),
                id -> status.maintenanceWindow(id).isPresent() ? Optional.empty() : targets.lookup(id),
                dispatcher::notify, clock, clock, log);
        alerts = new OutageAlerts(dispatcher::notify, reminders, targets, id -> status.status(id)
                .map(EndpointStatus::checkState).orElse(State.UNKNOWN) == State.DOWN);
        scheduler = new MaintenanceScheduler(store, status, history, () -> Map.of("web", "none"), clock, log);
        scheduler.addListener(new MaintenanceScheduler.Listener() {
            @Override
            public void endpointChanged(String endpointId, String fromWindowId, String toWindowId, Instant at) {
                alerts.maintenanceChanged(endpointId, fromWindowId, toWindowId, at);
            }
        });
        scheduler.addListener(new MaintenanceAnnouncer(store, Duration.ofHours(1), ZoneOffset.UTC,
                w -> List.of("Website"), id -> "https://status.example/maintenance/" + id,
                dispatcher::send, T0));
    }

    @AfterEach
    void tearDown() {
        scheduler.close();
        reminders.close();
        dispatcher.close();
    }

    private static Instant at(int minutes) {
        return T0.plus(Duration.ofMinutes(minutes));
    }

    /** Moves the clock to minute {@code minute}, running whatever falls due on the way (reminders, deliveries). */
    private void advanceTo(int minute) {
        clock.advance(Duration.between(clock.instant(), at(minute)));
    }

    private void tickAt(int minute) {
        advanceTo(minute);
        scheduler.tick();
        clock.runDue();
    }

    /** Records a check at minute {@code minute} as the daemon does: status, history, then notifications. */
    private void check(int minute, boolean success) {
        StateChange change = record(minute, success);
        if (change != null) {
            alerts.onStateChange(web, change, "https://status.example/web/event");
        }
        clock.runDue();
    }

    private StateChange record(int minute, boolean success) {
        advanceTo(minute);
        CheckRecord check = new CheckRecord(at(minute), Duration.ZERO,
                success ? CheckResult.ok("status 200") : CheckResult.fail("status 503"));
        StateChange change = status.record("web", check).orElse(null);
        history.record("web", check, change);
        return change;
    }

    private MaintenanceWindow window(String title, int from, int to, List<String> channels) throws Exception {
        return store.create(new MaintenanceStore.Draft(title, "Kernel update", at(from), at(to), List.of("web"),
                List.of(), channels), SIGGI);
    }

    private static List<Notification> notifications(NotificationDispatcherTest.FakeNotifier notifier) {
        return notifier.delivered().stream().flatMap(b -> b.notifications().stream()).toList();
    }

    private static List<Notification.Kind> kinds(NotificationDispatcherTest.FakeNotifier notifier) {
        return notifications(notifier).stream().map(Notification::kind).toList();
    }

    @Test
    void noAlertsDuringMaintenance() throws Exception {
        check(1, true);
        window("Reboot", 10, 70, List.of());
        check(5, false); // down before the window: alerted as usual
        assertEquals(List.of(Notification.Kind.DOWN), kinds(ops));

        tickAt(10);
        assertEquals(State.MAINTENANCE, status.status("web").orElseThrow().state());
        assertTrue(reminders.pendingEndpoints().isEmpty(), "reminders cancelled when the window starts");
        advanceTo(40); // the 0:30 reminder of the outage would have been due at minute 35
        check(41, true); // recovery inside the window: silent
        check(45, false); // down again inside the window: silent, no reminders
        assertTrue(reminders.pendingEndpoints().isEmpty());
        advanceTo(68);
        check(69, true);
        tickAt(70); // window ends with the endpoint up: nothing
        advanceTo(300);

        assertEquals(List.of(Notification.Kind.DOWN), kinds(ops));
        assertTrue(other.delivered().isEmpty());
        assertTrue(maint.delivered().isEmpty());
    }

    @Test
    void alertAtWindowEndIfStillDown() throws Exception {
        check(1, true);
        window("Reboot", 10, 20, List.of());
        tickAt(10);
        check(12, false);
        assertTrue(ops.delivered().isEmpty());

        tickAt(22); // a late tick still dates the outage from the window's end
        List<Notification> sent = notifications(ops);
        assertEquals(1, sent.size());
        Notification down = sent.get(0);
        assertEquals(Notification.Kind.DOWN, down.kind());
        assertEquals("web", down.endpointId());
        assertEquals(at(20), down.outageStart());
        assertEquals(at(20), down.time());
        assertEquals("status 503", down.failureMessage());
        assertEquals(Optional.of(at(50)), reminders.nextReminder("web"), "reminders count from the window's end");

        advanceTo(55);
        check(56, true);
        assertEquals(List.of(Notification.Kind.DOWN, Notification.Kind.REMINDER, Notification.Kind.RECOVERED),
                kinds(ops));
        Notification recovered = notifications(ops).get(2);
        assertEquals(at(20), recovered.outageStart());
        assertEquals(Duration.ofMinutes(36), recovered.outageDuration().orElseThrow());
        assertTrue(reminders.pendingEndpoints().isEmpty());
        assertTrue(maint.delivered().isEmpty());
    }

    @Test
    void downChangeRacingTheWindowEndIsAlertedOnce() throws Exception {
        check(1, true);
        window("Reboot", 10, 20, List.of());
        tickAt(10);
        // The check goes DOWN, the window ends before its listener runs, then the listener runs.
        StateChange change = record(19, false);
        tickAt(20);
        alerts.onStateChange(web, change, null);
        clock.runDue();
        assertEquals(List.of(Notification.Kind.DOWN), kinds(ops));
        assertEquals(at(20), notifications(ops).get(0).outageStart());
    }

    @Test
    void announcementsGoToWindowChannelsOnly() throws Exception {
        check(1, true);
        advanceTo(2);
        MaintenanceWindow w = window("Weekly reboot", 180, 240, List.of("maint"));
        tickAt(3);
        tickAt(4); // nothing twice
        tickAt(120); // announceBeforeHours = 1
        tickAt(180);
        check(190, false); // suppressed
        tickAt(240); // ended; still down → normal down alert via the endpoint's routing
        tickAt(241);

        List<Notification> announced = notifications(maint);
        assertEquals(List.of("Maintenance scheduled: Weekly reboot", "Upcoming maintenance: Weekly reboot",
                "Maintenance started: Weekly reboot", "Maintenance ended: Weekly reboot"),
                announced.stream().map(Notification::endpointName).toList());
        for (Notification n : announced) {
            assertEquals(Notification.Kind.ANNOUNCEMENT, n.kind());
            assertNull(n.endpointId());
            assertTrue(n.text().contains("Affects: Website"), n.text());
            assertEquals("https://status.example/maintenance/" + w.id(), n.link());
        }
        assertEquals(List.of(at(2), at(120), at(180), at(240)), announced.stream().map(Notification::time).toList());
        assertEquals("Sat 14 Nov 2026 15:00–16:00 (Z)\nAffects: Website\nKernel update", announced.get(0).text());

        assertEquals(List.of(Notification.Kind.DOWN), kinds(ops));
        assertTrue(other.delivered().isEmpty());
    }

    @Test
    void cancelAndEndNowAreAnnounced() throws Exception {
        check(1, true);
        MaintenanceWindow cancelled = window("Upgrade", 600, 660, List.of("maint", "other"));
        tickAt(2);
        store.cancel(cancelled.id(), cancelled.version(), SIGGI);
        tickAt(3);

        MaintenanceWindow running = window("Reboot", 10, 60, List.of("maint"));
        tickAt(10);
        check(12, false);
        advanceTo(30);
        scheduler.endNow(running.id(), store.window(running.id()).orElseThrow().version(), SIGGI);
        clock.runDue();

        assertEquals(List.of("Maintenance scheduled: Upgrade", "Maintenance cancelled: Upgrade",
                "Maintenance scheduled: Reboot", "Maintenance started: Reboot", "Maintenance ended: Reboot"),
                notifications(maint).stream().map(Notification::endpointName).toList());
        assertEquals(List.of("Maintenance scheduled: Upgrade", "Maintenance cancelled: Upgrade"),
                notifications(other).stream().map(Notification::endpointName).toList());
        assertEquals(at(30), notifications(maint).get(4).time());
        // "End now" with the endpoint still down: a down alert at once, outage from then.
        assertEquals(List.of(Notification.Kind.DOWN), kinds(ops));
        assertEquals(at(30), notifications(ops).get(0).outageStart());
    }

    @Test
    void createdAndCancelledBetweenTicksAndPastEventsAreNotAnnounced() throws Exception {
        MaintenanceWindow w = window("Upgrade", 600, 660, List.of("maint"));
        store.cancel(w.id(), w.version(), SIGGI);
        tickAt(1);
        assertTrue(maint.delivered().isEmpty());

        // A restart: a new announcer doesn't repeat what happened before it started.
        window("Reboot", 10, 20, List.of("maint"));
        MaintenanceAnnouncer restarted = new MaintenanceAnnouncer(store, Duration.ofHours(1), ZoneOffset.UTC,
                x -> List.of(), id -> null, dispatcher::send, at(15));
        advanceTo(15);
        restarted.ticked(at(15));
        advanceTo(20);
        restarted.ticked(at(20));
        clock.runDue();
        assertEquals(List.of("Maintenance ended: Reboot"),
                notifications(maint).stream().map(Notification::endpointName).toList());
    }
}
