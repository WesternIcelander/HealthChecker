package io.siggi.healthchecker.history;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Uptime of an endpoint over the rolling last 24 hours, the rolling last 60 × 24 hours,
 * and single calendar days in the configured timezone, computed exactly from the
 * {@link HistoryStore}'s coverage periods and downtime events:
 *
 * <pre>uptime(window) = 1 − (counted downtime ∩ coverage ∩ window) / (coverage ∩ window)</pre>
 *
 * <p>Counted downtime is every {@code outage} event, plus {@code maintenance} events when
 * maintenance counts as downtime. An ongoing event lasts until now. While an endpoint is
 * monitored, its current coverage period runs until now too (the heartbeat only writes
 * it once a minute). Overlapping events are merged, so no time counts twice.
 *
 * <p>Day summaries are cached per endpoint once the day is over (with a few minutes'
 * margin, since a check that started before midnight is recorded after it). The cache of
 * an endpoint is dropped whenever one of its events is opened or closed, or on
 * {@link #invalidate(String)}.
 *
 * <p>Thread-safe.
 */
public final class UptimeCalculator implements AutoCloseable {
    public static final Duration DAY_WINDOW = Duration.ofHours(24);
    public static final Duration SIXTY_DAY_WINDOW = Duration.ofDays(60);
    /** How long after a day ends before its summary is cached. */
    static final Duration CACHE_SETTLE = Duration.ofMinutes(10);

    private final HistoryStore history;
    private final ZoneId zone;
    private final boolean maintenanceCountsAsDowntime;
    private final Clock clock;
    private final Map<String, EndpointCache> caches = new ConcurrentHashMap<>();
    private final Consumer<HistoryEvent> listener = e -> invalidate(e.endpointId());
    private final AtomicLong computations = new AtomicLong();

    /**
     * @param zone                        where calendar days start and end
     * @param maintenanceCountsAsDowntime {@code maintenance.countsAsDowntime} from the config
     */
    public UptimeCalculator(HistoryStore history, ZoneId zone, boolean maintenanceCountsAsDowntime, Clock clock) {
        this.history = Objects.requireNonNull(history, "history");
        this.zone = Objects.requireNonNull(zone, "zone");
        this.maintenanceCountsAsDowntime = maintenanceCountsAsDowntime;
        this.clock = Objects.requireNonNull(clock, "clock");
        history.addListener(listener);
    }

    public ZoneId zone() {
        return zone;
    }

    /** Today's date in the configured timezone. */
    public LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), zone);
    }

    /** Uptime over the last 24 hours up to now. */
    public Uptime last24Hours(String endpointId) {
        Instant now = clock.instant();
        return window(endpointId, now.minus(DAY_WINDOW), now);
    }

    /** Uptime over the last 60 × 24 hours up to now. */
    public Uptime last60Days(String endpointId) {
        Instant now = clock.instant();
        return window(endpointId, now.minus(SIXTY_DAY_WINDOW), now);
    }

    /** Uptime within {@code [from, to)}; the part after now has no data. */
    public Uptime window(String endpointId, Instant from, Instant to) {
        Objects.requireNonNull(endpointId, "endpointId");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Instant now = clock.instant();
        return compute(snapshot(endpointId, now), from, to.isAfter(now) ? now : to).uptime;
    }

    /** Summary of one calendar day in the configured timezone (23 or 25 hours on DST changes). */
    public DaySummary day(String endpointId, LocalDate date) {
        return days(endpointId, date, date).getFirst();
    }

    /** Summaries of every day from {@code from} to {@code to}, both inclusive, oldest first. */
    public List<DaySummary> days(String endpointId, LocalDate from, LocalDate to) {
        Objects.requireNonNull(endpointId, "endpointId");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (to.isBefore(from)) {
            throw new IllegalArgumentException(to + " is before " + from);
        }
        EndpointCache cache = caches.computeIfAbsent(endpointId, id -> new EndpointCache());
        long generation = cache.generation.get();
        Instant now = clock.instant();
        Snapshot snapshot = null;
        List<DaySummary> result = new ArrayList<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            CachedDay cached = cache.days.get(date);
            if (cached != null && cached.generation == generation) {
                result.add(cached.summary);
                continue;
            }
            if (snapshot == null) {
                snapshot = snapshot(endpointId, now);
            }
            Instant dayStart = date.atStartOfDay(zone).toInstant();
            Instant dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant();
            Result r = compute(snapshot, dayStart, dayEnd.isAfter(now) ? now : dayEnd);
            DaySummary summary = new DaySummary(date, r.uptime, r.events, r.maintenanceEvents);
            if (!dayEnd.plus(CACHE_SETTLE).isAfter(now)) {
                cache.days.put(date, new CachedDay(generation, summary));
            }
            result.add(summary);
        }
        return List.copyOf(result);
    }

    /** Drops the cached day summaries of an endpoint (its history changed). */
    public void invalidate(String endpointId) {
        EndpointCache cache = caches.get(endpointId);
        if (cache != null) {
            cache.generation.incrementAndGet();
            cache.days.clear();
        }
    }

    /** Stops listening to the history store. */
    @Override
    public void close() {
        history.removeListener(listener);
    }

    /** Number of window computations so far (each uncached day, and each rolling window). */
    long computations() {
        return computations.get();
    }

    // ---- computation ----

    private record Snapshot(List<Interval> coverage, List<HistoryEvent> events, Instant now) {
    }

    private record Interval(Instant start, Instant end) {
    }

    private record Result(Uptime uptime, int events, int maintenanceEvents) {
    }

    private record CachedDay(long generation, DaySummary summary) {
    }

    private static final class EndpointCache {
        final AtomicLong generation = new AtomicLong();
        final Map<LocalDate, CachedDay> days = new ConcurrentHashMap<>();
    }

    private Snapshot snapshot(String endpointId, Instant now) {
        boolean monitored = history.monitored(endpointId);
        List<CoveragePeriod> periods = history.coverage(endpointId);
        List<Interval> coverage = new ArrayList<>(periods.size());
        for (int i = 0; i < periods.size(); i++) {
            CoveragePeriod p = periods.get(i);
            Instant end = p.end();
            if (monitored && i == periods.size() - 1 && now.isAfter(end)) {
                end = now;
            }
            coverage.add(new Interval(p.start(), end));
        }
        return new Snapshot(merge(coverage), history.events(endpointId), now);
    }

    private Result compute(Snapshot s, Instant from, Instant to) {
        computations.incrementAndGet();
        if (!to.isAfter(from)) {
            return new Result(Uptime.NONE, 0, 0);
        }
        List<Interval> coverage = clip(s.coverage, from, to);
        List<Interval> downtime = new ArrayList<>();
        int events = 0;
        int maintenanceEvents = 0;
        for (HistoryEvent e : s.events) {
            Instant end = e.end() != null ? e.end() : (s.now.isAfter(e.start()) ? s.now : e.start());
            if (!overlaps(e.start(), end, from, to)) {
                continue;
            }
            if (counts(e)) {
                events++;
                downtime.add(new Interval(e.start(), end));
            } else {
                maintenanceEvents++;
            }
        }
        downtime = clip(merge(downtime), from, to);
        Duration covered = total(coverage);
        Duration down = intersection(coverage, downtime);
        return new Result(new Uptime(covered, down), events, maintenanceEvents);
    }

    private boolean counts(HistoryEvent e) {
        return e.kind() == HistoryEvent.Kind.OUTAGE || maintenanceCountsAsDowntime;
    }

    /** Whether {@code [start, end]} touches {@code [from, to)}; an instantaneous event counts where it is. */
    private static boolean overlaps(Instant start, Instant end, Instant from, Instant to) {
        if (start.equals(end)) {
            return !start.isBefore(from) && start.isBefore(to);
        }
        return start.isBefore(to) && end.isAfter(from);
    }

    /** Sorts and merges overlapping or touching intervals; drops empty ones. */
    private static List<Interval> merge(List<Interval> intervals) {
        List<Interval> sorted = new ArrayList<>(intervals);
        sorted.sort(Comparator.comparing(Interval::start));
        List<Interval> merged = new ArrayList<>();
        for (Interval i : sorted) {
            if (!i.end.isAfter(i.start)) {
                continue;
            }
            if (!merged.isEmpty() && !i.start.isAfter(merged.getLast().end)) {
                Interval last = merged.getLast();
                if (i.end.isAfter(last.end)) {
                    merged.set(merged.size() - 1, new Interval(last.start, i.end));
                }
            } else {
                merged.add(i);
            }
        }
        return merged;
    }

    /** Clips sorted, disjoint intervals to {@code [from, to)}. */
    private static List<Interval> clip(List<Interval> intervals, Instant from, Instant to) {
        List<Interval> clipped = new ArrayList<>();
        for (Interval i : intervals) {
            Instant start = i.start.isBefore(from) ? from : i.start;
            Instant end = i.end.isAfter(to) ? to : i.end;
            if (end.isAfter(start)) {
                clipped.add(new Interval(start, end));
            }
        }
        return clipped;
    }

    private static Duration total(List<Interval> intervals) {
        Duration sum = Duration.ZERO;
        for (Interval i : intervals) {
            sum = sum.plus(Duration.between(i.start, i.end));
        }
        return sum;
    }

    /** Total length of the intersection of two sorted, disjoint interval lists. */
    private static Duration intersection(List<Interval> a, List<Interval> b) {
        Duration sum = Duration.ZERO;
        int i = 0;
        int j = 0;
        while (i < a.size() && j < b.size()) {
            Interval x = a.get(i);
            Interval y = b.get(j);
            Instant start = x.start.isAfter(y.start) ? x.start : y.start;
            Instant end = x.end.isBefore(y.end) ? x.end : y.end;
            if (end.isAfter(start)) {
                sum = sum.plus(Duration.between(start, end));
            }
            if (x.end.isBefore(y.end)) {
                i++;
            } else {
                j++;
            }
        }
        return sum;
    }
}
