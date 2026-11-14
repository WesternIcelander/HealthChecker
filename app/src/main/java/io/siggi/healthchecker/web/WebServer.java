package io.siggi.healthchecker.web;

import io.siggi.healthchecker.config.ServerConfig;
import io.siggi.http.HTTPRequest;
import io.siggi.http.HTTPResponder;
import io.siggi.http.HTTPServer;
import io.siggi.http.HTTPServerBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The dashboard's HTTP server: a SiggiHTTP {@link HTTPServer} fed by HealthChecker's own accept
 * thread on {@code server.bind}:{@code server.port}. Built-in routes are {@code GET /health} and
 * the static files under {@code /static/}; pages and APIs are added with {@link #register}.
 */
public final class WebServer implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(WebServer.class.getName());
    private static final int BACKLOG = 128;
    /** Static files served under {@code /static/}: name → content type. */
    private static final Map<String, String> STATIC_FILES = Map.of(
            "style.css", "text/css; charset=utf-8",
            "app.js", "text/javascript; charset=utf-8");
    static final String STATIC_CACHE_CONTROL = "public, max-age=3600";

    private final ServerConfig config;
    private final TrustedProxies trustedProxies;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final HTTPServer http;
    private final Map<String, byte[]> staticFiles;
    private ServerSocket serverSocket;
    private Thread acceptThread;
    private volatile boolean closed;

    public WebServer(ServerConfig config) {
        this.config = config;
        this.trustedProxies = TrustedProxies.parse(config.trustedProxies());
        this.staticFiles = loadStaticFiles();
        this.http = new HTTPServerBuilder().setExecutor(executor).build();
        http.setServerName("HealthChecker");
        configureSiggiHttpTrust();
        register("/health", this::health);
        register("/static/", this::staticFile);
    }

    /**
     * Narrows SiggiHTTP's own trusted list (used for e.g. {@code X-Forwarded-Host}) from its default
     * private ranges to the configured proxies. SiggiHTTP still trusts loopback and Cloudflare
     * regardless, which is why client address and scheme come from {@link TrustedProxies}.
     */
    private void configureSiggiHttpTrust() {
        http.clearTrustedIPs();
        for (var range : trustedProxies.ranges()) {
            try {
                http.trustIP(range.toString());
            } catch (IllegalArgumentException e) {
                // SiggiHTTP's parser is less complete than ours; TrustedProxies stays authoritative.
                LOG.log(System.Logger.Level.DEBUG, "SiggiHTTP can't parse trusted proxy " + range);
            }
        }
    }

    public TrustedProxies trustedProxies() {
        return trustedProxies;
    }

    /** Registers a responder for {@code path} (a path ending in {@code /} also covers everything below it). */
    public void register(String path, HTTPResponder responder) {
        http.responderRegistry.register(path, responder);
    }

    /** Binds {@code server.bind}:{@code server.port} and starts the accept thread. */
    public synchronized void start() throws IOException {
        if (closed) throw new IllegalStateException("closed");
        if (serverSocket != null) throw new IllegalStateException("already started");
        InetAddress bindAddress = InetAddress.getByName(config.bind());
        ServerSocket socket = new ServerSocket();
        try {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(bindAddress, config.port()), BACKLOG);
        } catch (IOException e) {
            socket.close();
            throw new IOException("can't listen on " + config.bind() + ":" + config.port() + ": " + e.getMessage(), e);
        }
        serverSocket = socket;
        acceptThread = Thread.ofPlatform().name("HealthChecker-HTTP").unstarted(() -> acceptLoop(socket));
        acceptThread.start();
        LOG.log(System.Logger.Level.INFO, "Web server listening on " + config.bind() + ":" + socket.getLocalPort());
    }

    /** The port actually listened on (useful with port 0 in tests), or -1 before {@link #start()}. */
    public synchronized int port() {
        return serverSocket == null ? -1 : serverSocket.getLocalPort();
    }

    private void acceptLoop(ServerSocket socket) {
        while (!closed) {
            Socket client;
            try {
                client = socket.accept();
            } catch (IOException e) {
                if (closed || socket.isClosed()) return;
                LOG.log(System.Logger.Level.WARNING, "accept failed: " + e.getMessage());
                pause();
                continue;
            }
            try {
                http.handle(client);
            } catch (UncheckedIOException | IllegalStateException e) {
                LOG.log(System.Logger.Level.WARNING, "couldn't handle connection: " + e.getMessage());
                try {
                    client.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static void pause() {
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Stops accepting connections and waits briefly for the accept thread. */
    @Override
    public void close() {
        Thread thread;
        synchronized (this) {
            if (closed) return;
            closed = true;
            thread = acceptThread;
            if (serverSocket != null) {
                try {
                    serverSocket.close();
                } catch (IOException ignored) {
                }
            }
        }
        if (thread != null) {
            try {
                thread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        executor.shutdown();
    }

    // ---- built-in responders -------------------------------------------------------

    private void health(HTTPRequest request) throws Exception {
        request.allowedMethods("GET");
        byte[] body = "ok\n".getBytes(StandardCharsets.UTF_8);
        request.response.setHeader("200 OK");
        request.response.setHeader("Content-Type", "text/plain; charset=utf-8");
        request.response.setHeader("Cache-Control", "no-store");
        request.response.setHeader("Content-Length", Integer.toString(body.length));
        request.response.write(body);
    }

    private void staticFile(HTTPRequest request) throws Exception {
        String name = request.path().substring("/static/".length());
        byte[] body = staticFiles.get(name);
        if (body == null) return; // nothing written → 404
        request.allowedMethods("GET");
        request.response.setHeader("200 OK");
        request.response.setHeader("Content-Type", STATIC_FILES.get(name));
        request.response.setHeader("Cache-Control", STATIC_CACHE_CONTROL);
        request.response.setHeader("X-Content-Type-Options", "nosniff");
        request.response.setHeader("Content-Length", Integer.toString(body.length));
        request.response.write(body);
    }

    private static Map<String, byte[]> loadStaticFiles() {
        Map<String, byte[]> files = new java.util.HashMap<>();
        for (String name : STATIC_FILES.keySet()) {
            try (InputStream in = WebServer.class.getResourceAsStream("static/" + name)) {
                if (in == null) throw new IllegalStateException("missing resource static/" + name);
                files.put(name, in.readAllBytes());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return Map.copyOf(files);
    }
}
