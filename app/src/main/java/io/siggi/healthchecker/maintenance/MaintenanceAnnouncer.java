package io.siggi.healthchecker.maintenance;

import io.siggi.healthchecker.notify.Notification;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Sends maintenance announcements to each window's own {@code notifyChannels} (not the endpoints'
 * {@code notify} routing): when a window is created, {@code announceBeforeHours} before it starts, when it
 * starts, when it ends (also by "End now") and when it's cancelled. Runs on every
 * {@link MaintenanceScheduler} tick.
 *
 * <p>Each announcement goes out once, for things that happen while the app runs: an event is announced when
 * a tick finds its time at or after the announcer was created and at most {@link #LOOKBACK} ago. What
 * happened while the app was down isn't announced afterwards. A series is announced as created and
 * cancelled as a whole; its occurrences get their own "upcoming", "started" and "ended". A window created
 * and cancelled between two ticks is announced not at all; an "upcoming" announcement is left out when the
 * window was created after that moment (the "created" one says it all).
 *
 * <p>Thread-safe.
 */
public final class MaintenanceAnnouncer implements MaintenanceScheduler.Listener {
    /** How far back a tick looks for events it hasn't announced yet. */
    public static final Duration LOOKBACK = Duration.ofHours(1);

    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("EEE d MMM yyyy HH:mm", Locale.ENGLISH);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH);

    /** What is announced. */
    public enum Event { CREATED, UPCOMING, STARTED, ENDED, CANCELLED }

    /** Where announcements go, normally {@code NotificationDispatcher.send}. */
    @FunctionalInterface
    public interface Sink {
        void announce(List<String> channels, Notification notification);
    }

    private final MaintenanceStore store;
    private final Duration announceBefore;
    private final ZoneId zone;
    private final Function<MaintenanceWindow, List<String>> affectedNames;
    private final Function<String, String> link;
    private final Sink sink;
    private final Instant since;
    /** "{window id}/{event}" → the event's time, for what has been announced (or decided against). */
    private final Map<String, Instant> announced = new HashMap<>();

    /**
     * @param announceBefore how long before a start the "upcoming" announcement goes out; zero for none
     * @param affectedNames  the names of the services a window covers now
     * @param link           an absolute link to a window's page by id, or {@code null}
     * @param since          events before this aren't announced (normally the startup time)
     */
    public MaintenanceAnnouncer(MaintenanceStore store, Duration announceBefore, ZoneId zone,
                                Function<MaintenanceWindow, List<String>> affectedNames,
                                Function<String, String> link, Sink sink, Instant since) {
        this.store = Objects.requireNonNull(store, "store");
        this.announceBefore = Objects.requireNonNull(announceBefore, "announceBefore");
        this.zone = Objects.requireNonNull(zone, "zone");
        this.affectedNames = Objects.requireNonNull(affectedNames, "affectedNames");
        this.link = Objects.requireNonNull(link, "link");
        this.sink = Objects.requireNonNull(sink, "sink");
        this.since = Objects.requireNonNull(since, "since");
    }

    @Override
    public synchronized void ticked(Instant now) {
        Instant from = now.minus(LOOKBACK);
        announced.values().removeIf(t -> t.isBefore(from));
        for (MaintenanceWindow w : store.windows()) {
            if (w.seriesId() != null) continue; // occurrences are created by the clock, not by anyone
            if (w.cancelledAt() != null && due(w.cancelledAt(), now)) {
                if (due(w.createdAt(), now) && !announced.containsKey(key(w, Event.CREATED))) {
                    // Created and cancelled before anything was said: say nothing.
                    announced.put(key(w, Event.CREATED), w.createdAt());
                    announced.put(key(w, Event.CANCELLED), w.cancelledAt());
                    continue;
                }
                once(w, Event.CANCELLED, w.cancelledAt());
            } else if (w.cancelledAt() == null && due(w.createdAt(), now)) {
                once(w, Event.CREATED, w.createdAt());
            }
        }
        for (MaintenanceWindow w : store.occurrences(from, now.plus(announceBefore).plusNanos(1))) {
            if (w.cancelledAt() != null || w.isSeries()) continue;
            if (announceBefore.isPositive()) {
                Instant t = w.start().minus(announceBefore);
                if (due(t, now) && t.isAfter(w.createdAt()) && w.start().isAfter(now)) {
                    once(w, Event.UPCOMING, t);
                }
            }
            if (due(w.start(), now)) once(w, Event.STARTED, w.start());
            if (due(w.end(), now)) once(w, Event.ENDED, w.end());
        }
    }

    private boolean due(Instant t, Instant now) {
        return !t.isBefore(since) && !t.isBefore(now.minus(LOOKBACK)) && !t.isAfter(now);
    }

    private static String key(MaintenanceWindow w, Event event) {
        return w.id() + "/" + event;
    }

    private void once(MaintenanceWindow w, Event event, Instant time) {
        if (announced.putIfAbsent(key(w, event), time) != null || w.notifyChannels().isEmpty()) {
            return;
        }
        sink.announce(w.notifyChannels(), announcement(w, event, time));
    }

    /** The announcement for {@code event} of window {@code w}, happening at {@code time}. */
    Notification announcement(MaintenanceWindow w, Event event, Instant time) {
        String headline = switch (event) {
            case CREATED -> w.isSeries() ? "Recurring maintenance scheduled: " : "Maintenance scheduled: ";
            case UPCOMING -> "Upcoming maintenance: ";
            case STARTED -> "Maintenance started: ";
            case ENDED -> "Maintenance ended: ";
            case CANCELLED -> "Maintenance cancelled: ";
        } + w.title();
        List<String> lines = new ArrayList<>();
        switch (event) {
            case CREATED -> lines.add((w.isSeries() ? "First: " : "") + range(w.start(), w.end()));
            case UPCOMING -> lines.add(range(w.start(), w.end()));
            case STARTED -> lines.add("Planned until " + DATE_TIME.format(w.end().atZone(zone)) + " (" + zone + ")");
            case ENDED -> lines.add("Ran " + range(w.start(), w.end()));
            case CANCELLED -> lines.add(w.isSeries() ? "All future occurrences are cancelled."
                    : "Was planned for " + range(w.start(), w.end()));
        }
        List<String> names = affectedNames.apply(w);
        if (!names.isEmpty()) {
            lines.add("Affects: " + String.join(", ", names));
        }
        if ((event == Event.CREATED || event == Event.UPCOMING) && w.description() != null
                && !w.description().isBlank()) {
            lines.add(w.description());
        }
        return new Notification(Notification.Kind.ANNOUNCEMENT, null, headline, time, null, null,
                String.join("\n", lines), link.apply(w.id()));
    }

    /** "Sat 21 Nov 2026 03:00–04:00 (Atlantic/Reykjavik)", with the end's date when it differs. */
    private String range(Instant start, Instant end) {
        ZonedDateTime s = start.atZone(zone);
        ZonedDateTime e = end.atZone(zone);
        String to = s.toLocalDate().equals(e.toLocalDate()) ? "–" + TIME.format(e) : " – " + DATE_TIME.format(e);
        return DATE_TIME.format(s) + to + " (" + zone + ")";
    }
}
