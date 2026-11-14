package io.siggi.healthchecker.web.page;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.siggi.healthchecker.api.CheckResult;
import io.siggi.healthchecker.config.CategoryConfig;
import io.siggi.healthchecker.config.EndpointDefinition;
import io.siggi.healthchecker.config.ServerConfig;
import io.siggi.healthchecker.config.SiteConfig;
import io.siggi.healthchecker.config.UnknownHostsPolicy;
import io.siggi.healthchecker.config.Visibility;
import io.siggi.healthchecker.plugin.JsonEndpointConfig.Target;
import io.siggi.healthchecker.schedule.Endpoint;
import io.siggi.healthchecker.status.EndpointStatus;
import io.siggi.healthchecker.status.State;
import io.siggi.healthchecker.web.WebServer;
import io.siggi.healthchecker.web.layout.ResolvedLayout;
import io.siggi.healthchecker.web.site.SiteResolver;
import io.siggi.healthchecker.web.view.Viewer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Site scoping on the main page and {@code /api/status}, over real HTTP. Requests are written by
 * hand because {@link java.net.http.HttpClient} won't send a custom {@code Host} header.
 */
class StatusPageTest {
    private static final Instant T0 = Instant.parse("2026-11-13T09:00:00Z");

    private final Map<String, Endpoint> endpoints = new LinkedHashMap<>();
    private final Map<String, EndpointStatus> statuses = new HashMap<>();
    private final List<CategoryConfig> categories = List.of(
            new CategoryConfig("web", "Websites", null),
            new CategoryConfig("minecraft", "Minecraft servers", null));
    private final List<WebServer> servers = new ArrayList<>();
    private Viewer viewer = Viewer.ANONYMOUS;

    private static final SiteConfig MC = new SiteConfig(List.of("mc-status.example.com", "*.mc.example.com"),
            "Minecraft status", "https://mc-status.example.com", List.of("minecraft"), List.of());
    private static final SiteConfig WEB = new SiteConfig(List.of("web-status.example.com"), "Website status",
            null, List.of(), List.of("website"));

    StatusPageTest() {
        add("website", "web", Visibility.PUBLIC, State.UP, "https://www.example.com");
        add("intranet", "web", Visibility.PRIVATE, State.DOWN, "http://10.0.0.5:8080");
        add("survival", "minecraft", Visibility.PUBLIC_STATUS, State.DOWN, "mc.example.net");
        add("creative", "minecraft", Visibility.PUBLIC, State.UP, "creative.example.net");
    }

    @AfterEach
    void stop() {
        servers.forEach(WebServer::close);
    }

    private void add(String id, String category, Visibility visibility, State state, String target) {
        EndpointDefinition def = new EndpointDefinition(id, "Name " + id, "http", category, visibility, 60, 5000, 1,
                List.of(), List.of(), 0, new JsonObject());
        endpoints.put(id, new Endpoint(def, List.of(new Target("url", target)), timeout -> CheckResult.ok("ok")));
        statuses.put(id, new EndpointStatus(id, state, T0, 1, 0, null, null, null, List.of()));
    }

    private WebServer start(UnknownHostsPolicy policy, String baseUrl, List<String> trustedProxies) throws Exception {
        ServerConfig config = new ServerConfig(0, "127.0.0.1", baseUrl, trustedProxies, policy);
        WebServer server = new WebServer(config);
        servers.add(server);
        SiteResolver sites = new SiteResolver(List.of(MC, WEB), "Status", baseUrl, policy, server.trustedProxies());
        List<EndpointDefinition> defs = endpoints.values().stream().map(Endpoint::definition).toList();
        new StatusPage(sites, () -> ResolvedLayout.resolve(null, categories, defs),
                id -> Optional.ofNullable(endpoints.get(id)), id -> Optional.ofNullable(statuses.get(id)),
                request -> viewer).register(server);
        server.start();
        return server;
    }

    private WebServer start(UnknownHostsPolicy policy) throws Exception {
        return start(policy, null, List.of());
    }

    record Response(int status, String body) {
    }

    private static Response get(WebServer server, String path, String host, String... headers) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", server.port())) {
            socket.setSoTimeout(5000);
            StringBuilder req = new StringBuilder("GET " + path + " HTTP/1.1\r\n");
            if (host != null) req.append("Host: ").append(host).append("\r\n");
            for (int i = 0; i < headers.length; i += 2) {
                req.append(headers[i]).append(": ").append(headers[i + 1]).append("\r\n");
            }
            req.append("Connection: close\r\n\r\n");
            OutputStream out = socket.getOutputStream();
            out.write(req.toString().getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            InputStream in = socket.getInputStream();
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            in.transferTo(buffer);
            String raw = buffer.toString(StandardCharsets.UTF_8);
            int status = Integer.parseInt(raw.substring(raw.indexOf(' ') + 1, raw.indexOf(' ') + 4));
            int split = raw.indexOf("\r\n\r\n");
            String head = raw.substring(0, split);
            String body = raw.substring(split + 4);
            if (head.toLowerCase(Locale.ROOT).contains("transfer-encoding: chunked")) {
                body = dechunk(body);
            }
            return new Response(status, body);
        }
    }

    private static String dechunk(String body) {
        StringBuilder out = new StringBuilder();
        int pos = 0;
        while (true) {
            int eol = body.indexOf("\r\n", pos);
            int size = Integer.parseInt(body.substring(pos, eol).trim(), 16);
            if (size == 0) return out.toString();
            out.append(body, eol + 2, eol + 2 + size);
            pos = eol + 2 + size + 2;
        }
    }

    private static List<String> apiIds(Response response) {
        JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
        List<String> ids = new ArrayList<>();
        for (JsonElement category : root.getAsJsonArray("categories")) {
            JsonArray services = category.getAsJsonObject().getAsJsonArray("endpoints");
            for (JsonElement service : services) {
                ids.add(service.getAsJsonObject().get("id").getAsString());
            }
        }
        return ids;
    }

    private static List<String> pageIds(Response response) {
        List<String> ids = new ArrayList<>();
        Matcher m = Pattern.compile("href=\"[^\"]*/service/([a-z0-9-]+)\"").matcher(response.body());
        while (m.find()) ids.add(m.group(1));
        return ids;
    }

    private static String overall(Response response) {
        return JsonParser.parseString(response.body()).getAsJsonObject().get("overall").getAsString();
    }

    @Test
    void scopingOnMainPageAndApi() throws Exception {
        WebServer server = start(UnknownHostsPolicy.DEFAULT);

        Response mcApi = get(server, "/api/status", "mc-status.example.com");
        assertEquals(List.of("survival", "creative"), apiIds(mcApi));
        assertEquals("DOWN", overall(mcApi));
        assertEquals(503, mcApi.status());
        Response mcPage = get(server, "/", "mc-status.example.com");
        assertEquals(200, mcPage.status());
        assertEquals(List.of("survival", "creative"), pageIds(mcPage));
        assertTrue(mcPage.body().contains("<title>Minecraft status</title>"));
        assertFalse(mcPage.body().contains("Websites"));

        // Overall state and status code are computed within the scope: the down services are elsewhere.
        Response webApi = get(server, "/api/status", "web-status.example.com");
        assertEquals(List.of("website"), apiIds(webApi));
        assertEquals("UP", overall(webApi));
        assertEquals(200, webApi.status());
        Response webPage = get(server, "/", "web-status.example.com");
        assertEquals(List.of("website"), pageIds(webPage));
        assertTrue(webPage.body().contains("<title>Website status</title>"));
        assertTrue(webPage.body().contains("All systems operational"));

        // Default site: everything visible, top-level title.
        Response all = get(server, "/api/status", "status.example.com");
        assertEquals(List.of("website", "survival", "creative"), apiIds(all));
        Response allPage = get(server, "/", "status.example.com");
        assertEquals(List.of("website", "survival", "creative"), pageIds(allPage));
        assertTrue(allPage.body().contains("<title>Status</title>"));
    }

    @Test
    void visibilityAppliesOnTopOfScopeEvenWhenLoggedIn() throws Exception {
        WebServer server = start(UnknownHostsPolicy.DEFAULT);
        // Anonymous: the private endpoint is hidden on the default site.
        assertFalse(apiIds(get(server, "/api/status", "status.example.com")).contains("intranet"));

        viewer = new Viewer("alice", true);
        assertEquals(List.of("website", "intranet", "survival", "creative"),
                apiIds(get(server, "/api/status", "status.example.com")));
        // The scope still applies to a privileged viewer.
        assertEquals(List.of("survival", "creative"), apiIds(get(server, "/api/status", "mc-status.example.com")));
        assertEquals(List.of("website"), pageIds(get(server, "/", "web-status.example.com")));
    }

    @Test
    void wildcardAndPortHandling() throws Exception {
        WebServer server = start(UnknownHostsPolicy.REJECT);
        assertEquals(List.of("survival", "creative"), apiIds(get(server, "/api/status", "MC-Status.Example.com:8080")));
        assertEquals(List.of("survival", "creative"), apiIds(get(server, "/api/status", "eu.mc.example.com")));
        assertEquals(List.of("survival", "creative"), pageIds(get(server, "/", "a.b.mc.example.com:443")));
        assertEquals(404, get(server, "/api/status", "mc.example.com").status());
        assertEquals(404, get(server, "/", "evilmc.example.com").status());
    }

    @Test
    void unknownHostDefaultVsReject() throws Exception {
        WebServer lenient = start(UnknownHostsPolicy.DEFAULT);
        assertEquals(List.of("website", "survival", "creative"), apiIds(get(lenient, "/api/status", "unknown.test")));
        assertEquals(200, get(lenient, "/", "127.0.0.1:1234").status());

        WebServer strict = start(UnknownHostsPolicy.REJECT);
        assertEquals(404, get(strict, "/api/status", "unknown.test").status());
        assertEquals(404, get(strict, "/", "unknown.test").status());
        assertEquals(404, get(strict, "/", "127.0.0.1").status());
        // /health stays available for load balancers that use an IP address.
        assertEquals(200, get(strict, "/health", "127.0.0.1").status());
        assertEquals(200, get(strict, "/", "web-status.example.com").status());
    }

    @Test
    void forwardedHostIgnoredFromUntrustedIps() throws Exception {
        // SiggiHTTP itself trusts loopback for X-Forwarded-Host; HealthChecker must not.
        WebServer untrusted = start(UnknownHostsPolicy.REJECT, null, List.of("10.0.0.0/8"));
        assertEquals(404, get(untrusted, "/api/status", "internal:8080",
                "X-Forwarded-Host", "mc-status.example.com").status());
        assertEquals(List.of("website"), apiIds(get(untrusted, "/api/status", "web-status.example.com",
                "X-Forwarded-Host", "mc-status.example.com")));

        WebServer trusted = start(UnknownHostsPolicy.REJECT, null, List.of("127.0.0.1"));
        assertEquals(List.of("survival", "creative"), apiIds(get(trusted, "/api/status", "internal:8080",
                "X-Forwarded-Host", "mc-status.example.com")));
        assertEquals(List.of("website"), pageIds(get(trusted, "/", "mc-status.example.com",
                "X-Forwarded-Host", "web-status.example.com:443")));
    }

    @Test
    void linksNeverBuiltFromHostHeader() throws Exception {
        WebServer server = start(UnknownHostsPolicy.DEFAULT, "https://status.example.com", List.of("127.0.0.1"));
        String evil = "evil.attacker.test";

        // Unknown host → default site, links from server.publicBaseUrl.
        Response page = get(server, "/", evil);
        assertFalse(page.body().contains(evil));
        assertTrue(page.body().contains("href=\"https://status.example.com/service/website\""));
        assertTrue(page.body().contains("href=\"https://status.example.com/static/style.css\""));
        assertFalse(get(server, "/api/status", evil).body().contains(evil));

        // Matched site with its own publicBaseUrl: that one, not the Host header (with its port).
        Response mc = get(server, "/", "mc-status.example.com:9999");
        assertFalse(mc.body().contains(":9999"));
        assertTrue(mc.body().contains("href=\"https://mc-status.example.com/service/survival\""));
        // A matched wildcard host never ends up in a link either.
        Response wildcard = get(server, "/", "evil.mc.example.com");
        assertFalse(wildcard.body().contains("evil.mc.example.com"));

        // Matched site without publicBaseUrl falls back to the server default.
        Response web = get(server, "/", "web-status.example.com");
        assertFalse(web.body().contains("web-status.example.com"));
        assertTrue(web.body().contains("href=\"https://status.example.com/service/website\""));

        // Via a trusted proxy, the forwarded host selects the site but isn't echoed either.
        Response forwarded = get(server, "/", "internal:8080", "X-Forwarded-Host", evil);
        assertFalse(forwarded.body().contains(evil));
        assertFalse(forwarded.body().contains("internal"));
    }

    @Test
    void linksAreRootRelativeWithoutBaseUrl() throws Exception {
        WebServer server = start(UnknownHostsPolicy.DEFAULT);
        Response page = get(server, "/", "somewhere.test");
        assertTrue(page.body().contains("href=\"/service/website\""));
        assertFalse(page.body().contains("somewhere.test"));
    }

    @Test
    void noTargetsInAnonymousOutput() throws Exception {
        WebServer server = start(UnknownHostsPolicy.DEFAULT);
        for (String path : List.of("/", "/api/status")) {
            String body = get(server, path, "status.example.com").body();
            for (Endpoint endpoint : endpoints.values()) {
                for (Target target : endpoint.targets()) {
                    assertFalse(body.contains(target.value()), path + " leaks " + target.value());
                }
            }
        }
    }

    @Test
    void otherPathsStillNotFound() throws Exception {
        WebServer server = start(UnknownHostsPolicy.DEFAULT);
        assertEquals(404, get(server, "/nothing-here", "status.example.com").status());
        assertEquals(404, get(server, "/api/status/extra", "status.example.com").status());
    }
}
