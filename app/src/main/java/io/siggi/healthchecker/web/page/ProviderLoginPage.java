package io.siggi.healthchecker.web.page;

import com.google.gson.JsonObject;
import io.siggi.healthchecker.audit.AuditLog;
import io.siggi.healthchecker.auth.ActionRateLimiter;
import io.siggi.healthchecker.auth.AuthProviders;
import io.siggi.healthchecker.auth.ExternalLogin;
import io.siggi.healthchecker.auth.OidcException;
import io.siggi.healthchecker.auth.ProviderConfig;
import io.siggi.healthchecker.auth.SessionManager;
import io.siggi.healthchecker.auth.SignInMatch;
import io.siggi.healthchecker.auth.UserDirectory;
import io.siggi.healthchecker.web.Html;
import io.siggi.healthchecker.web.TrustedProxies;
import io.siggi.healthchecker.web.WebServer;
import io.siggi.healthchecker.web.site.Site;
import io.siggi.healthchecker.web.site.SiteResolver;
import io.siggi.http.HTTPRequest;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Sign-in with OIDC / OAuth 2.0 providers: {@code GET /auth/start/{providerId}} and
 * {@code GET /auth/callback/{providerId}}.
 * <ul>
 *   <li>Start: the started sign-in (and {@code next}) is kept in the visitor's session, then {@code 303} to the
 *       provider. {@code switch=1} asks an OIDC provider to let the person pick an account
 *       ({@code prompt=select_account}).</li>
 *   <li>Callback: the sign-in is completed and the identity resolved ({@link UserDirectory#resolve}: users first,
 *       then rules in order). Allowed: a new session, {@code 303} to {@code next}. Unknown or disabled: no session
 *       (any existing one is deleted) and a page showing the identifier to send to the administrator, with a copy
 *       button and a "different account" link; it never says anything about users, roles or rules. Such attempts
 *       are logged and recorded in the audit log ({@code login.rejected}), at most {@value #REJECTED_PER_HOUR} times
 *       per identifier per hour. Failures (state mismatch, bad token, provider error, no usable identifier) get a
 *       short page; details only go to the log.</li>
 * </ul>
 * A provider is offered on a site when it's available there and the site has a base URL for the callback;
 * otherwise both paths are {@code 404}.
 */
public final class ProviderLoginPage {
    private static final System.Logger LOG = System.getLogger(ProviderLoginPage.class.getName());
    static final int REJECTED_PER_HOUR = 5;
    static final String KEY_PENDING = "signin";
    static final String KEY_NEXT = "signin.next";
    private static final String START = "/auth/start/";
    private static final String CALLBACK = "/auth/callback/";

    private final SiteResolver sites;
    private final SessionManager sessions;
    private final UserDirectory users;
    private final AuthProviders providers;
    private final ExternalLogin external;
    private final TrustedProxies trustedProxies;
    private final ActionRateLimiter rejectedLog;
    private volatile AuditLog audit = AuditLog.disabled();

    public ProviderLoginPage(SiteResolver sites, SessionManager sessions, UserDirectory users, AuthProviders providers,
                             ExternalLogin external, TrustedProxies trustedProxies, Clock clock) {
        this.sites = sites;
        this.sessions = sessions;
        this.users = users;
        this.providers = providers;
        this.external = external;
        this.trustedProxies = trustedProxies;
        this.rejectedLog = new ActionRateLimiter(REJECTED_PER_HOUR, Duration.ofHours(1), clock);
    }

    /** Records rejected sign-ins in {@code audit}. */
    public ProviderLoginPage audit(AuditLog audit) {
        this.audit = audit;
        return this;
    }

    public void register(WebServer server) {
        server.register("/auth/", this::handle);
    }

    /** The providers shown as "Sign in with …" buttons on this site, in config order. */
    static List<ProviderConfig> offeredOn(AuthProviders providers, Site site) {
        if (providers == null || site.publicBaseUrl() == null) return List.of();
        return providers.availableOn(site.config());
    }

    /** The link that starts a sign-in with {@code provider}, returning to {@code next} afterwards. */
    static String startLink(Site site, ProviderConfig provider, String next, boolean switchAccount) {
        String query = "?next=" + URLEncoder.encode(next, StandardCharsets.UTF_8);
        return site.link(START + provider.id() + query + (switchAccount ? "&switch=1" : ""));
    }

    private void handle(HTTPRequest request) throws Exception {
        String path = request.path();
        boolean start = path.startsWith(START);
        if (!start && !path.startsWith(CALLBACK)) return; // nothing written → 404
        String id = path.substring(start ? START.length() : CALLBACK.length());
        Optional<Site> found = sites.resolve(request);
        if (found.isEmpty()) return;
        Site site = found.get();
        Optional<ProviderConfig> provider = offeredOn(providers, site).stream()
                .filter(p -> p.id().equals(id)).findFirst();
        if (provider.isEmpty()) return;
        request.allowedMethods("GET");
        if (start) {
            start(request, site, provider.get());
        } else {
            callback(request, site, provider.get());
        }
    }

    private void start(HTTPRequest request, Site site, ProviderConfig provider) throws Exception {
        Map<String, String> query = request.get();
        String next = LoginPage.safeNext(query == null ? null : query.get("next"));
        boolean switchAccount = query != null && "1".equals(query.get("switch"));
        ExternalLogin.Started started;
        try {
            started = external.start(provider, site.link(CALLBACK + provider.id()));
        } catch (OidcException e) {
            LOG.log(System.Logger.Level.WARNING, "Starting a sign-in with " + provider.id() + " failed: "
                    + e.getMessage());
            problem(request, site, "502 Bad Gateway", "Couldn't reach " + provider.label()
                    + ". Try again in a moment.", next);
            return;
        }
        sessions.put(request, Map.of(KEY_PENDING, started.pending(), KEY_NEXT, next));
        String url = started.authorizationUrl().toString();
        if (switchAccount && provider.type() == ProviderConfig.Type.OIDC) url += "&prompt=select_account";
        request.response.setHeader("303 See Other");
        request.response.setHeader("Location", url);
        request.response.setHeader("Cache-Control", "no-store");
        request.response.setHeader("Content-Length", "0");
        request.response.write(new byte[0]);
    }

    private void callback(HTTPRequest request, Site site, ProviderConfig provider) throws Exception {
        // Single use: taken out of the session before anything is checked.
        String pending = sessions.take(request, KEY_PENDING);
        String next = LoginPage.safeNext(sessions.take(request, KEY_NEXT));
        Map<String, String> query = request.get();
        JsonObject attributes;
        try {
            attributes = external.finish(provider, pending, query == null ? Map.of() : query);
        } catch (OidcException e) {
            LOG.log(System.Logger.Level.WARNING, "Sign-in with " + provider.id() + " from "
                    + trustedProxies.clientAddress(request).getHostAddress() + " failed: " + e.getMessage());
            problem(request, site, "400 Bad Request", "Signing in with " + provider.label()
                    + " didn't work. Please try again.", next);
            return;
        }
        SignInMatch match = users.resolve(provider, attributes);
        switch (match) {
            case SignInMatch.Allowed allowed -> {
                sessions.login(request, allowed.user().id(), allowed.rule(), allowed.user().displayName());
                LOG.log(System.Logger.Level.INFO, "Signed in " + allowed.user().id() + " with " + provider.id()
                        + (allowed.rule() == null ? "" : " (rule " + allowed.rule().key() + ")"));
                request.response.setHeader("303 See Other");
                request.response.setHeader("Location", site.link(next));
                request.response.setHeader("Cache-Control", "no-store");
                request.response.setHeader("Content-Length", "0");
                request.response.write(new byte[0]);
            }
            case SignInMatch.NoIdentifier none -> {
                LOG.log(System.Logger.Level.WARNING, "Sign-in with " + provider.id()
                        + " gave no usable identifier (identifyBy/idField missing, or an unverified email)");
                problem(request, site, "400 Bad Request", provider.label() + " didn't say which account you used"
                        + " in a way this site can use (for example, the email address isn't verified).", next);
            }
            case SignInMatch.Unknown unknown -> rejected(request, site, provider, attributes, unknown.identifier(),
                    false, next);
            case SignInMatch.Disabled disabled -> rejected(request, site, provider, attributes,
                    disabled.identifier(), true, next);
        }
    }

    /** Not allowed (yet): no session, the identifier to send to the administrator. */
    private void rejected(HTTPRequest request, Site site, ProviderConfig provider, JsonObject attributes,
                          String identifier, boolean disabled, String next) throws Exception {
        // No session for this person, and none left over from before (e.g. someone else signed in on this browser).
        sessions.logout(request);
        Optional<String> name = provider.nameHint(attributes);
        Optional<String> email = provider.emailHint(attributes);
        String ip = trustedProxies.clientAddress(request).getHostAddress();
        if (rejectedLog.tryAcquire(identifier).isEmpty()) {
            LOG.log(System.Logger.Level.INFO, "Rejected sign-in with " + provider.id() + " as " + identifier
                    + name.map(n -> " (" + n + ")").orElse("") + " from " + ip
                    + (disabled ? ": user disabled" : ": no user or rule matches"));
            JsonObject after = new JsonObject();
            after.addProperty("provider", provider.id());
            after.addProperty("identifier", identifier);
            after.addProperty("displayName", name.orElse(null));
            after.addProperty("email", email.orElse(null));
            after.addProperty("ip", ip);
            after.addProperty("reason", disabled ? "disabled" : "unknown");
            audit.record(null, null, "login.rejected", identifier, null, after);
        }

        StringBuilder html = new StringBuilder(4096);
        PageSupport.head(html, site, (disabled ? "Access disabled" : "No access yet") + " · " + site.title(), false,
                null);
        html.append("<section class=\"login signin-rejected\">\n<h2>")
                .append(disabled ? "Access disabled" : "No access yet").append("</h2>\n<p>You signed in with <strong>")
                .append(Html.escape(provider.label())).append("</strong>");
        if (name.isPresent()) html.append(" as <em>").append(Html.escape(name.get())).append("</em>");
        if (email.isPresent() && !email.equals(name)) {
            html.append(name.isPresent() ? " (" : " as ").append(Html.escape(email.get()))
                    .append(name.isPresent() ? ")" : "");
        }
        if (disabled) {
            html.append(", but access for this account has been disabled.</p>\n")
                    .append("<p>If you think this is a mistake, contact the administrator and mention this"
                            + " identifier:</p>\n");
        } else {
            html.append(", but this account doesn't have access yet.</p>\n")
                    .append("<p>Send this identifier to the administrator and ask them to allow it:</p>\n");
        }
        html.append("<p class=\"identifier\"><code id=\"signin-identifier\">").append(Html.escape(identifier))
                .append("</code> <button type=\"button\" class=\"copy-button\" data-copy=\"signin-identifier\"")
                .append(" hidden>Copy</button></p>\n")
                .append("<p><a href=\"").append(Html.escape(startLink(site, provider, next, true)))
                .append("\" rel=\"nofollow\">Sign in with a different account</a></p>\n")
                .append("<p><a href=\"").append(Html.escape(site.link("/"))).append("\">Back to the status page</a></p>\n")
                .append("</section>\n");
        PageSupport.foot(html, site);
        writePage(request, "403 Forbidden", html);
    }

    private void problem(HTTPRequest request, Site site, String status, String message, String next)
            throws Exception {
        StringBuilder html = new StringBuilder(2048);
        PageSupport.head(html, site, "Sign-in failed · " + site.title(), false, null);
        html.append("<section class=\"login\">\n<h2>Sign-in failed</h2>\n<p class=\"form-error\" role=\"alert\">")
                .append(Html.escape(message)).append("</p>\n<p><a href=\"")
                .append(Html.escape(site.link("/login?next=" + URLEncoder.encode(next, StandardCharsets.UTF_8))))
                .append("\">Back to sign-in</a></p>\n</section>\n");
        PageSupport.foot(html, site);
        writePage(request, status, html);
    }

    private static void writePage(HTTPRequest request, String status, StringBuilder html) throws Exception {
        request.response.setHeader("X-Frame-Options", "DENY");
        request.response.setHeader("Content-Security-Policy", "frame-ancestors 'none'");
        request.response.setHeader("Referrer-Policy", "no-referrer");
        PageSupport.write(request, status, "text/html; charset=utf-8", html.toString());
    }
}
