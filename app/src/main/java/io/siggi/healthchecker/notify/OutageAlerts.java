package io.siggi.healthchecker.notify;

import io.siggi.healthchecker.config.EndpointDefinition;
import io.siggi.healthchecker.status.State;
import io.siggi.healthchecker.status.StateChange;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Down, recovered and reminder notifications for endpoint state changes, kept quiet during maintenance.
 *
 * <p>Outside maintenance, a change to DOWN sends a down notification and starts reminders, and DOWN → UP
 * sends a recovery (only for an outage a down notification was sent for) and cancels them. While an
 * endpoint is in a maintenance window ({@link #maintenanceChanged}), changes send nothing: entering a window
 * cancels pending reminders, and a recovery inside it is silent. If the endpoint is still DOWN when it
 * leaves the window, a down notification goes out then, with the outage starting at the window's end, and
 * reminders count from there.
 *
 * <p>Thread-safe.
 */
public final class OutageAlerts {
    /** Where down and recovered notifications go, normally {@link NotificationDispatcher#notify}. */
    @FunctionalInterface
    public interface Sink {
        void send(EndpointDefinition endpoint, Notification notification);
    }

    private final Sink sink;
    private final ReminderScheduler reminders;
    private final ReminderScheduler.Source targets;
    private final Predicate<String> checkDown;
    /** Read without the lock (by reminder lookups, which run under the reminder scheduler's lock). */
    private final Set<String> inMaintenance = ConcurrentHashMap.newKeySet();
    /** Endpoint → start of the outage a down notification was sent for. */
    private final Map<String, Instant> alerted = new HashMap<>();

    /**
     * @param targets   the endpoint's definition, latest failure message and a link to its open event
     * @param checkDown whether the endpoint's checks currently say DOWN (also during maintenance)
     */
    public OutageAlerts(Sink sink, ReminderScheduler reminders, ReminderScheduler.Source targets,
                        Predicate<String> checkDown) {
        this.sink = Objects.requireNonNull(sink, "sink");
        this.reminders = Objects.requireNonNull(reminders, "reminders");
        this.targets = Objects.requireNonNull(targets, "targets");
        this.checkDown = Objects.requireNonNull(checkDown, "checkDown");
    }

    /**
     * Handles a state change.
     *
     * @param endpoint the endpoint's current definition, or {@code null} if it no longer exists (nothing is
     *                 sent then; reminders still follow the change)
     * @param link     link for the notification (the event opened or closed), or {@code null}
     */
    public synchronized void onStateChange(EndpointDefinition endpoint, StateChange change, String link) {
        String id = change.endpointId();
        boolean quiet = inMaintenance.contains(id);
        if (change.to() == State.DOWN) {
            // Already announced when it left a window still down (the change raced the window's end).
            if (quiet || alerted.containsKey(id)) {
                return;
            }
            if (endpoint != null) {
                Optional<Notification> n = Notification.forChange(endpoint, change, link);
                if (n.isPresent()) {
                    sink.send(endpoint, n.get());
                    alerted.put(id, n.get().outageStart());
                }
            }
            reminders.onStateChange(change);
            return;
        }
        Instant since = alerted.remove(id);
        if (!quiet && since != null && endpoint != null && change.from() == State.DOWN && change.to() == State.UP) {
            sink.send(endpoint, new Notification(Notification.Kind.RECOVERED, id, endpoint.name(), change.time(),
                    since, null, null, link));
        }
        reminders.onStateChange(change);
    }

    /**
     * The endpoint moved from window {@code fromWindowId} to {@code toWindowId} (either {@code null}) at
     * {@code at}. Entering cancels its reminders; leaving while its checks say DOWN sends a down
     * notification for an outage starting at {@code at} and starts reminders from then.
     */
    public synchronized void maintenanceChanged(String endpointId, String fromWindowId, String toWindowId,
                                                Instant at) {
        Objects.requireNonNull(endpointId, "endpointId");
        Objects.requireNonNull(at, "at");
        if (toWindowId != null) {
            if (inMaintenance.add(endpointId)) {
                alerted.remove(endpointId);
                reminders.cancel(endpointId);
            }
            return;
        }
        if (!inMaintenance.remove(endpointId) || !checkDown.test(endpointId)) {
            return;
        }
        Optional<ReminderScheduler.Target> target = targets.lookup(endpointId);
        if (target.isEmpty()) {
            return;
        }
        EndpointDefinition def = target.get().endpoint();
        sink.send(def, new Notification(Notification.Kind.DOWN, endpointId, def.name(), at, at,
                target.get().failureMessage(), null, target.get().link()));
        alerted.put(endpointId, at);
        reminders.start(endpointId, at);
    }

    /** Whether the endpoint is in a maintenance window, as last told. Doesn't take the lock. */
    public boolean inMaintenance(String endpointId) {
        return inMaintenance.contains(endpointId);
    }

    /** Forgets the endpoint's outage (it was removed or its check replaced) and cancels its reminders. */
    public synchronized void forget(String endpointId) {
        alerted.remove(endpointId);
        reminders.cancel(endpointId);
    }
}
