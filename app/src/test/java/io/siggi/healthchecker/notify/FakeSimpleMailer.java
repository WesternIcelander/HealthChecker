package io.siggi.healthchecker.notify;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/**
 * An in-process SimpleMailer API on an ephemeral loopback port: records requests to
 * {@code /mail/v1/messages} and answers with queued responses (default {@code 202}).
 */
public final class FakeSimpleMailer implements AutoCloseable {
    public record Response(int status, String body, Map<String, String> headers) {
    }

    public record Request(String method, String path, String authorization, String contentType, String userAgent,
                          String body, JsonObject json) {
    }

    private final HttpServer server;
    private final List<Request> requests = new ArrayList<>();
    private final Deque<Response> responses = new ArrayDeque<>();

    public FakeSimpleMailer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            JsonObject json = null;
            try {
                json = JsonParser.parseString(body).getAsJsonObject();
            } catch (RuntimeException ignored) {
                // recorded as text only
            }
            Response r;
            synchronized (this) {
                requests.add(new Request(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                        exchange.getRequestHeaders().getFirst("Authorization"),
                        exchange.getRequestHeaders().getFirst("Content-Type"),
                        exchange.getRequestHeaders().getFirst("User-Agent"), body, json));
                r = responses.isEmpty() ? accepted(json) : responses.poll();
            }
            r.headers().forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
            byte[] bytes = r.body().getBytes(StandardCharsets.UTF_8);
            if (bytes.length > 0) exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(r.status(), bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    private static Response accepted(JsonObject json) {
        String id = json != null && json.has("id") ? json.get("id").getAsString() : "";
        return new Response(202, "{\"id\":\"" + id + "\",\"status\":\"queued\"}", Map.of());
    }

    /** The base URL to configure (with a path prefix and a trailing slash, as a reverse proxy might have). */
    public URI baseUrl() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/mail/");
    }

    public synchronized void respond(int status, String body, Map<String, String> headers) {
        responses.add(new Response(status, body, headers));
    }

    public synchronized void respond(int status, String body) {
        respond(status, body, Map.of());
    }

    public synchronized List<Request> requests() {
        return List.copyOf(requests);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
