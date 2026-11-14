package io.siggi.healthchecker.web.layout;

import java.util.List;
import java.util.Objects;

/**
 * The dashboard layout as stored in {@code data/layout.json}: category order and the
 * ordered service ids in each category. It may mention ids that no longer exist and may
 * miss ones added since; {@link ResolvedLayout#resolve} merges it with the current
 * categories and endpoints.
 *
 * @param version incremented on every save, for conflict detection
 */
public record SavedLayout(int version, List<Category> categories) {
    /** The id the implicit "Other" category has in a saved layout. */
    public static final String OTHER_ID = "_other";

    public SavedLayout {
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
        categories = List.copyOf(categories);
    }

    /**
     * One category in the saved layout.
     *
     * @param id       a category id, or {@link #OTHER_ID}
     * @param services endpoint ids in display order
     */
    public record Category(String id, List<String> services) {
        public Category {
            Objects.requireNonNull(id, "id");
            services = List.copyOf(services);
        }
    }
}
