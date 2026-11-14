package io.siggi.healthchecker.auth;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Counts failed sign-ins per key in a sliding window: once a key has {@code maxFailures} failures
 * within {@code window}, it's blocked until the oldest of them leaves the window. Keys are built
 * with {@link #ipKey} and {@link #userKey}, so per-IP and per-username limits share one instance.
 * Only failures are counted; a blocked attempt isn't checked at all and doesn't count again.
 */
public final class LoginRateLimiter {
    public static final int DEFAULT_MAX_FAILURES = 10;
    public static final Duration DEFAULT_WINDOW = Duration.ofMinutes(15);
    /** Usernames longer than this share the key of their prefix. */
    static final int MAX_USER_KEY_LENGTH = 256;
    /** Expired keys are swept after this many recorded failures. */
    private static final int SWEEP_EVERY = 1024;

    private final int maxFailures;
    private final Duration window;
    private final Clock clock;
    private final Map<String, ArrayDeque<Instant>> failures = new HashMap<>();
    private int sinceSweep;

    public LoginRateLimiter() {
        this(DEFAULT_MAX_FAILURES, DEFAULT_WINDOW, Clock.systemUTC());
    }

    public LoginRateLimiter(int maxFailures, Duration window, Clock clock) {
        if (maxFailures < 1) throw new IllegalArgumentException("maxFailures must be at least 1");
        if (window.isNegative() || window.isZero()) throw new IllegalArgumentException("window must be positive");
        this.maxFailures = maxFailures;
        this.window = window;
        this.clock = clock;
    }

    /**
     * The key for a client address. IPv6 clients are grouped by their /64 prefix, since one
     * client usually controls a whole /64.
     */
    public static String ipKey(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (address instanceof Inet6Address) {
            return "ip " + HexFormat.of().formatHex(bytes, 0, 8) + "::/64";
        }
        return "ip " + address.getHostAddress();
    }

    /** The key for a username: case-insensitive, at most {@value #MAX_USER_KEY_LENGTH} characters. */
    public static String userKey(String username) {
        String normalized = username.toLowerCase(Locale.ROOT);
        if (normalized.length() > MAX_USER_KEY_LENGTH) normalized = normalized.substring(0, MAX_USER_KEY_LENGTH);
        return "user " + normalized;
    }

    /** How long until {@code key} may try again, or empty when it isn't blocked. */
    public synchronized Optional<Duration> blocked(String key) {
        ArrayDeque<Instant> times = failures.get(key);
        if (times == null) return Optional.empty();
        Instant now = clock.instant();
        prune(times, now);
        if (times.isEmpty()) {
            failures.remove(key);
            return Optional.empty();
        }
        if (times.size() < maxFailures) return Optional.empty();
        return Optional.of(Duration.between(now, times.peekFirst().plus(window)));
    }

    public synchronized void recordFailure(String key) {
        Instant now = clock.instant();
        ArrayDeque<Instant> times = failures.computeIfAbsent(key, k -> new ArrayDeque<>());
        prune(times, now);
        times.addLast(now);
        // Only the newest maxFailures matter for the decision.
        while (times.size() > maxFailures) times.removeFirst();
        if (++sinceSweep >= SWEEP_EVERY) {
            sinceSweep = 0;
            sweep(now);
        }
    }

    /** Forgets the failures of {@code key} (after a successful sign-in). */
    public synchronized void reset(String key) {
        failures.remove(key);
    }

    /** Number of keys currently tracked (for tests). */
    synchronized int size() {
        return failures.size();
    }

    private void sweep(Instant now) {
        for (Iterator<ArrayDeque<Instant>> it = failures.values().iterator(); it.hasNext(); ) {
            ArrayDeque<Instant> times = it.next();
            prune(times, now);
            if (times.isEmpty()) it.remove();
        }
    }

    private void prune(ArrayDeque<Instant> times, Instant now) {
        Instant cutoff = now.minus(window);
        while (!times.isEmpty() && !times.peekFirst().isAfter(cutoff)) times.removeFirst();
    }
}
