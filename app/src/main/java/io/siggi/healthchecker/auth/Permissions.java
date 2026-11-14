package io.siggi.healthchecker.auth;

import io.siggi.healthchecker.web.site.Site;
import io.siggi.healthchecker.web.view.Viewer;
import io.siggi.http.HTTPRequest;
import io.siggi.http.session.SessionHandle;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.function.Function;

/**
 * The one place responders ask who is signed in and what they may do.
 * <ul>
 *   <li>The session stores only the user id; the user (and so their role and permissions) is looked
 *       up fresh on every request. A session granted by a rule ({@code auth.rules}) stores the identifier, the
 *       rule's key and the provider's display name, and is checked against the current rules.</li>
 *   <li>A session whose user is disabled or no longer configured is ended (deleted, cookie cleared)
 *       on that request, which then counts as anonymous.</li>
 *   <li>{@link #viewer} gives the pages' {@link Viewer}: {@code view.private} decides the data view, so
 *       a signed-in user without it sees what anonymous visitors see; the header still shows them.</li>
 *   <li>{@link #require} enforces a permission: anonymous visitors are redirected to {@code /login},
 *       signed-in users without it get {@code 403}.</li>
 * </ul>
 */
public final class Permissions {
    private static final System.Logger LOG = System.getLogger(Permissions.class.getName());

    private final SessionManager sessions;
    private final Function<String, Optional<User>> users;
    private final RuleUsers ruleUsers;

    /** Looks up the user of a session a rule granted ({@link UserDirectory#ruleUser}). */
    @FunctionalInterface
    public interface RuleUsers {
        Optional<User> ruleUser(String identifier, String ruleKey, String displayName);
    }

    /** @param users looks a user up by id ({@link UserDirectory#user}) */
    public Permissions(SessionManager sessions, Function<String, Optional<User>> users) {
        this(sessions, users, (identifier, ruleKey, displayName) -> Optional.empty());
    }

    /** Users and rule-granted sessions from {@code directory}. */
    public Permissions(SessionManager sessions, UserDirectory directory) {
        this(sessions, directory::user, directory::ruleUser);
    }

    public Permissions(SessionManager sessions, Function<String, Optional<User>> users, RuleUsers ruleUsers) {
        this.sessions = sessions;
        this.users = users;
        this.ruleUsers = ruleUsers;
    }

    /** The signed-in user and their session's CSRF token. */
    public record SignedIn(User user, String csrfToken) {
        @Override
        public String toString() {
            return "SignedIn[user=" + user.id() + "]";
        }
    }

    /**
     * The request's signed-in, enabled user, or empty. Ends the session when its user is disabled or
     * unknown, so call it before the response headers are sent.
     */
    public Optional<SignedIn> signedIn(HTTPRequest request) {
        SessionHandle session = sessions.read(request);
        String id = session.get(SessionManager.KEY_USER);
        if (id == null) return Optional.empty();
        String rule = session.get(SessionManager.KEY_RULE);
        Optional<User> user = rule != null
                ? ruleUsers.ruleUser(id, rule, session.get(SessionManager.KEY_NAME))
                : users.apply(id);
        if (user.isEmpty() || user.get().disabled()) {
            LOG.log(System.Logger.Level.INFO, "Ended a session of " + (user.isEmpty() ? "an unknown" : "a disabled")
                    + " user");
            sessions.logout(request);
            return Optional.empty();
        }
        return Optional.of(new SignedIn(user.get(), session.get(SessionManager.KEY_CSRF)));
    }

    /** The request's signed-in, enabled user, or empty (see {@link #signedIn}). */
    public Optional<User> user(HTTPRequest request) {
        return signedIn(request).map(SignedIn::user);
    }

    /** Who the pages and APIs show data for. */
    public Viewer viewer(HTTPRequest request) {
        return signedIn(request).map(Permissions::viewer).orElse(Viewer.ANONYMOUS);
    }

    static Viewer viewer(SignedIn signedIn) {
        User user = signedIn.user();
        return new Viewer(user.displayName(), user.role().name(), user.has(Permission.VIEW_PRIVATE),
                signedIn.csrfToken());
    }

    /** Whether the request's user has {@code permission} (never for anonymous visitors). */
    public boolean has(HTTPRequest request, Permission permission) {
        return user(request).map(u -> u.has(permission)).orElse(false);
    }

    /**
     * Enforces {@code permission}. Without it, answers and returns false, so a responder can
     * {@code if (!permissions.require(request, site, Permission.X)) return;}: anonymous visitors get
     * {@code 303} to {@code /login?next=<this path and query>} (for a GET; otherwise {@code next=/}), signed-in users {@code 403}.
     */
    public boolean require(HTTPRequest request, Site site, Permission permission) throws IOException {
        Optional<User> user = user(request);
        if (user.isPresent() && user.get().has(permission)) return true;
        if (user.isEmpty()) {
            String next = request.method().equals("GET") && request.fullUrl != null
                    && request.fullUrl.startsWith("/") ? request.fullUrl : "/";
            request.response.setHeader("303 See Other");
            request.response.setHeader("Location",
                    site.link("/login?next=" + URLEncoder.encode(next, StandardCharsets.UTF_8)));
            request.response.setHeader("Cache-Control", "no-store");
            request.response.setHeader("Content-Length", "0");
            request.response.write(new byte[0]);
            return false;
        }
        byte[] body = "You don't have permission to do this.\n".getBytes(StandardCharsets.UTF_8);
        request.response.setHeader("403 Forbidden");
        request.response.setHeader("Content-Type", "text/plain; charset=utf-8");
        request.response.setHeader("Cache-Control", "no-store");
        request.response.setHeader("Content-Length", Integer.toString(body.length));
        request.response.write(body);
        return false;
    }
}
