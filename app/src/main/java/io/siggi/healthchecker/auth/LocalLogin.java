package io.siggi.healthchecker.auth;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.net.InetAddress;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Built-in username/password sign-in.
 * <ul>
 *   <li>Every attempt that isn't rate limited costs one PBKDF2 verification: unknown users, users
 *       without a password and over-long input are checked against a dummy hash, so timing doesn't
 *       reveal which usernames exist. Hash comparison is constant-time ({@link PasswordHasher#verify}).</li>
 *   <li>Failures are counted per client IP and per username ({@link LoginRateLimiter}); while either
 *       is blocked, attempts are refused without checking the password. A success clears the
 *       username's failures (not the IP's).</li>
 * </ul>
 */
public final class LocalLogin {
    private static final System.Logger LOG = System.getLogger(LocalLogin.class.getName());
    /** Passwords longer than this always fail (still costing a dummy verification). */
    static final int MAX_PASSWORD_LENGTH = 1024;

    /** Looks up a user's password hash by username. Empty for unknown users and users without a password. */
    @FunctionalInterface
    public interface Credentials {
        Optional<String> passwordHash(String username);
    }

    /** The outcome of a sign-in attempt. */
    public sealed interface Result {
        /** The password was right; {@code userId} is the user to log in. */
        record Success(String userId) implements Result {
        }

        /** Wrong username or password (deliberately not saying which). */
        record Failure() implements Result {
        }

        /** Too many recent failures for this IP or username; nothing was checked. */
        record RateLimited(Duration retryAfter) implements Result {
        }
    }

    private final boolean enabled;
    private final Credentials credentials;
    private final LoginRateLimiter limiter;
    private final Verifier verifier;

    /** Checks a password against an encoded hash ({@link PasswordHasher#verify}); replaceable in tests. */
    @FunctionalInterface
    interface Verifier {
        boolean verify(char[] password, String encodedHash);
    }

    public LocalLogin(boolean enabled, Credentials credentials, LoginRateLimiter limiter) {
        this(enabled, credentials, limiter, new PasswordHasher()::verify);
    }

    LocalLogin(boolean enabled, Credentials credentials, LoginRateLimiter limiter, Verifier verifier) {
        this.enabled = enabled;
        this.credentials = credentials;
        this.limiter = limiter;
        this.verifier = verifier;
    }

    /** The hash unknown users are checked against. */
    static String dummyHash() {
        return DummyHash.VALUE;
    }

    /** Whether password sign-in is offered ({@code auth.local.enabled}). */
    public boolean enabled() {
        return enabled;
    }

    /**
     * Checks a sign-in attempt. The password array isn't cleared; the caller owns it.
     *
     * @param client the client's address (as {@code server.trustedProxies} decides)
     */
    public Result attempt(String username, char[] password, InetAddress client) {
        if (!enabled) return new Result.Failure();
        String ipKey = LoginRateLimiter.ipKey(client);
        String userKey = LoginRateLimiter.userKey(username);
        Optional<Duration> wait = max(limiter.blocked(ipKey), limiter.blocked(userKey));
        if (wait.isPresent()) {
            return new Result.RateLimited(wait.get());
        }
        Optional<String> hash = credentials.passwordHash(username);
        boolean ok;
        if (hash.isEmpty() || password.length > MAX_PASSWORD_LENGTH) {
            verifier.verify(password.length > MAX_PASSWORD_LENGTH ? new char[0] : password, DummyHash.VALUE);
            ok = false;
        } else {
            ok = verifier.verify(password, hash.get());
        }
        if (ok) {
            limiter.reset(userKey);
            return new Result.Success(username);
        }
        limiter.recordFailure(ipKey);
        limiter.recordFailure(userKey);
        LOG.log(System.Logger.Level.INFO, "Failed sign-in from " + client.getHostAddress());
        return new Result.Failure();
    }

    private static Optional<Duration> max(Optional<Duration> a, Optional<Duration> b) {
        if (a.isEmpty()) return b;
        if (b.isEmpty()) return a;
        return a.get().compareTo(b.get()) >= 0 ? a : b;
    }

    /** A hash of a random password nobody knows, computed once with the default parameters. */
    private static final class DummyHash {
        static final String VALUE;

        static {
            SecureRandom random = new SecureRandom();
            char[] password = new char[32];
            for (int i = 0; i < password.length; i++) password[i] = (char) ('a' + random.nextInt(26));
            VALUE = new PasswordHasher().hash(password);
            Arrays.fill(password, '\0');
        }
    }

    // ---- configuration ----

    /**
     * Reads {@code auth.local.enabled} (default {@code true}). Users and their password hashes come
     * from {@link UserDirectory}.
     *
     * @param auth   the raw {@code auth} section, or {@code null}
     * @param errors receives one message per problem, prefixed with its path
     */
    public static boolean enabled(JsonObject auth, List<String> errors) {
        if (auth == null) return true;
        JsonElement local = auth.get("local");
        if (local == null || local.isJsonNull()) return true;
        if (!local.isJsonObject()) {
            errors.add("auth.local must be an object");
            return true;
        }
        for (String key : local.getAsJsonObject().keySet()) {
            if (!key.equals("enabled")) errors.add("auth.local: unknown key \"" + key + "\"");
        }
        JsonElement value = local.getAsJsonObject().get("enabled");
        if (value == null || value.isJsonNull()) return true;
        if (value instanceof JsonPrimitive p && p.isBoolean()) return p.getAsBoolean();
        errors.add("auth.local.enabled must be true or false");
        return true;
    }

    /** {@link #enabled(JsonObject, List)} that throws on a problem. */
    public static boolean enabled(JsonObject auth) {
        List<String> errors = new ArrayList<>();
        boolean enabled = enabled(auth, errors);
        if (!errors.isEmpty()) throw new IllegalArgumentException(String.join("; ", errors));
        return enabled;
    }
}
