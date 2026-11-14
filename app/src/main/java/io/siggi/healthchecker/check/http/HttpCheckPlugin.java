package io.siggi.healthchecker.check.http;

import io.siggi.healthchecker.api.CheckPlugin;
import io.siggi.healthchecker.api.CheckResult;
import io.siggi.healthchecker.api.Checker;
import io.siggi.healthchecker.api.ConfigException;
import io.siggi.healthchecker.api.EndpointConfig;
import io.siggi.healthchecker.api.SettingSpec;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.net.ssl.SSLException;

/**
 * The built-in {@code http} check: {@code GET {url}/}, passing when a response arrives and
 * {@code statusCode / 100 <= maxClass}. Redirects are not followed, so a 3xx is observable.
 * No response (refused, DNS failure, TLS error, timeout, malformed response) always fails.
 *
 * <p>All checkers share one {@link HttpClient}, created on first use.
 */
public final class HttpCheckPlugin implements CheckPlugin {
    public static final String TYPE = "http";
    public static final int MIN_CLASS = 2;
    public static final int MAX_CLASS = 5;

    private static final String USER_AGENT = "HealthChecker";

    private volatile HttpClient client;

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public String displayName() {
        return "HTTP";
    }

    @Override
    public List<SettingSpec> settings() {
        return List.of(
                SettingSpec.string("url", "URL").asRequired()
                        .help("http:// or https:// with a host and optional port; no path, query or fragment"),
                SettingSpec.integer("maxClass", "Highest passing status class").asRequired()
                        .help("2: only 2xx passes; 3: up to 3xx; 4: up to 4xx; 5: any HTTP response"));
    }

    @Override
    public Checker create(EndpointConfig config) throws ConfigException {
        // Read both settings before validating, so neither is reported as unread.
        String url = config.getString("url");
        int maxClass = config.getInt("maxClass");
        URI target = parseUrl(url);
        if (maxClass < MIN_CLASS || maxClass > MAX_CLASS) {
            throw new ConfigException("maxClass", "maxClass must be 2, 3, 4 or 5, got " + maxClass);
        }
        config.declareTarget("url", url);
        config.declareTarget("host", stripBrackets(target.getHost()));
        return new HttpChecker(this, target, maxClass);
    }

    /**
     * Validates {@code url} and returns the URI to request: scheme, authority and {@code "/"}.
     * The URL must be absolute {@code http}/{@code https} with a host, no user info, a path that
     * is empty or {@code "/"}, and no query or fragment.
     */
    static URI parseUrl(String url) throws ConfigException {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new ConfigException("url", "url is not a valid URL");
        }
        String scheme = uri.getScheme();
        if (scheme == null) {
            throw new ConfigException("url", "url must be absolute, starting with http:// or https://");
        }
        scheme = scheme.toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new ConfigException("url", "url scheme must be http or https");
        }
        if (uri.isOpaque() || uri.getHost() == null || uri.getHost().isEmpty()) {
            throw new ConfigException("url", "url must have a host");
        }
        if (uri.getRawUserInfo() != null) {
            throw new ConfigException("url", "url must not contain user info");
        }
        if (uri.getPort() > 65535) {
            throw new ConfigException("url", "url port must be 1-65535");
        }
        if (uri.getPort() == 0) {
            throw new ConfigException("url", "url port must be 1-65535");
        }
        String path = uri.getRawPath();
        if (path != null && !path.isEmpty() && !path.equals("/")) {
            throw new ConfigException("url", "url must not have a path (only \"/\" is allowed)");
        }
        if (uri.getRawQuery() != null) {
            throw new ConfigException("url", "url must not have a query");
        }
        if (uri.getRawFragment() != null) {
            throw new ConfigException("url", "url must not have a fragment");
        }
        return URI.create(scheme + "://" + uri.getRawAuthority() + "/");
    }

    private static String stripBrackets(String host) {
        if (host.length() > 2 && host.startsWith("[") && host.endsWith("]")) {
            return host.substring(1, host.length() - 1);
        }
        return host;
    }

    HttpClient client() {
        HttpClient c = client;
        if (c == null) {
            synchronized (this) {
                c = client;
                if (c == null) {
                    c = HttpClient.newBuilder()
                            .followRedirects(HttpClient.Redirect.NEVER)
                            .version(HttpClient.Version.HTTP_1_1)
                            .build();
                    client = c;
                }
            }
        }
        return c;
    }

    /** Passes when {@code statusCode / 100 <= maxClass}. */
    static boolean passes(int statusCode, int maxClass) {
        return statusCode >= 100 && statusCode / 100 <= maxClass;
    }

    private static final class HttpChecker implements Checker {
        private final HttpCheckPlugin plugin;
        private final URI uri;
        private final int maxClass;

        HttpChecker(HttpCheckPlugin plugin, URI uri, int maxClass) {
            this.plugin = plugin;
            this.uri = uri;
            this.maxClass = maxClass;
        }

        @Override
        public CheckResult check(Duration timeout) throws InterruptedException {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .GET()
                    .timeout(timeout)
                    .header("User-Agent", USER_AGENT)
                    .build();
            CompletableFuture<HttpResponse<Void>> future =
                    plugin.client().sendAsync(request, HttpResponse.BodyHandlers.discarding());
            HttpResponse<Void> response;
            try {
                response = future.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
                return timeoutResult(timeout);
            } catch (InterruptedException e) {
                future.cancel(true);
                throw e;
            } catch (ExecutionException e) {
                return failure(e.getCause(), timeout);
            }
            int status = response.statusCode();
            if (passes(status, maxClass)) {
                return CheckResult.ok("status " + status).with("statusCode", status);
            }
            return CheckResult.fail("status " + status + ", expected at most " + maxClass + "xx")
                    .with("statusCode", status);
        }

        private static CheckResult timeoutResult(Duration timeout) {
            return CheckResult.fail("timeout after " + timeout.toMillis() + " ms");
        }

        /**
         * Describes a failure without its exception message, which can name the target
         * (host, address, certificate names).
         */
        private static CheckResult failure(Throwable t, Duration timeout) {
            if (t instanceof HttpConnectTimeoutException) {
                return CheckResult.fail("connect timeout after " + timeout.toMillis() + " ms");
            }
            if (t instanceof HttpTimeoutException) {
                return timeoutResult(timeout);
            }
            for (Throwable c = t; c != null; c = c.getCause()) {
                if (c instanceof UnknownHostException || c instanceof UnresolvedAddressException) {
                    return CheckResult.fail("host not found");
                }
                if (c instanceof ConnectException) {
                    return CheckResult.fail("connection failed");
                }
                if (c instanceof SSLException) {
                    return CheckResult.fail("TLS error");
                }
            }
            if (t instanceof IOException) {
                return CheckResult.fail("no valid HTTP response (" + t.getClass().getSimpleName() + ")");
            }
            return CheckResult.fail("request failed (" + (t == null ? "unknown" : t.getClass().getSimpleName()) + ")");
        }
    }
}
