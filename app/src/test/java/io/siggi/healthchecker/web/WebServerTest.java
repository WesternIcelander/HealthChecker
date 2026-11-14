package io.siggi.healthchecker.web;

import io.siggi.healthchecker.config.ServerConfig;
import io.siggi.healthchecker.config.UnknownHostsPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebServerTest {
    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final List<WebServer> servers = new ArrayList<>();

    @AfterEach
    void stop() {
        servers.forEach(WebServer::close);
    }

    private WebServer start(String... trustedProxies) throws Exception {
        WebServer server = new WebServer(new ServerConfig(0, "127.0.0.1", null, List.of(trustedProxies),
                UnknownHostsPolicy.DEFAULT));
        servers.add(server);
        // Echoes what HealthChecker believes about the client.
        server.register("/whoami", request -> {
            TrustedProxies proxies = server.trustedProxies();
            request.response.setHeader("Content-Type", "text/plain; charset=utf-8");
            request.response.write(proxies.clientAddress(request).getHostAddress()
                    + " " + (request.isSecure() ? "https" : "http"));
        });
        server.start();
        return server;
    }

    private HttpResponse<String> get(WebServer server, String path, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .timeout(Duration.ofSeconds(5));
        if (headers.length > 0) b.headers(headers);
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void healthReturns200() throws Exception {
        WebServer server = start();
        HttpResponse<String> r = get(server, "/health");
        assertEquals(200, r.statusCode());
        assertEquals("ok\n", r.body());
        assertTrue(r.headers().firstValue("Content-Type").orElse("").startsWith("text/plain"));
        assertEquals("no-store", r.headers().firstValue("Cache-Control").orElse(""));
    }

    @Test
    void healthRejectsPost() throws Exception {
        WebServer server = start();
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/health"))
                .POST(HttpRequest.BodyPublishers.ofString("x")).build();
        assertEquals(405, client.send(req, HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    @Test
    void servesStaticFiles() throws Exception {
        WebServer server = start();
        HttpResponse<String> css = get(server, "/static/style.css");
        assertEquals(200, css.statusCode());
        assertTrue(css.headers().firstValue("Content-Type").orElse("").startsWith("text/css"));
        assertEquals(WebServer.STATIC_CACHE_CONTROL, css.headers().firstValue("Cache-Control").orElse(""));
        assertTrue(css.body().contains("prefers-color-scheme"));

        HttpResponse<String> js = get(server, "/static/app.js");
        assertEquals(200, js.statusCode());
        assertTrue(js.headers().firstValue("Content-Type").orElse("").startsWith("text/javascript"));

        assertEquals(404, get(server, "/static/missing.css").statusCode());
        assertEquals(404, get(server, "/static/").statusCode());
        assertEquals(404, get(server, "/nothing-here").statusCode());
    }

    @Test
    void forwardedForIgnoredWithoutTrustedProxies() throws Exception {
        // SiggiHTTP trusts loopback by default; HealthChecker clears that.
        WebServer server = start();
        HttpResponse<String> r = get(server, "/whoami",
                "X-Forwarded-For", "203.0.113.5", "X-Forwarded-Proto", "https");
        assertEquals("127.0.0.1 http", r.body());
    }

    @Test
    void forwardedForIgnoredFromUntrustedProxy() throws Exception {
        WebServer server = start("10.0.0.0/8", "::1");
        HttpResponse<String> r = get(server, "/whoami",
                "X-Forwarded-For", "203.0.113.5", "X-Forwarded-Proto", "https");
        assertEquals("127.0.0.1 http", r.body());
    }

    @Test
    void forwardedForHonouredFromTrustedProxy() throws Exception {
        WebServer server = start("127.0.0.1");
        HttpResponse<String> r = get(server, "/whoami",
                "X-Forwarded-For", "203.0.113.5", "X-Forwarded-Proto", "https");
        assertEquals("203.0.113.5 https", r.body());
    }

    @Test
    void forwardedForStopsAtFirstUntrustedHop() throws Exception {
        WebServer server = start("127.0.0.0/8", "10.0.0.0/8");
        // The leftmost entry is client-supplied and unverifiable; 198.51.100.7 was added by our proxy chain.
        HttpResponse<String> r = get(server, "/whoami", "X-Forwarded-For", "192.0.2.66, 198.51.100.7, 10.1.2.3");
        assertEquals("198.51.100.7 http", r.body());
    }

    @Test
    void forwardedForAllTrustedHopsGivesLeftmost() throws Exception {
        WebServer server = start("127.0.0.0/8", "10.0.0.0/8");
        assertEquals("10.9.9.9 http", get(server, "/whoami", "X-Forwarded-For", "10.9.9.9, 10.2.3.4").body());
    }

    @Test
    void forwardedForInvalidEntryStopsTheWalk() throws Exception {
        WebServer server = start("127.0.0.0/8", "10.0.0.0/8");
        assertEquals("127.0.0.1 http", get(server, "/whoami", "X-Forwarded-For", "unknown").body());
        assertEquals("10.2.3.4 http",
                get(server, "/whoami", "X-Forwarded-For", "198.51.100.7, garbage, 10.2.3.4").body());
    }

    @Test
    void forwardedForIpv6() throws Exception {
        WebServer server = start("127.0.0.1");
        String body = get(server, "/whoami", "X-Forwarded-For", "2001:db8::1").body();
        assertEquals("2001:db8:0:0:0:0:0:1 http", body);
    }

    @Test
    void forwardedForProxyAddedHeaderLine() throws Exception {
        // A proxy that adds its own header line after the client's: the proxy's line decides.
        WebServer server = start("127.0.0.0/8", "10.0.0.0/8");
        assertEquals("198.51.100.7 http", get(server, "/whoami",
                "X-Forwarded-For", "192.0.2.66", "X-Forwarded-For", "198.51.100.7, 10.1.2.3").body());
    }

    @Test
    void cfConnectingIpIgnored() throws Exception {
        WebServer server = start("127.0.0.1");
        assertEquals("127.0.0.1 http", get(server, "/whoami", "CF-Connecting-IP", "203.0.113.9").body());
        assertEquals("198.51.100.7 http", get(server, "/whoami",
                "X-Forwarded-For", "198.51.100.7", "CF-Connecting-IP", "203.0.113.9").body());
    }

    @Test
    void cannotBindTwice() throws Exception {
        WebServer server = start();
        WebServer second = new WebServer(new ServerConfig(server.port(), "127.0.0.1", null, List.of(),
                UnknownHostsPolicy.DEFAULT));
        servers.add(second);
        assertThrows(java.io.IOException.class, second::start);
    }

    @Test
    void closeStopsListening() throws Exception {
        WebServer server = start();
        int port = server.port();
        server.close();
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/health"))
                .timeout(Duration.ofSeconds(5)).build();
        assertThrows(java.io.IOException.class, () -> client.send(req, HttpResponse.BodyHandlers.ofString()));
    }
}
