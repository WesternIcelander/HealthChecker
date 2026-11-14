package io.siggi.healthchecker.web.page;

import io.siggi.healthchecker.maintenance.MaintenanceStore;
import io.siggi.healthchecker.maintenance.MaintenanceWindow;
import io.siggi.healthchecker.schedule.Endpoint;
import io.siggi.healthchecker.web.layout.ResolvedLayout;
import io.siggi.healthchecker.web.site.Site;
import io.siggi.healthchecker.web.view.ViewFilter;
import io.siggi.healthchecker.web.view.Viewer;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Which maintenance windows a viewer may see, and which of their affected services. Used by the main
 * page, the service pages, the maintenance pages and the JSON API alike.
 *
 * <p>A window is shown only on and for affected services the viewer can see within the request's site
 * scope: its affected list holds just those (in layout order), and a window without any is hidden
 * entirely (as if it didn't exist). Categories cover the services in them in the current effective
 * layout. The one exception: viewers with {@code view.private} also see windows that affect no existing
 * service at all (e.g. one for an empty category), so they can still be edited or cancelled.
 */
public final class MaintenanceViews {
    private final MaintenanceStore store;
    private final Supplier<ResolvedLayout> layout;
    private final Function<String, Optional<Endpoint>> endpoints;
    private final Clock clock;
    private final int upcomingDays;
    private final ViewFilter filter = new ViewFilter();

    /** One affected service as shown. */
    public record Affected(String id, String name) {
    }

    /**
     * A window as a viewer may see it.
     *
     * @param affected the affected services the viewer can see in scope, in layout order
     */
    public record Visible(MaintenanceWindow window, List<Affected> affected) {
        public Visible {
            affected = List.copyOf(affected);
        }

        public boolean affects(String endpointId) {
            return affected.stream().anyMatch(a -> a.id().equals(endpointId));
        }
    }

    /**
     * @param layout       the current effective (unscoped) layout
     * @param endpoints    live endpoint by id
     * @param upcomingDays how far ahead "upcoming maintenance" looks ({@code maintenance.upcomingDays})
     */
    public MaintenanceViews(MaintenanceStore store, Supplier<ResolvedLayout> layout,
                            Function<String, Optional<Endpoint>> endpoints, Clock clock, int upcomingDays) {
        this.store = Objects.requireNonNull(store, "store");
        this.layout = Objects.requireNonNull(layout, "layout");
        this.endpoints = Objects.requireNonNull(endpoints, "endpoints");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.upcomingDays = upcomingDays;
    }

    public MaintenanceStore store() {
        return store;
    }

    public ZoneId zone() {
        return store.zone();
    }

    Instant now() {
        return clock.instant();
    }

    int upcomingDays() {
        return upcomingDays;
    }

    ResolvedLayout layout() {
        return layout.get();
    }

    Optional<Endpoint> endpoint(String id) {
        return endpoints.apply(id);
    }

    /** Endpoint id → its category id in {@code layout} ({@code null} for "Other"). */
    static Map<String, String> categories(ResolvedLayout layout) {
        Map<String, String> out = new HashMap<>();
        for (ResolvedLayout.Category c : layout.categories()) {
            for (String id : c.endpointIds()) {
                out.put(id, c.isOther() ? null : c.id());
            }
        }
        return out;
    }

    /** A snapshot of the layout, to check several windows against the same one. */
    final class Context {
        final Viewer viewer;
        final Site site;
        final ResolvedLayout full;
        final ResolvedLayout scoped;
        final Map<String, String> categories;

        Context(Viewer viewer, Site site) {
            this.viewer = viewer;
            this.site = site;
            this.full = layout.get();
            this.scoped = site.scope(full);
            this.categories = categories(full);
        }

        Optional<Visible> visible(MaintenanceWindow w) {
            List<Affected> affected = new ArrayList<>();
            boolean anyExisting = false;
            for (ResolvedLayout.Category c : full.categories()) {
                String categoryId = c.isOther() ? null : c.id();
                for (String id : c.endpointIds()) {
                    if (w.affects(id, categoryId) && endpoints.apply(id).isPresent()) {
                        anyExisting = true;
                        break;
                    }
                }
            }
            for (ResolvedLayout.Category c : scoped.categories()) {
                String categoryId = c.isOther() ? null : c.id();
                for (String id : c.endpointIds()) {
                    if (!w.affects(id, categoryId)) continue;
                    Optional<Endpoint> endpoint = endpoints.apply(id);
                    if (endpoint.isEmpty() || !filter.canSee(viewer, endpoint.get())) continue;
                    affected.add(new Affected(id, endpoint.get().name()));
                }
            }
            if (affected.isEmpty() && !(viewer.viewPrivate() && !anyExisting)) {
                return Optional.empty();
            }
            return Optional.of(new Visible(w, affected));
        }
    }

    Context context(Viewer viewer, Site site) {
        return new Context(viewer, site);
    }

    /** The window as the viewer may see it on this site, or empty if it's hidden from them. */
    public Optional<Visible> visible(Viewer viewer, Site site, MaintenanceWindow window) {
        return new Context(viewer, site).visible(window);
    }

    /**
     * A one-off window, an occurrence (stored or not yet) or a series by id, as the viewer may see it;
     * empty when there's no such window or it's hidden from them.
     */
    public Optional<Visible> find(Viewer viewer, Site site, String id) {
        Optional<MaintenanceWindow> w = store.occurrence(id);
        if (w.isEmpty()) w = store.window(id).filter(MaintenanceWindow::isSeries);
        return w.flatMap(window -> visible(viewer, site, window));
    }

    /** Visible, not cancelled windows and occurrences overlapping {@code [from, to)}, by start. */
    public List<Visible> between(Viewer viewer, Site site, Instant from, Instant to) {
        Context ctx = new Context(viewer, site);
        List<Visible> out = new ArrayList<>();
        for (MaintenanceWindow w : store.occurrences(from, to)) {
            if (w.cancelledAt() != null) continue;
            ctx.visible(w).ifPresent(out::add);
        }
        return out;
    }

    /** Visible windows in progress or starting within {@code maintenance.upcomingDays}, by start. */
    public List<Visible> upcoming(Viewer viewer, Site site) {
        Instant now = clock.instant();
        return between(viewer, site, now, now.plus(Duration.ofDays(upcomingDays)));
    }

    /** {@link #upcoming} for windows affecting one service. */
    public List<Visible> upcoming(Viewer viewer, Site site, String endpointId) {
        return upcoming(viewer, site).stream().filter(v -> v.affects(endpointId)).toList();
    }

    /**
     * The not cancelled windows affecting the endpoint (by its current category) that overlap
     * {@code [from, to)}, by start. The endpoint itself must be visible to whoever sees the result.
     */
    public List<MaintenanceWindow> affecting(String endpointId, Instant from, Instant to) {
        String category = categories(layout.get()).get(endpointId);
        List<MaintenanceWindow> out = new ArrayList<>();
        for (MaintenanceWindow w : store.occurrences(from, to)) {
            if (w.cancelledAt() == null && w.affects(endpointId, category)) out.add(w);
        }
        return out;
    }

    /**
     * The days in {@code [from, to]} (in the configured timezone) on which a not cancelled window
     * affecting the endpoint takes place, at least partly.
     */
    public Set<LocalDate> days(String endpointId, LocalDate from, LocalDate to) {
        ZoneId zone = store.zone();
        Instant start = from.atStartOfDay(zone).toInstant();
        Instant end = to.plusDays(1).atStartOfDay(zone).toInstant();
        Set<LocalDate> out = new HashSet<>();
        for (MaintenanceWindow w : affecting(endpointId, start, end)) {
            LocalDate first = LocalDate.ofInstant(w.start(), zone);
            // The end is exclusive: a window ending at midnight doesn't touch the next day.
            LocalDate last = LocalDate.ofInstant(w.end().minusNanos(1), zone);
            for (LocalDate d = first.isBefore(from) ? from : first; !d.isAfter(last) && !d.isAfter(to); d = d.plusDays(1)) {
                out.add(d);
            }
        }
        return out;
    }
}
