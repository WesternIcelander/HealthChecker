package io.siggi.healthchecker.web.site;

import com.google.gson.JsonObject;
import io.siggi.healthchecker.config.CategoryConfig;
import io.siggi.healthchecker.config.EndpointDefinition;
import io.siggi.healthchecker.config.SiteConfig;
import io.siggi.healthchecker.config.UnknownHostsPolicy;
import io.siggi.healthchecker.config.Visibility;
import io.siggi.healthchecker.web.TrustedProxies;
import io.siggi.healthchecker.web.layout.ResolvedLayout;
import io.siggi.healthchecker.web.layout.SavedLayout;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SiteResolverTest {
    private static final InetAddress PROXY = address("10.0.0.2");
    private static final InetAddress STRANGER = address("203.0.113.9");

    private static final SiteConfig MC = new SiteConfig(List.of("mc-status.example.com"), "Minecraft status",
            "https://mc-status.example.com", List.of("minecraft"), List.of());
    private static final SiteConfig WILD = new SiteConfig(List.of("*.example.org", "exact.example.net"), null,
            null, List.of(), List.of("website"));
    private static final SiteConfig ALSO_MC = new SiteConfig(List.of("*.example.com"), "Wildcard",
            null, List.of(), List.of());

    private static InetAddress address(String literal) {
        try {
            return InetAddress.getByName(literal);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static SiteResolver resolver(UnknownHostsPolicy policy, SiteConfig... sites) {
        return new SiteResolver(List.of(sites), "Status", "https://status.example.com", policy,
                TrustedProxies.parse(List.of("10.0.0.0/8")));
    }

    private static SiteConfig site(Optional<Site> resolved) {
        assertTrue(resolved.isPresent(), "expected a site");
        return resolved.get().config();
    }

    @Test
    void exactMatchIsCaseInsensitiveAndIgnoresPort() {
        SiteResolver r = resolver(UnknownHostsPolicy.DEFAULT, MC, WILD);
        assertSame(MC, site(r.resolve(STRANGER, "mc-status.example.com", null)));
        assertSame(MC, site(r.resolve(STRANGER, "MC-Status.Example.COM:8443", null)));
        assertSame(MC, site(r.resolve(STRANGER, "mc-status.example.com.", null)));
        assertSame(WILD, site(r.resolve(STRANGER, "exact.example.net:80", null)));
        assertNull(site(r.resolve(STRANGER, "other.example.net", null)));
        assertNull(site(r.resolve(STRANGER, "status.mc-status.example.com", null)));
    }

    @Test
    void wildcardMatchesSubdomainsOnly() {
        SiteResolver r = resolver(UnknownHostsPolicy.REJECT, WILD);
        assertSame(WILD, site(r.resolve(STRANGER, "a.example.org", null)));
        assertSame(WILD, site(r.resolve(STRANGER, "a.b.example.org:8080", null)));
        assertSame(WILD, site(r.resolve(STRANGER, "A.EXAMPLE.ORG", null)));
        assertTrue(r.resolve(STRANGER, "example.org", null).isEmpty());
        assertTrue(r.resolve(STRANGER, "badexample.org", null).isEmpty());
        assertTrue(r.resolve(STRANGER, "a.example.org.evil.test", null).isEmpty());
    }

    @Test
    void firstMatchingSiteWins() {
        SiteResolver r = resolver(UnknownHostsPolicy.DEFAULT, MC, ALSO_MC);
        assertSame(MC, site(r.resolve(STRANGER, "mc-status.example.com", null)));
        assertSame(ALSO_MC, site(r.resolve(STRANGER, "web.example.com", null)));
        SiteResolver reversed = resolver(UnknownHostsPolicy.DEFAULT, ALSO_MC, MC);
        assertSame(ALSO_MC, site(reversed.resolve(STRANGER, "mc-status.example.com", null)));
    }

    @Test
    void unknownHostGetsDefaultSiteOrIsRejected() {
        SiteResolver lenient = resolver(UnknownHostsPolicy.DEFAULT, MC);
        Site fallback = lenient.resolve(STRANGER, "203.0.113.1:8080", null).orElseThrow();
        assertTrue(fallback.isDefault());
        assertTrue(fallback.everything());
        assertEquals("Status", fallback.title());
        assertEquals("https://status.example.com", fallback.publicBaseUrl());
        assertTrue(lenient.resolve(STRANGER, null, null).orElseThrow().isDefault());

        SiteResolver strict = resolver(UnknownHostsPolicy.REJECT, MC);
        assertTrue(strict.resolve(STRANGER, "unknown.example.com", null).isEmpty());
        assertTrue(strict.resolve(STRANGER, null, null).isEmpty());
        assertTrue(strict.resolve(STRANGER, "", null).isEmpty());
        assertSame(MC, site(strict.resolve(STRANGER, "mc-status.example.com", null)));
    }

    @Test
    void forwardedHostOnlyFromTrustedProxies() {
        SiteResolver r = resolver(UnknownHostsPolicy.REJECT, MC);
        List<String> forwarded = List.of("mc-status.example.com");
        assertSame(MC, site(r.resolve(PROXY, "internal:8080", forwarded)));
        // From an untrusted peer the header is ignored and the Host header decides.
        assertTrue(r.resolve(STRANGER, "internal:8080", forwarded).isEmpty());
        assertTrue(r.resolve(address("127.0.0.1"), "internal:8080", forwarded).isEmpty());
        // Nor can an untrusted peer use it to escape its Host-selected site.
        assertSame(MC, site(r.resolve(STRANGER, "mc-status.example.com", List.of("other.example.com"))));
    }

    @Test
    void forwardedHostUsesNearestEntry() {
        SiteResolver r = resolver(UnknownHostsPolicy.REJECT, MC);
        assertSame(MC, site(r.resolve(PROXY, "internal", List.of("spoofed.example.com, MC-STATUS.example.com:443"))));
        assertSame(MC, site(r.resolve(PROXY, "internal", List.of("spoofed.example.com", "mc-status.example.com"))));
        assertTrue(r.resolve(PROXY, "mc-status.example.com", List.of("mc-status.example.com, spoofed.example.com"))
                .isEmpty());
        // An empty forwarded value falls back to Host.
        assertSame(MC, site(r.resolve(PROXY, "mc-status.example.com", List.of(""))));
    }

    @Test
    void normalizeHost() {
        assertEquals("example.com", SiteResolver.normalizeHost(" Example.COM:8080 "));
        assertEquals("example.com", SiteResolver.normalizeHost("example.com."));
        assertEquals("[::1]", SiteResolver.normalizeHost("[::1]:8080"));
        assertEquals("[::1]", SiteResolver.normalizeHost("[::1]"));
        assertEquals("::1", SiteResolver.normalizeHost("::1"));
        assertNull(SiteResolver.normalizeHost(""));
        assertNull(SiteResolver.normalizeHost(":80"));
        assertNull(SiteResolver.normalizeHost(null));
    }

    @Test
    void titleAndBaseUrlFallBackToDefaults() {
        SiteResolver r = resolver(UnknownHostsPolicy.DEFAULT, MC, WILD);
        Site mc = r.resolve(STRANGER, "mc-status.example.com", null).orElseThrow();
        assertEquals("Minecraft status", mc.title());
        assertEquals("https://mc-status.example.com/service/x", mc.link("/service/x"));
        Site wild = r.resolve(STRANGER, "a.example.org", null).orElseThrow();
        assertEquals("Status", wild.title());
        assertEquals("https://status.example.com/", wild.link("/"));

        Site noBase = new SiteResolver(List.of(), "Status", null, UnknownHostsPolicy.DEFAULT,
                TrustedProxies.parse(List.of())).defaultSite();
        assertEquals("/service/x", noBase.link("/service/x"));
        assertThrows(IllegalArgumentException.class, () -> noBase.link("//evil.test/x"));
        assertThrows(IllegalArgumentException.class, () -> noBase.link("service"));
    }

    private static EndpointDefinition endpoint(String id, String category) {
        return new EndpointDefinition(id, "Name " + id, "http", category, Visibility.PUBLIC, 60, 5000, 1,
                List.of(), List.of(), 0, new JsonObject());
    }

    @Test
    void scopeFiltersButKeepsOrder() {
        List<CategoryConfig> categories = List.of(new CategoryConfig("web", "Websites", null),
                new CategoryConfig("minecraft", "Minecraft", null));
        List<EndpointDefinition> defs = List.of(endpoint("website", "web"), endpoint("blog", "web"),
                endpoint("survival", "minecraft"), endpoint("creative", "minecraft"), endpoint("loose", null));
        // The saved layout moves "blog" into minecraft and reorders.
        SavedLayout saved = new SavedLayout(1, List.of(
                new SavedLayout.Category("minecraft", List.of("creative", "blog", "survival")),
                new SavedLayout.Category("web", List.of("website"))));
        ResolvedLayout layout = ResolvedLayout.resolve(saved, categories, defs);

        Site mc = Site.of(MC, "Status", null);
        ResolvedLayout scoped = mc.scope(layout);
        assertEquals(List.of("minecraft", "web", SavedLayout.OTHER_ID),
                scoped.categories().stream().map(ResolvedLayout.Category::id).toList());
        assertEquals(List.of("creative", "blog", "survival"), scoped.categories().get(0).endpointIds());
        assertEquals(List.of(), scoped.categories().get(1).endpointIds());
        assertEquals(List.of(), scoped.categories().get(2).endpointIds());
        assertTrue(mc.includes("blog", layout));
        assertFalse(mc.includes("website", layout));
        assertFalse(mc.includes("ghost", layout));

        // Union of categories and endpoints.
        Site mixed = Site.of(new SiteConfig(List.of("x.test"), null, null, List.of("web"), List.of("loose", "creative")),
                "Status", null);
        ResolvedLayout mixedScope = mixed.scope(layout);
        assertEquals(List.of("creative"), mixedScope.categories().get(0).endpointIds());
        assertEquals(List.of("website"), mixedScope.categories().get(1).endpointIds());
        assertEquals(List.of("loose"), mixedScope.categories().get(2).endpointIds());

        Site all = Site.of(new SiteConfig(List.of("all.test"), null, null, List.of(), List.of()), "Status", null);
        assertSame(layout, all.scope(layout));
    }

    @Test
    void siteForEndpointIsFirstSiteIncludingIt() {
        List<CategoryConfig> categories = List.of(new CategoryConfig("minecraft", "Minecraft", null));
        ResolvedLayout layout = ResolvedLayout.resolve(null, categories,
                List.of(endpoint("survival", "minecraft"), endpoint("website", null)));
        SiteResolver r = resolver(UnknownHostsPolicy.DEFAULT, MC, WILD);
        assertSame(MC, r.siteFor("survival", layout).config());
        assertSame(WILD, r.siteFor("website", layout).config());
        assertTrue(r.siteFor("ghost", layout).isDefault());
    }
}
