package io.siggi.healthchecker.web.view;

import com.google.gson.JsonObject;
import io.siggi.healthchecker.api.CheckResult;
import io.siggi.healthchecker.config.CategoryConfig;
import io.siggi.healthchecker.config.EndpointDefinition;
import io.siggi.healthchecker.config.Visibility;
import io.siggi.healthchecker.schedule.Endpoint;
import io.siggi.healthchecker.status.EndpointStatus;
import io.siggi.healthchecker.status.State;
import io.siggi.healthchecker.web.layout.ResolvedLayout;
import io.siggi.healthchecker.web.layout.SavedLayout;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CategoryGroupingTest {
    private static final Instant T0 = Instant.parse("2026-11-13T09:00:00Z");
    private static final Viewer PRIVILEGED = new Viewer("alice", true);

    private final CategoryGrouping grouping = new CategoryGrouping(new ViewFilter());
    private final Map<String, Endpoint> endpoints = new LinkedHashMap<>();
    private final Map<String, EndpointStatus> statuses = new HashMap<>();

    private EndpointDefinition add(String id, String category, Visibility visibility, State state) {
        EndpointDefinition def = new EndpointDefinition(id, "Name " + id, "http", category, visibility, 60, 5000, 1,
                List.of(), List.of(), 0, new JsonObject());
        endpoints.put(id, new Endpoint(def, List.of(), timeout -> CheckResult.ok("ok")));
        if (state != State.UNKNOWN) {
            statuses.put(id, new EndpointStatus(id, state, T0, 1, 0, null, null, null, List.of()));
        }
        return def;
    }

    private List<CategorySection> group(Viewer viewer, ResolvedLayout layout) {
        return grouping.group(viewer, layout, id -> Optional.ofNullable(endpoints.get(id)),
                id -> Optional.ofNullable(statuses.get(id)));
    }

    private static List<String> ids(CategorySection section) {
        return section.services().stream().map(EndpointView::id).toList();
    }

    @Test
    void categoryWithOnlyPrivateServicesHiddenFromAnonymous() {
        List<CategoryConfig> categories = List.of(
                new CategoryConfig("web", "Websites", null),
                new CategoryConfig("internal", "Internal", "Staff only"));
        List<EndpointDefinition> defs = List.of(
                add("website", "web", Visibility.PUBLIC, State.UP),
                add("intranet", "web", Visibility.PRIVATE, State.DOWN),
                add("admin", "internal", Visibility.PRIVATE, State.UP),
                add("loose", null, Visibility.PUBLIC_STATUS, State.UP));
        ResolvedLayout layout = ResolvedLayout.resolve(null, categories, defs);

        List<CategorySection> anon = group(Viewer.ANONYMOUS, layout);
        assertEquals(List.of("web", "_other"), anon.stream().map(CategorySection::id).toList());
        assertEquals(List.of("website"), ids(anon.get(0)));
        assertEquals("Other", anon.get(1).name());
        assertTrue(anon.get(1).hasHeading());
        // A logged-in user without view.private sees the same.
        assertEquals(anon, group(new Viewer("bob", false), layout));

        List<CategorySection> privileged = group(PRIVILEGED, layout);
        assertEquals(List.of("web", "internal", "_other"), privileged.stream().map(CategorySection::id).toList());
        assertEquals(List.of("website", "intranet"), ids(privileged.get(0)));
        assertEquals("Internal", privileged.get(1).name());
        assertEquals("Staff only", privileged.get(1).description());
    }

    @Test
    void emptyCategoriesAndUnknownEndpointsSkipped() {
        List<CategoryConfig> categories = List.of(new CategoryConfig("empty", "Empty", null),
                new CategoryConfig("web", "Websites", null));
        EndpointDefinition website = add("website", "web", Visibility.PUBLIC, State.UP);
        EndpointDefinition ghost = new EndpointDefinition("ghost", "Ghost", "http", "web", Visibility.PUBLIC, 60,
                5000, 1, List.of(), List.of(), 0, new JsonObject());
        ResolvedLayout layout = ResolvedLayout.resolve(null, categories, List.of(website, ghost));
        List<CategorySection> sections = group(PRIVILEGED, layout);
        assertEquals(1, sections.size());
        assertEquals(List.of("website"), ids(sections.get(0)));
    }

    @Test
    void flatListHasNoHeading() {
        List<EndpointDefinition> defs = List.of(
                add("a", null, Visibility.PUBLIC, State.UP),
                add("b", null, Visibility.PRIVATE, State.UP),
                add("c", null, Visibility.PUBLIC, State.DOWN));
        List<CategorySection> sections = group(Viewer.ANONYMOUS, ResolvedLayout.resolve(null, List.of(), defs));
        assertEquals(1, sections.size());
        CategorySection flat = sections.get(0);
        assertFalse(flat.hasHeading());
        assertNull(flat.id());
        assertNull(flat.name());
        assertEquals(List.of("a", "c"), ids(flat));

        assertEquals(List.of(), group(Viewer.ANONYMOUS,
                ResolvedLayout.resolve(null, List.of(), List.of(defs.get(1)))));
    }

    @Test
    void headingSummaryCountsOnlyVisibleServices() {
        List<CategoryConfig> categories = List.of(new CategoryConfig("web", "Websites", null));
        List<EndpointDefinition> defs = List.of(
                add("w1", "web", Visibility.PUBLIC, State.UP),
                add("w2", "web", Visibility.PUBLIC, State.DOWN),
                add("w3", "web", Visibility.PUBLIC_STATUS, State.UP),
                add("w4", "web", Visibility.PUBLIC, State.UP),
                add("w5", "web", Visibility.PRIVATE, State.DOWN));
        ResolvedLayout layout = ResolvedLayout.resolve(null, categories, defs);

        CategorySection anon = group(Viewer.ANONYMOUS, layout).get(0);
        assertEquals(new CategorySummary(4, 1, 0), anon.summary());
        assertEquals("1 of 4 down", anon.summary().text());

        CategorySection privileged = group(PRIVILEGED, layout).get(0);
        assertEquals("2 of 5 down", privileged.summary().text());

        statuses.put("w2", new EndpointStatus("w2", State.UP, T0, 1, 0, null, null, null, List.of()));
        assertEquals("All operational", group(Viewer.ANONYMOUS, layout).get(0).summary().text());

        statuses.remove("w4");
        assertEquals("1 of 4 not checked yet", group(Viewer.ANONYMOUS, layout).get(0).summary().text());
    }

    @Test
    void savedLayoutOrderIsFollowed() {
        List<CategoryConfig> categories = List.of(new CategoryConfig("web", "Websites", null),
                new CategoryConfig("mc", "Minecraft", null));
        List<EndpointDefinition> defs = List.of(
                add("website", "web", Visibility.PUBLIC, State.UP),
                add("survival", "mc", Visibility.PUBLIC, State.UP),
                add("creative", "mc", Visibility.PUBLIC, State.UP));
        var saved = new SavedLayout(1, List.of(
                new SavedLayout.Category("mc", List.of("creative", "website")),
                new SavedLayout.Category("web", List.of())));
        List<CategorySection> sections = group(Viewer.ANONYMOUS, ResolvedLayout.resolve(saved, categories, defs));
        assertEquals(1, sections.size());
        assertEquals("mc", sections.get(0).id());
        assertEquals(List.of("creative", "website", "survival"), ids(sections.get(0)));
    }
}
