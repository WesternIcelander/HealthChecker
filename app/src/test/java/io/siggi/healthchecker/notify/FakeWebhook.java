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

/** An in-process Discord webhook on an ephemeral loopback port: records posts, answers with queued responses (default 204). */
public final class FakeWebhook implements AutoCloseable {
    public record Response(int status, String body, Map<String, String> headers) {
    }

    public record Post(String contentType, String userAgent, JsonObject json) {
    }

    private final HttpServer server;
    private final List<Post> posts = new ArrayList<>();
    private final Deque<Response> responses = new ArrayDeque<>();

    public FakeWebhook() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/api/webhooks/1/secret-token", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Response r;
            synchronized (this) {
                posts.add(new Post(exchange.getRequestHeaders().getFirst("Content-Type"),
                        exchange.getRequestHeaders().getFirst("User-Agent"),
                        exchange.getRequestMethod().equals("POST") ? JsonParser.parseString(body).getAsJsonObject() : null));
                r = responses.isEmpty() ? new Response(204, "", Map.of()) : responses.poll();
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

    public URI url() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/webhooks/1/secret-token");
    }

    public synchronized void respond(int status, String body, Map<String, String> headers) {
        responses.add(new Response(status, body, headers));
    }

    public synchronized void respond(int status, String body) {
        respond(status, body, Map.of());
    }

    public synchronized List<Post> posts() {
        return List.copyOf(posts);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
