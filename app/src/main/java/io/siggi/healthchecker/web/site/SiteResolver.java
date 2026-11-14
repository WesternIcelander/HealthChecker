package io.siggi.healthchecker.web.site;

import io.siggi.healthchecker.config.Config;
import io.siggi.healthchecker.config.SiteConfig;
import io.siggi.healthchecker.config.UnknownHostsPolicy;
import io.siggi.healthchecker.web.TrustedProxies;
import io.siggi.healthchecker.web.layout.ResolvedLayout;
import io.siggi.http.HTTPRequest;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Picks the {@link Site} for a request from its host name.
 * <p>
 * The host is the {@code Host} header, or the nearest (last) {@code X-Forwarded-Host} entry when
 * the connection comes from a trusted proxy ({@code server.trustedProxies}; SiggiHTTP's own
 * handling of that header always trusts loopback, so it isn't used). It's compared
 * case-insensitively without its port and without a trailing dot. {@code *.example.com} matches
 * any subdomain at any depth, but not {@code example.com} itself. The first matching site wins;
 * no match gives the default site, or nothing when {@code server.unknownHosts} is {@code "reject"}.
 * <p>
 * The host only selects a site. It's never used to build URLs: links come from
 * {@link Site#link(String)}.
 */
public final class SiteResolver {
    private final List<Site> sites;
    private final Site defaultSite;
    private final UnknownHostsPolicy unknownHosts;
    private final TrustedProxies trustedProxies;

    public SiteResolver(List<SiteConfig> sites, String defaultTitle, String defaultBaseUrl,
                        UnknownHostsPolicy unknownHosts, TrustedProxies trustedProxies) {
        List<Site> list = new ArrayList<>();
        for (SiteConfig site : sites) {
            list.add(Site.of(site, defaultTitle, defaultBaseUrl));
        }
        this.sites = List.copyOf(list);
        this.defaultSite = Site.defaultSite(defaultTitle, defaultBaseUrl);
        this.unknownHosts = unknownHosts;
        this.trustedProxies = trustedProxies;
    }

    public static SiteResolver fromConfig(Config config, TrustedProxies trustedProxies) {
        return new SiteResolver(config.sites(), config.dashboard().title(), config.server().publicBaseUrl(),
                config.server().unknownHosts(), trustedProxies);
    }

    public List<Site> sites() {
        return sites;
    }

    public Site defaultSite() {
        return defaultSite;
    }

    /** The site for the request, or empty when its host is unknown and unknown hosts are rejected (404). */
    public Optional<Site> resolve(HTTPRequest request) {
        return resolve(request.getNextHopAddress().getAddress(), request.getHeader("Host"),
                request.getHeaders("X-Forwarded-Host"));
    }

    /**
     * @param nextHop       the connection's peer address
     * @param hostHeader    the {@code Host} header, or {@code null}
     * @param forwardedHost the {@code X-Forwarded-Host} header lines, or {@code null}
     */
    public Optional<Site> resolve(InetAddress nextHop, String hostHeader, List<String> forwardedHost) {
        return resolveHost(effectiveHost(nextHop, hostHeader, forwardedHost));
    }

    /** The site for an already normalized host name ({@code null}: no host). */
    public Optional<Site> resolveHost(String host) {
        if (host != null) {
            for (Site site : sites) {
                for (String pattern : site.config().hosts()) {
                    if (matches(pattern, host)) {
                        return Optional.of(site);
                    }
                }
            }
        }
        return unknownHosts == UnknownHostsPolicy.REJECT ? Optional.empty() : Optional.of(defaultSite);
    }

    /**
     * The site used for links about an endpoint outside of a request (notifications): the first
     * configured site whose scope includes it, otherwise the default site.
     */
    public Site siteFor(String endpointId, ResolvedLayout layout) {
        for (Site site : sites) {
            if (site.includes(endpointId, layout)) {
                return site;
            }
        }
        return defaultSite;
    }

    /** The normalized host the request names, honouring {@code X-Forwarded-Host} only from trusted proxies. */
    String effectiveHost(InetAddress nextHop, String hostHeader, List<String> forwardedHost) {
        if (forwardedHost != null && !forwardedHost.isEmpty() && trustedProxies.isTrusted(nextHop)) {
            String nearest = null;
            for (String line : forwardedHost) {
                for (String part : line.split(",", -1)) {
                    nearest = part;
                }
            }
            String host = normalizeHost(nearest);
            if (host != null) {
                return host;
            }
        }
        return normalizeHost(hostHeader);
    }

    /**
     * Lowercases a {@code Host}-style value and strips its port and a trailing dot.
     * Bracketed IPv6 literals keep their brackets. Returns {@code null} for a missing or empty value.
     */
    public static String normalizeHost(String value) {
        if (value == null) return null;
        String host = value.trim().toLowerCase(Locale.ROOT);
        if (host.startsWith("[")) {
            int end = host.indexOf(']');
            host = end < 0 ? host : host.substring(0, end + 1);
        } else {
            int colon = host.indexOf(':');
            if (colon >= 0 && colon == host.lastIndexOf(':')) {
                host = host.substring(0, colon);
            }
        }
        if (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        return host.isEmpty() ? null : host;
    }

    /** Whether a configured host (lowercase, no port; optionally {@code *.suffix}) matches a normalized host. */
    static boolean matches(String pattern, String host) {
        if (pattern.startsWith("*.")) {
            String suffix = pattern.substring(1); // ".example.com"
            return host.length() > suffix.length() && host.endsWith(suffix);
        }
        return pattern.equals(host);
    }
}
