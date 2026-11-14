package io.siggi.healthchecker.web.view;

import io.siggi.healthchecker.schedule.Endpoint;
import io.siggi.healthchecker.status.EndpointStatus;
import io.siggi.healthchecker.web.layout.ResolvedLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Groups the services a viewer can see into the sections of the main page, following the
 * effective layout. A category without any service the viewer can see is left out, so a
 * category of only private services is invisible to anonymous visitors. When no categories
 * exist the result is a single section without a heading.
 */
public final class CategoryGrouping {
    /** Heading of the implicit category for services without one. */
    public static final String OTHER_NAME = "Other";

    private final ViewFilter filter;

    public CategoryGrouping(ViewFilter filter) {
        this.filter = filter;
    }

    /**
     * @param endpoints looks up a live endpoint by id (e.g. {@code EndpointRegistry::get});
     *                  ids it doesn't know are skipped
     * @param statuses  looks up an endpoint's live status (e.g. {@code StatusRegistry::status})
     */
    public List<CategorySection> group(Viewer viewer, ResolvedLayout layout,
                                       Function<String, Optional<Endpoint>> endpoints,
                                       Function<String, Optional<EndpointStatus>> statuses) {
        List<CategorySection> sections = new ArrayList<>();
        for (ResolvedLayout.Category category : layout.categories()) {
            List<EndpointView> views = new ArrayList<>();
            int down = 0;
            int unknown = 0;
            for (String id : category.endpointIds()) {
                Optional<Endpoint> endpoint = endpoints.apply(id);
                if (endpoint.isEmpty()) {
                    continue;
                }
                Optional<EndpointView> view = filter.endpoint(viewer, endpoint.get(), statuses.apply(id).orElse(null));
                if (view.isEmpty()) {
                    continue;
                }
                views.add(view.get());
                switch (view.get().state()) {
                    case DOWN -> down++;
                    case UNKNOWN -> unknown++;
                    case UP -> {
                    }
                }
            }
            if (views.isEmpty()) {
                continue;
            }
            CategorySummary summary = new CategorySummary(views.size(), down, unknown);
            if (layout.flat()) {
                sections.add(new CategorySection(null, null, null, views, summary));
            } else if (category.isOther()) {
                sections.add(new CategorySection(category.id(), OTHER_NAME, null, views, summary));
            } else {
                sections.add(new CategorySection(category.id(), category.config().name(),
                        category.config().description(), views, summary));
            }
        }
        return sections;
    }
}
