package io.siggi.healthchecker.web.page;

import io.siggi.healthchecker.audit.AuditLog;
import io.siggi.healthchecker.auth.AuthProviders;
import io.siggi.healthchecker.auth.LocalLogin;
import io.siggi.healthchecker.auth.ProviderConfig;
import io.siggi.healthchecker.auth.Permissions;
import io.siggi.healthchecker.auth.SessionManager;
import io.siggi.healthchecker.web.Html;
import io.siggi.healthchecker.web.TrustedProxies;
import io.siggi.healthchecker.web.WebServer;
import io.siggi.healthchecker.web.site.Site;
import io.siggi.healthchecker.web.site.SiteResolver;
import io.siggi.http.HTTPRequest;

import java.net.InetAddress;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code /login} and {@code /logout}.
 * <ul>
 *   <li>{@code GET /login} shows the password form (with a CSRF token, which starts a pre-login
 *       session; left out when {@code auth.local.enabled} is false) and a "Sign in with …" button per
 *       provider offered on the site, or, when already signed in, who is signed in and a sign-out button.</li>
 *   <li>{@code POST /login} needs the CSRF token ({@code 403} without it), then answers {@code 303}
 *       to {@code next} on success (with a new session ID), {@code 401} with the form for a wrong
 *       username or password, and {@code 429} with {@code Retry-After} while rate limited.</li>
 *   <li>{@code POST /logout} needs the CSRF token, deletes the session and answers {@code 303} to {@code /}.</li>
 * </ul>
 * {@code next} is only ever a path on this site ({@link #safeNext}). Rejected hosts get {@code 404}.
 */
public final class LoginPage {
    private final SiteResolver sites;
    private final SessionManager sessions;
    private final LocalLogin local;
    private final TrustedProxies trustedProxies;
    private final Permissions permissions;

    public LoginPage(SiteResolver sites, SessionManager sessions, Permissions permissions, LocalLogin local,
                     TrustedProxies trustedProxies) {
        this.sites = sites;
        this.sessions = sessions;
        this.permissions = permissions;
        this.local = local;
        this.trustedProxies = trustedProxies;
    }

    private volatile AuditLog audit = AuditLog.disabled();
    private volatile AuthProviders providers;

    /** Shows a "Sign in with …" button for each provider offered on the site ({@link ProviderLoginPage}). */
    public LoginPage providers(AuthProviders providers) {
        this.providers = providers;
        return this;
    }

    /** Records failed sign-ins (with the client address only) in {@code audit}. */
    public LoginPage audit(AuditLog audit) {
        this.audit = audit;
        return this;
    }

    public void register(WebServer server) {
        server.register("/login", this::login);
        server.register("/logout", this::logout);
    }

    /**
     * A redirect target after sign-in: a path on this site starting with a single {@code /}, made
     * of printable ASCII without backslashes, at most 2048 characters. Anything else becomes {@code /},
     * so it can't redirect to another host.
     */
    static String safeNext(String next) {
        if (next == null || next.isEmpty() || next.length() > 2048) return "/";
        if (!next.startsWith("/") || next.startsWith("//")) return "/";
        for (int i = 0; i < next.length(); i++) {
            char c = next.charAt(i);
            if (c <= ' ' || c >= 0x7f || c == '\\') return "/";
        }
        return next;
    }

    private void login(HTTPRequest request) throws Exception {
        if (!request.path().equals("/login")) return; // nothing written → 404
        Optional<Site> found = sites.resolve(request);
        if (found.isEmpty()) return;
        request.allowedMethods("GET", "POST");
        Site site = found.get();
        if (request.method().equals("POST")) {
            submit(request, site);
            return;
        }
        String next = safeNext(request.get().get("next"));
        Optional<Permissions.SignedIn> user = permissions.signedIn(request);
        if (user.isPresent()) {
            signedIn(request, site, user.get(), next);
        } else {
            form(request, site, "200 OK", null, "", next);
        }
    }

    private void submit(HTTPRequest request, Site site) throws Exception {
        if (!local.enabled()) return; // nothing written → 404
        if (!sessions.requireCsrf(request)) return;
        Map<String, String> post = request.post();
        String username = value(post, "username");
        String next = safeNext(value(post, "next"));
        char[] password = value(post, "password").toCharArray();
        LocalLogin.Result result;
        InetAddress client = trustedProxies.clientAddress(request);
        try {
            result = local.attempt(username, password, client);
        } finally {
            Arrays.fill(password, '\0');
        }
        switch (result) {
            case LocalLogin.Result.Success success -> {
                sessions.login(request, success.userId());
                request.response.setHeader("303 See Other");
                request.response.setHeader("Location", site.link(next));
                request.response.setHeader("Cache-Control", "no-store");
                request.response.setHeader("Content-Length", "0");
                request.response.write(new byte[0]);
            }
            case LocalLogin.Result.Failure failure -> {
                // Never the username or password: either may be a mistyped password.
                audit.record(null, null, "login.failed", client.getHostAddress(), null, null);
                form(request, site, "401 Unauthorized", "Wrong username or password.", username, next);
            }
            case LocalLogin.Result.RateLimited limited -> {
                long seconds = Math.max(1, ceilSeconds(limited.retryAfter()));
                request.response.setHeader("Retry-After", Long.toString(seconds));
                form(request, site, "429 Too Many Requests", "Too many failed sign-in attempts. Try again in "
                        + waitText(seconds) + ".", username, next);
            }
        }
    }

    private void logout(HTTPRequest request) throws Exception {
        if (!request.path().equals("/logout")) return;
        Optional<Site> found = sites.resolve(request);
        if (found.isEmpty()) return;
        request.allowedMethods("POST");
        if (!sessions.requireCsrf(request)) return;
        sessions.logout(request);
        request.response.setHeader("303 See Other");
        request.response.setHeader("Location", found.get().link("/"));
        request.response.setHeader("Cache-Control", "no-store");
        request.response.setHeader("Content-Length", "0");
        request.response.write(new byte[0]);
    }

    private void form(HTTPRequest request, Site site, String status, String error, String username, String next)
            throws Exception {
        StringBuilder html = new StringBuilder(4096);
        PageSupport.head(html, site, "Sign in · " + site.title(), false, null);
        html.append("<section class=\"login\">\n<h2>Sign in</h2>\n");
        if (error != null) {
            html.append("<p class=\"form-error\" role=\"alert\">").append(Html.escape(error)).append("</p>\n");
        }
        if (local.enabled()) {
            String token = sessions.csrfToken(request);
            html.append("<form method=\"post\" action=\"").append(Html.escape(site.link("/login"))).append("\">\n")
                    .append("<input type=\"hidden\" name=\"").append(SessionManager.CSRF_FIELD).append("\" value=\"")
                    .append(Html.escape(token)).append("\">\n")
                    .append("<input type=\"hidden\" name=\"next\" value=\"").append(Html.escape(next)).append("\">\n")
                    .append("<label for=\"login-username\">Username</label>\n")
                    .append("<input id=\"login-username\" name=\"username\" autocomplete=\"username\" required")
                    .append(" autocapitalize=\"none\" spellcheck=\"false\" value=\"").append(Html.escape(username))
                    .append("\">\n")
                    .append("<label for=\"login-password\">Password</label>\n")
                    .append("<input id=\"login-password\" name=\"password\" type=\"password\"")
                    .append(" autocomplete=\"current-password\" required>\n")
                    .append("<button type=\"submit\">Sign in</button>\n</form>\n");
        }
        List<ProviderConfig> offered = ProviderLoginPage.offeredOn(providers, site);
        if (!offered.isEmpty()) {
            if (local.enabled()) html.append("<p class=\"login-or\">or</p>\n");
            html.append("<div class=\"login-providers\">\n");
            for (ProviderConfig provider : offered) {
                html.append("<a class=\"button provider-button\" rel=\"nofollow\" href=\"")
                        .append(Html.escape(ProviderLoginPage.startLink(site, provider, next, false)))
                        .append("\">Sign in with ").append(Html.escape(provider.label())).append("</a>\n");
            }
            html.append("</div>\n");
        } else if (!local.enabled()) {
            html.append("<p class=\"muted\">No sign-in method is available here.</p>\n");
        }
        html.append("</section>\n");
        PageSupport.foot(html, site);
        writePage(request, status, html);
    }

    private void signedIn(HTTPRequest request, Site site, Permissions.SignedIn user, String next) throws Exception {
        String token = sessions.csrfToken(request);
        StringBuilder html = new StringBuilder(2048);
        PageSupport.head(html, site, "Signed in · " + site.title(), false, null);
        html.append("<section class=\"login\">\n<h2>Signed in</h2>\n<p>You're signed in as <strong>")
                .append(Html.escape(user.user().displayName())).append("</strong> (")
                .append(Html.escape(user.user().role().name())).append(").</p>\n<p><a href=\"")
                .append(Html.escape(site.link(next))).append("\">Continue</a></p>\n")
                .append("<form method=\"post\" action=\"").append(Html.escape(site.link("/logout"))).append("\">\n")
                .append("<input type=\"hidden\" name=\"").append(SessionManager.CSRF_FIELD).append("\" value=\"")
                .append(Html.escape(token)).append("\">\n")
                .append("<button type=\"submit\">Sign out</button>\n</form>\n</section>\n");
        PageSupport.foot(html, site);
        writePage(request, "200 OK", html);
    }

    private static void writePage(HTTPRequest request, String status, StringBuilder html) throws Exception {
        request.response.setHeader("X-Frame-Options", "DENY");
        request.response.setHeader("Content-Security-Policy", "frame-ancestors 'none'");
        PageSupport.write(request, status, "text/html; charset=utf-8", html.toString());
    }

    private static String value(Map<String, String> post, String key) {
        String value = post == null ? null : post.get(key);
        return value == null ? "" : value;
    }

    private static long ceilSeconds(Duration duration) {
        long seconds = duration.getSeconds();
        return duration.getNano() > 0 ? seconds + 1 : seconds;
    }

    private static String waitText(long seconds) {
        if (seconds < 60) return seconds == 1 ? "1 second" : seconds + " seconds";
        long minutes = (seconds + 59) / 60;
        return minutes == 1 ? "1 minute" : minutes + " minutes";
    }
}
