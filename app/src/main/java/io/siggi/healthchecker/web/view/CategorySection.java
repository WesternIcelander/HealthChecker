package io.siggi.healthchecker.web.view;

import java.util.List;

/**
 * One category as shown to a viewer: its heading and the services the viewer can see in it,
 * in layout order. Only built when at least one service is visible.
 *
 * @param id          category id, {@link io.siggi.healthchecker.web.layout.SavedLayout#OTHER_ID}
 *                    for "Other", or {@code null} for the flat list
 * @param name        heading text, or {@code null} for the flat list (no heading)
 * @param description optional, {@code null} if not set
 */
public record CategorySection(String id, String name, String description, List<EndpointView> services,
                              CategorySummary summary) {
    public CategorySection {
        services = List.copyOf(services);
    }

    /** Whether a heading is shown (false only for the flat list when no categories exist). */
    public boolean hasHeading() {
        return name != null;
    }
}
