package io.siggi.healthchecker.web.site;

import io.siggi.healthchecker.config.SiteConfig;
import io.siggi.healthchecker.web.layout.ResolvedLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The status site a request is served as: which services it includes, its title, and the
 * base URL every link on it is built from. Scoping is presentation only, not access control;
 * visibility rules are applied on top of it.
 *
 * @param config        the configured site, or {@code null} for the default site
 * @param title         the page title (the site's, or {@code dashboard.title})
 * @param publicBaseUrl external URL without a trailing slash (the site's, or {@code server.publicBaseUrl}),
 *                      or {@code null} when none is configured; links are then root-relative
 * @param categories    included category ids
 * @param endpoints     included endpoint ids
 * @param everything    whether the site includes every endpoint
 */
public record Site(SiteConfig config, String title, String publicBaseUrl, Set<String> categories,
                   Set<String> endpoints, boolean everything) {
    public Site {
        categories = Set.copyOf(categories);
        endpoints = Set.copyOf(endpoints);
    }

    /** The default site: everything, with the top-level title and base URL. */
    public static Site defaultSite(String title, String publicBaseUrl) {
        return new Site(null, title, publicBaseUrl, Set.of(), Set.of(), true);
    }

    /** A configured site; its title and base URL fall back to the given defaults. */
    public static Site of(SiteConfig config, String defaultTitle, String defaultBaseUrl) {
        return new Site(config,
                config.title() != null ? config.title() : defaultTitle,
                config.publicBaseUrl() != null ? config.publicBaseUrl() : defaultBaseUrl,
                Set.copyOf(config.categories()), Set.copyOf(config.endpoints()), config.includesEverything());
    }

    public boolean isDefault() {
        return config == null;
    }

    /**
     * Whether the endpoint is in this site's scope: listed by id, or in an included category.
     *
     * @param categoryId the endpoint's category in the effective layout, or {@code null}/"Other"
     */
    public boolean includes(String endpointId, String categoryId) {
        return everything || endpoints.contains(endpointId) || (categoryId != null && categories.contains(categoryId));
    }

    /** Whether the endpoint is in scope, using its category in {@code layout}. Unknown ids are out of scope. */
    public boolean includes(String endpointId, ResolvedLayout layout) {
        Optional<String> category = categoryOf(endpointId, layout);
        return category.isPresent() && includes(endpointId, category.get());
    }

    /**
     * The layout reduced to the endpoints in scope, with order and categories unchanged
     * (a site only filters). Categories left empty stay in the result; grouping drops them.
     */
    public ResolvedLayout scope(ResolvedLayout layout) {
        if (everything) {
            return layout;
        }
        List<ResolvedLayout.Category> out = new ArrayList<>();
        for (ResolvedLayout.Category category : layout.categories()) {
            String categoryId = category.isOther() ? null : category.id();
            List<String> ids = new ArrayList<>();
            for (String id : category.endpointIds()) {
                if (includes(id, categoryId)) {
                    ids.add(id);
                }
            }
            out.add(new ResolvedLayout.Category(category.config(), ids));
        }
        return new ResolvedLayout(layout.flat(), out);
    }

    /**
     * An absolute link to {@code path} on this site, built only from the configured base URL
     * (never from the request's {@code Host}). Without a base URL the path itself is returned.
     *
     * @param path starting with a single {@code /} (so a root-relative link can't become protocol-relative)
     */
    public String link(String path) {
        if (!path.startsWith("/") || path.startsWith("//")) {
            throw new IllegalArgumentException("path must start with a single /: " + path);
        }
        return publicBaseUrl == null ? path : publicBaseUrl + path;
    }

    private static Optional<String> categoryOf(String endpointId, ResolvedLayout layout) {
        for (ResolvedLayout.Category category : layout.categories()) {
            if (category.endpointIds().contains(endpointId)) {
                return Optional.of(category.isOther() ? "" : category.id());
            }
        }
        return Optional.empty();
    }
}
