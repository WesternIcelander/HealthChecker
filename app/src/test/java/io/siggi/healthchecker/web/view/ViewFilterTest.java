package io.siggi.healthchecker.web.view;

import com.google.gson.JsonObject;
import io.siggi.healthchecker.api.CheckResult;
import io.siggi.healthchecker.config.EndpointDefinition;
import io.siggi.healthchecker.config.Visibility;
import io.siggi.healthchecker.history.EventNote;
import io.siggi.healthchecker.history.HistoryEvent;
import io.siggi.healthchecker.plugin.JsonEndpointConfig.Target;
import io.siggi.healthchecker.schedule.Endpoint;
import io.siggi.healthchecker.status.CheckRecord;
import io.siggi.healthchecker.status.EndpointStatus;
import io.siggi.healthchecker.status.State;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ViewFilterTest {
    private static final Instant T0 = Instant.parse("2026-11-13T09:00:00Z");
    private static final Viewer LOGGED_IN_WITHOUT_PERMISSION = new Viewer("bob", false);
    private static final Viewer PRIVILEGED = new Viewer("alice", true);

    private final ViewFilter filter = new ViewFilter();

    private static Endpoint endpoint(String id, Visibility visibility) {
        EndpointDefinition def = new EndpointDefinition(id, "Name " + id, "http", null, visibility, 60, 5000, 1,
                List.of("ops"), List.of(30, 60), 0, new JsonObject());
        return new Endpoint(def, List.of(new Target("url", "https://Secret.Example.com:8443/"),
                new Target("host", "secret.example.com")), timeout -> CheckResult.ok("ok"));
    }

    private static CheckRecord record(Instant at, CheckResult result) {
        return new CheckRecord(at, Duration.ofMillis(84), result);
    }

    private static EndpointStatus status(String id, CheckRecord... checks) {
        CheckRecord last = checks[checks.length - 1];
        return new EndpointStatus(id, State.UP, T0, 1, 0, null, null, last, List.of(checks));
    }

    private static CheckResult detailed() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("statusCode", 200);
        details.put("server", "nginx on SECRET.example.com");
        details.put("redirect", "https://secret.example.com:8443/login");
        details.put("version", "1.21");
        return new CheckResult(true, "status 200", details);
    }

    private static HistoryEvent event(String endpointId, String title, String firstFailure, String lastFailure) {
        List<EventNote> notes = List.of(
                new EventNote("n1", "Alice", T0, null, "public note", EventNote.Visibility.PUBLIC),
                new EventNote("n2", "Alice", T0, T0.plusSeconds(5), "private note", EventNote.Visibility.PRIVATE));
        return new HistoryEvent("e1", endpointId, HistoryEvent.Kind.OUTAGE, T0, T0.plusSeconds(600), title,
                firstFailure, lastFailure, T0.plusSeconds(540), notes, true, false, "m1");
    }

    @Test
    void privateEndpointsAbsentForAnonymousAndViewersWithoutPermission() {
        Endpoint priv = endpoint("priv", Visibility.PRIVATE);
        EndpointStatus st = status("priv", record(T0, detailed()));
        for (Viewer viewer : List.of(Viewer.ANONYMOUS, LOGGED_IN_WITHOUT_PERMISSION)) {
            assertFalse(filter.canSee(viewer, priv));
            assertEquals(Optional.empty(), filter.endpoint(viewer, priv, st));
            assertEquals(Optional.empty(), filter.check(viewer, priv, st.lastCheck()));
            assertEquals(Optional.empty(), filter.event(viewer, priv, event("priv", null, "x", "y")));
        }
        assertTrue(filter.canSee(PRIVILEGED, priv));
        assertTrue(filter.endpoint(PRIVILEGED, priv, st).isPresent());
    }

    @Test
    void publicStatusFieldsExactlyAsInVisibilityTable() {
        Endpoint ep = endpoint("survival", Visibility.PUBLIC_STATUS);
        CheckRecord fail = record(T0, CheckResult.fail("timeout after 5000 ms").with("players", 3));
        CheckRecord ok = record(T0.plusSeconds(60), detailed());
        EndpointView view = filter.endpoint(Viewer.ANONYMOUS, ep, status("survival", fail, ok)).orElseThrow();

        // Name, state: yes.
        assertEquals("survival", view.id());
        assertEquals("Name survival", view.name());
        assertEquals(State.UP, view.state());
        assertEquals(T0, view.since());
        // Recent checks as pass/fail only: no latency, message or details.
        assertEquals(List.of(new CheckView(T0, false, null, null, null),
                new CheckView(T0.plusSeconds(60), true, null, null, null)), view.recentChecks());
        assertNull(view.lastCheck());
        // No type, and nothing privileged; targets never.
        assertNull(view.type());
        assertNull(view.visibility());
        assertNull(view.targets());
        assertNull(view.notifyChannels());
        assertNull(view.reminders());
        assertNull(view.reminderRepeatMinutes());

        assertEquals(new CheckView(T0, false, null, null, null),
                filter.check(Viewer.ANONYMOUS, ep, fail).orElseThrow());

        // Events: title, start, end and public notes; no failure messages.
        EventView ev = filter.event(Viewer.ANONYMOUS, ep, event("survival", null, "status 503", "status 503"))
                .orElseThrow();
        assertEquals("e1", ev.id());
        assertEquals(HistoryEvent.Kind.OUTAGE, ev.kind());
        assertEquals(T0, ev.start());
        assertEquals(T0.plusSeconds(600), ev.end());
        assertEquals(Duration.ofMinutes(10), ev.duration(T0.plusSeconds(9999)));
        assertEquals("Name survival outage", ev.title());
        assertNull(ev.customTitle());
        assertNull(ev.firstFailureMessage());
        assertNull(ev.lastFailureMessage());
        assertNull(ev.endedUnobserved());
        assertNull(ev.endedByConfigChange());
        assertNull(ev.maintenanceId());
        assertEquals(List.of(new NoteView("n1", "Alice", T0, null, "public note", null)), ev.notes());
    }

    @Test
    void publicEndpointShowsDetailsButNoTargets() {
        Endpoint ep = endpoint("website", Visibility.PUBLIC);
        CheckRecord ok = record(T0, CheckResult.ok("status 200").with("statusCode", 200));
        EndpointView view = filter.endpoint(Viewer.ANONYMOUS, ep, status("website", ok)).orElseThrow();
        assertEquals("http", view.type());
        CheckView expected = new CheckView(T0, true, Duration.ofMillis(84), "status 200", Map.of("statusCode", 200));
        assertEquals(expected, view.lastCheck());
        assertEquals(List.of(expected), view.recentChecks());
        assertNull(view.targets());
        assertNull(view.visibility());
        assertNull(view.notifyChannels());

        EventView ev = filter.event(Viewer.ANONYMOUS, ep, event("website", "DB migration", "status 503",
                "connection failed")).orElseThrow();
        assertEquals("DB migration", ev.title());
        assertNull(ev.customTitle());
        assertEquals("status 503", ev.firstFailureMessage());
        assertEquals("connection failed", ev.lastFailureMessage());
        assertEquals(1, ev.notes().size());
    }

    @Test
    void leakingMessageAndDetailsOmittedWithoutPlaceholderWhileOtherDetailsRemain() {
        Endpoint ep = endpoint("website", Visibility.PUBLIC);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("statusCode", 200);
        details.put("server", "nginx on SECRET.example.com");
        details.put("redirect", "https://secret.example.com:8443/login");
        details.put("secret.example.com", true);
        details.put("version", "1.21");
        CheckRecord leaky = record(T0, new CheckResult(false, "could not reach Secret.Example.Com", details));

        CheckView view = filter.check(Viewer.ANONYMOUS, ep, leaky).orElseThrow();
        assertNull(view.message());
        assertEquals(List.of("statusCode", "version"), List.copyOf(view.details().keySet()));
        assertEquals(200, view.details().get("statusCode"));
        assertEquals("1.21", view.details().get("version"));
        assertFalse(view.details().toString().toLowerCase().contains("secret"));

        // Same through the endpoint view.
        EndpointView ev = filter.endpoint(Viewer.ANONYMOUS, ep, status("website", leaky)).orElseThrow();
        assertEquals(view, ev.lastCheck());
        assertEquals(List.of(view), ev.recentChecks());

        // Failure messages on events: the leaking one is dropped, the other kept.
        EventView event = filter.event(Viewer.ANONYMOUS, ep,
                event("website", null, "connect to secret.example.com refused", "status 503")).orElseThrow();
        assertNull(event.firstFailureMessage());
        assertEquals("status 503", event.lastFailureMessage());

        // A privileged viewer sees everything unfiltered.
        CheckView full = filter.check(PRIVILEGED, ep, leaky).orElseThrow();
        assertEquals("could not reach Secret.Example.Com", full.message());
        assertEquals(details, full.details());
    }

    @Test
    void viewerWithoutPermissionGetsAnonymousView() {
        Endpoint ep = endpoint("website", Visibility.PUBLIC);
        EndpointStatus st = status("website", record(T0, detailed()));
        assertEquals(filter.endpoint(Viewer.ANONYMOUS, ep, st), filter.endpoint(LOGGED_IN_WITHOUT_PERMISSION, ep, st));
    }

    @Test
    void privilegedSeesEverything() {
        Endpoint ep = endpoint("survival", Visibility.PUBLIC_STATUS);
        CheckRecord ok = record(T0, detailed());
        EndpointView view = filter.endpoint(PRIVILEGED, ep, status("survival", ok)).orElseThrow();
        assertEquals("http", view.type());
        assertEquals(Visibility.PUBLIC_STATUS, view.visibility());
        assertEquals(ep.targets(), view.targets());
        assertEquals(List.of("ops"), view.notifyChannels());
        assertEquals(List.of(30, 60), view.reminders());
        assertEquals(0, view.reminderRepeatMinutes());
        assertEquals(new CheckView(T0, true, Duration.ofMillis(84), "status 200", detailed().details()),
                view.lastCheck());

        EventView ev = filter.event(PRIVILEGED, ep, event("survival", "Renamed", "a", "b")).orElseThrow();
        assertEquals("Renamed", ev.customTitle());
        assertEquals("a", ev.firstFailureMessage());
        assertEquals(Boolean.TRUE, ev.endedUnobserved());
        assertEquals(Boolean.FALSE, ev.endedByConfigChange());
        assertEquals("m1", ev.maintenanceId());
        assertEquals(List.of(EventNote.Visibility.PUBLIC, EventNote.Visibility.PRIVATE),
                ev.notes().stream().map(NoteView::visibility).toList());
    }

    @Test
    void noStatusYetIsUnknown() {
        EndpointView view = filter.endpoint(Viewer.ANONYMOUS, endpoint("w", Visibility.PUBLIC), null).orElseThrow();
        assertEquals(State.UNKNOWN, view.state());
        assertNull(view.since());
        assertNull(view.lastCheck());
        assertEquals(List.of(), view.recentChecks());
    }

    @Test
    void eventOfAnotherEndpointRejected() {
        assertThrows(IllegalArgumentException.class, () -> filter.event(PRIVILEGED,
                endpoint("a", Visibility.PUBLIC), event("b", null, "x", "y")));
    }
}
