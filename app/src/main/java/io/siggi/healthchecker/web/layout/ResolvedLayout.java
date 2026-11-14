package io.siggi.healthchecker.web.layout;

import io.siggi.healthchecker.config.CategoryConfig;
import io.siggi.healthchecker.config.EndpointDefinition;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The effective dashboard layout: every current category (plus the implicit "Other") in
 * display order, each with the ids of every current endpoint in it, in display order.
 * Visibility isn't applied here; it lists all endpoints.
 *
 * @param flat       no categories exist, so the page is a flat list; {@code categories}
 *                   then holds only "Other", with every endpoint
 * @param categories in display order; "Other" is always included, possibly empty
 */
public record ResolvedLayout(boolean flat, List<Category> categories) {
    public ResolvedLayout {
        categories = List.copyOf(categories);
    }

    /**
     * One category of the effective layout.
     *
     * @param config      the category, or {@code null} for the implicit "Other"
     * @param endpointIds every endpoint in it, in display order
     */
    public record Category(CategoryConfig config, List<String> endpointIds) {
        public Category {
            endpointIds = List.copyOf(endpointIds);
        }

        public boolean isOther() {
            return config == null;
        }

        /** The category id, or {@link SavedLayout#OTHER_ID} for "Other". */
        public String id() {
            return config == null ? SavedLayout.OTHER_ID : config.id();
        }
    }

    /**
     * Merges a saved layout with the current categories and endpoints.
     * <ul>
     *   <li>Without a saved layout: categories in {@code categories} order, then "Other";
     *       endpoints in {@code endpoints} order within their {@code category} (none: "Other").</li>
     *   <li>The saved layout's category order and per-category service order win, and so does
     *       the category it puts a service in.</li>
     *   <li>Categories missing from the saved layout are appended in config order, then "Other"
     *       if it's missing too. Endpoints missing from it are appended to their configured
     *       category, in config order.</li>
     *   <li>Saved ids that no longer exist are ignored; so are repeats (the first one counts).</li>
     *   <li>An endpoint whose category no longer exists goes to "Other".</li>
     * </ul>
     *
     * @param saved      the saved layout, or {@code null} if there is none
     * @param categories all current categories, in config order
     * @param endpoints  all current endpoints, in config order
     */
    public static ResolvedLayout resolve(SavedLayout saved, List<CategoryConfig> categories,
                                         List<EndpointDefinition> endpoints) {
        boolean flat = categories.isEmpty();
        Map<String, CategoryConfig> categoryById = new LinkedHashMap<>();
        for (CategoryConfig category : categories) {
            categoryById.putIfAbsent(category.id(), category);
        }
        Set<String> endpointIds = new HashSet<>();
        for (EndpointDefinition endpoint : endpoints) {
            endpointIds.add(endpoint.id());
        }

        Map<String, List<String>> members = new LinkedHashMap<>();
        Set<String> placed = new HashSet<>();
        if (saved != null) {
            for (SavedLayout.Category category : saved.categories()) {
                String id = category.id();
                boolean known = id.equals(SavedLayout.OTHER_ID) || (!flat && categoryById.containsKey(id));
                if (!known || members.containsKey(id)) {
                    continue;
                }
                List<String> list = new ArrayList<>();
                for (String service : category.services()) {
                    if (endpointIds.contains(service) && placed.add(service)) {
                        list.add(service);
                    }
                }
                members.put(id, list);
            }
        }
        if (!flat) {
            for (String id : categoryById.keySet()) {
                members.computeIfAbsent(id, k -> new ArrayList<>());
            }
        }
        members.computeIfAbsent(SavedLayout.OTHER_ID, k -> new ArrayList<>());

        for (EndpointDefinition endpoint : endpoints) {
            if (!placed.add(endpoint.id())) {
                continue;
            }
            String category = endpoint.category();
            String key = !flat && category != null && categoryById.containsKey(category)
                    ? category : SavedLayout.OTHER_ID;
            members.get(key).add(endpoint.id());
        }

        List<Category> result = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : members.entrySet()) {
            CategoryConfig config = entry.getKey().equals(SavedLayout.OTHER_ID) ? null : categoryById.get(entry.getKey());
            result.add(new Category(config, entry.getValue()));
        }
        return new ResolvedLayout(flat, result);
    }
}
