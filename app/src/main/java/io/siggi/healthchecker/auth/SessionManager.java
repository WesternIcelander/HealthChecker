package io.siggi.healthchecker.auth;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import io.siggi.healthchecker.web.TrustedProxies;
import io.siggi.http.HTTPRequest;
import io.siggi.http.cookie.SameSite;
import io.siggi.http.session.SessionHandle;
import io.siggi.http.session.Sessions;
import io.siggi.http.session.SessionsBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Login sessions and CSRF tokens, on SiggiHTTP's sessions API ({@link SessionsBuilder}; none of the
 * deprecated session methods).
 * <ul>
 *   <li>Reads ({@link #read}, {@link #userId}) never create a session or set a cookie, so anonymous
 *       visitors get no cookie. Reading a live session counts as a use (sliding expiry) and refreshes
 *       the cookie's {@code Max-Age}.</li>
 *   <li>{@link #login} starts a new session with a new ID (against session fixation),
 *       {@link #logout} deletes it and clears the cookie.</li>
 *   <li>The session cookie is {@code Path=/; HttpOnly; SameSite=Lax}, plus {@code Secure} exactly when
 *       {@link TrustedProxies#isSecure} says the client used HTTPS. SiggiHTTP decides {@code Secure} with
 *       {@link HTTPRequest#isSecure()}, which always trusts loopback and Cloudflare, so the session
 *       cookie headers are rewritten after every session call.</li>
 *   <li>The CSRF token lives in the session; forms send it as {@value #CSRF_FIELD}, scripts as the
 *       {@value #CSRF_HEADER} header.</li>
 * </ul>
 */
public final class SessionManager {
    public static final int DEFAULT_SESSION_DAYS = 7;
    public static final int MAX_SESSION_DAYS = 365;
    /** Form field carrying the CSRF token. */
    public static final String CSRF_FIELD = "csrf";
    /** Header carrying the CSRF token (for requests with a JSON body). */
    public static final String CSRF_HEADER = "X-CSRF-Token";

    static final String KEY_USER = "user";
    static final String KEY_CSRF = "csrf";
    /** For a session a rule granted: the rule's {@link Rule#key()}. */
    static final String KEY_RULE = "rule";
    /** For a session a rule granted: the display name from the provider. */
    static final String KEY_NAME = "name";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Sessions sessions;
    private final TrustedProxies trustedProxies;

    /**
     * @param directory where sessions are stored (created if missing), so logins survive restarts
     * @param lifetime  idle timeout; the cookie's {@code Max-Age} matches it (at least one second)
     */
    public SessionManager(Path directory, Duration lifetime, TrustedProxies trustedProxies) {
        this.trustedProxies = trustedProxies;
        this.sessions = new SessionsBuilder()
                .setDirectory(directory.toFile())
                .setExpireTime(lifetime.toMillis())
                .setCookieMaxAge(Math.max(1L, lifetime.toSeconds()))
                .setSameSite(SameSite.LAX)
                .build();
    }

    /** The standard setup: {@code {dataDir}/sessions}, lifetime {@code sessionDays}. */
    public static SessionManager forDataDir(Path dataDir, int sessionDays, TrustedProxies trustedProxies) {
        return new SessionManager(dataDir.resolve("sessions"), Duration.ofDays(sessionDays), trustedProxies);
    }

    /**
     * {@code auth.sessionDays}: a whole number from 1 to {@value #MAX_SESSION_DAYS}, default
     * {@value #DEFAULT_SESSION_DAYS}.
     *
     * @param auth the raw {@code auth} section, or {@code null}
     * @throws IllegalArgumentException with a message naming the key if the value is invalid
     */
    public static int sessionDays(JsonObject auth) {
        JsonElement value = auth == null ? null : auth.get("sessionDays");
        if (value == null || value.isJsonNull()) return DEFAULT_SESSION_DAYS;
        if (value instanceof JsonPrimitive p && p.isNumber()) {
            double d = p.getAsDouble();
            if (d == Math.rint(d) && d >= 1 && d <= MAX_SESSION_DAYS) return (int) d;
        }
        throw new IllegalArgumentException("auth.sessionDays must be a whole number from 1 to " + MAX_SESSION_DAYS);
    }

    public String cookieName() {
        return sessions.getCookieName();
    }

    /** A read-only copy of the request's session (empty when there is none). Never sets a new cookie. */
    public SessionHandle read(HTTPRequest request) {
        try {
            return sessions.readSession(request);
        } finally {
            fixCookies(request);
        }
    }

    /** The logged-in user's id, if the request has a live session with one. */
    public Optional<String> userId(HTTPRequest request) {
        return Optional.ofNullable(read(request).get(KEY_USER));
    }

    /**
     * Logs {@code userId} in: the old session (if any) is deleted and a new one with a new ID and a
     * new CSRF token is started. Must be called before the response headers are sent.
     */
    public void login(HTTPRequest request, String userId) {
        login(request, userId, null, null);
    }

    /**
     * {@link #login(HTTPRequest, String)} for a user signed in through an OIDC/OAuth provider.
     *
     * @param rule        the rule that granted access ({@code userId} is then the identifier), or {@code null}
     * @param displayName for a rule, the name from the provider; ignored otherwise
     */
    public void login(HTTPRequest request, String userId, Rule rule, String displayName) {
        try (SessionHandle session = sessions.resetSession(request)) {
            session.set(KEY_USER, userId);
            session.set(KEY_CSRF, newToken());
            if (rule != null) {
                session.set(KEY_RULE, rule.key());
                session.set(KEY_NAME, displayName);
            }
        } finally {
            fixCookies(request);
        }
    }

    /**
     * Stores values in the request's session, starting one if needed (which sets the session cookie), so call
     * it once per request (a second call on a request without a session would start another one) and before the
     * response headers are sent.
     */
    public void put(HTTPRequest request, Map<String, String> values) {
        try (SessionHandle session = sessions.openSession(request)) {
            values.forEach(session::set);
        } finally {
            fixCookies(request);
        }
    }

    /** Removes a value from the request's session and returns it ({@code null} without a session or value). */
    public String take(HTTPRequest request, String key) {
        if (read(request).get(key) == null) return null;
        try (SessionHandle session = sessions.openSession(request)) {
            return session.set(key, null);
        } finally {
            fixCookies(request);
        }
    }

    /** Deletes the session and clears the cookie (if the client sent one). */
    public void logout(HTTPRequest request) {
        try {
            sessions.deleteSession(request);
        } finally {
            fixCookies(request);
        }
    }

    /**
     * The request's CSRF token for a form. A logged-in session already has one; otherwise (e.g. on
     * the login form) a session is started to hold it, which sets the session cookie, so call this
     * only on pages that need a form and before the response headers are sent.
     */
    public String csrfToken(HTTPRequest request) {
        String token = read(request).get(KEY_CSRF);
        if (token != null) return token;
        try (SessionHandle session = sessions.openSession(request)) {
            token = session.get(KEY_CSRF);
            if (token == null) {
                token = newToken();
                session.set(KEY_CSRF, token);
            }
            return token;
        } finally {
            fixCookies(request);
        }
    }

    /**
     * Whether the request carries its session's CSRF token (form field {@value #CSRF_FIELD} or header
     * {@value #CSRF_HEADER}), compared in constant time. False without a session or token.
     */
    public boolean csrfValid(HTTPRequest request) {
        String sent = null;
        Map<String, String> post = request.post();
        if (post != null) sent = post.get(CSRF_FIELD);
        if (sent == null) sent = request.getHeader(CSRF_HEADER);
        if (sent == null || sent.isEmpty()) return false;
        String expected = read(request).get(KEY_CSRF);
        return expected != null && MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), sent.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Checks the CSRF token; if it's missing or wrong, answers {@code 403} and returns false, so a
     * responder can simply {@code if (!sessions.requireCsrf(request)) return;}.
     */
    public boolean requireCsrf(HTTPRequest request) throws IOException {
        if (csrfValid(request)) return true;
        byte[] body = "Invalid or missing CSRF token. Reload the page and try again.\n"
                .getBytes(StandardCharsets.UTF_8);
        request.response.setHeader("403 Forbidden");
        request.response.setHeader("Content-Type", "text/plain; charset=utf-8");
        request.response.setHeader("Cache-Control", "no-store");
        request.response.setHeader("Content-Length", Integer.toString(body.length));
        request.response.write(body);
        return false;
    }

    private static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /**
     * Rewrites the session cookie headers SiggiHTTP added: {@code Secure} as {@link TrustedProxies}
     * decides, and only the last one kept (a read followed by a write may each have set it).
     */
    private void fixCookies(HTTPRequest request) {
        if (request.alreadyWrote()) return;
        String[] headers = request.response.getHeader("Set-Cookie");
        String prefix = sessions.getCookieName() + "=";
        int last = -1;
        for (int i = 0; i < headers.length; i++) {
            if (headers[i].startsWith(prefix)) last = i;
        }
        if (last < 0) return;
        boolean secure = trustedProxies.isSecure(request);
        request.response.deleteHeader("Set-Cookie");
        for (int i = 0; i < headers.length; i++) {
            if (!headers[i].startsWith(prefix)) {
                request.response.addHeader("Set-Cookie", headers[i]);
            } else if (i == last) {
                request.response.addHeader("Set-Cookie", withSecure(headers[i], secure));
            }
        }
    }

    static String withSecure(String setCookie, boolean secure) {
        List<String> parts = new ArrayList<>(List.of(setCookie.split("; ", -1)));
        parts.removeIf(part -> part.equalsIgnoreCase("Secure"));
        if (secure) {
            int httpOnly = parts.indexOf("HttpOnly");
            parts.add(httpOnly < 0 ? parts.size() : httpOnly + 1, "Secure");
        }
        return String.join("; ", parts);
    }
}
