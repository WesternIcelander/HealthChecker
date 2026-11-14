package io.siggi.healthchecker.config;

import java.util.List;

/**
 * One status site, selected by the request's host.
 *
 * @param hosts         lowercase host names; {@code *.example.com} matches any subdomain
 * @param title         overrides the dashboard title, or {@code null}
 * @param publicBaseUrl this site's external URL without a trailing slash, or {@code null}
 * @param categories    included category ids (empty together with {@code endpoints} = everything)
 * @param endpoints     included endpoint ids
 */
public record SiteConfig(List<String> hosts, String title, String publicBaseUrl,
                         List<String> categories, List<String> endpoints) {
    public SiteConfig {
        hosts = List.copyOf(hosts);
        categories = List.copyOf(categories);
        endpoints = List.copyOf(endpoints);
    }

    /** True when the site lists neither categories nor endpoints, so it includes everything. */
    public boolean includesEverything() {
        return categories.isEmpty() && endpoints.isEmpty();
    }
}
