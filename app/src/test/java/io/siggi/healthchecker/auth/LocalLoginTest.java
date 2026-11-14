package io.siggi.healthchecker.auth;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalLoginTest {
    /** Real hash of "secret" (computed once; PBKDF2 at 600k iterations is slow). */
    private static final String ALICE_HASH = new PasswordHasher().hash("secret".toCharArray());

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-14T12:00:00Z"));
    private final List<String> verifiedAgainst = new ArrayList<>();
    private final PasswordHasher hasher = new PasswordHasher();

    private LocalLogin login(int maxFailures) {
        LoginRateLimiter limiter = new LoginRateLimiter(maxFailures, Duration.ofMinutes(15), clock);
        return new LocalLogin(true, username -> Optional.ofNullable(Map.of("alice", ALICE_HASH).get(username)),
                limiter, (password, hash) -> {
                    verifiedAgainst.add(hash);
                    return hasher.verify(password, hash);
                });
    }

    private static InetAddress ip(String text) throws Exception {
        return InetAddress.getByName(text);
    }

    @Test
    void success() throws Exception {
        LocalLogin.Result r = login(3).attempt("alice", "secret".toCharArray(), ip("10.0.0.1"));
        assertEquals(new LocalLogin.Result.Success("alice"), r);
        assertEquals(List.of(ALICE_HASH), verifiedAgainst);
    }

    @Test
    void wrongPassword() throws Exception {
        assertInstanceOf(LocalLogin.Result.Failure.class, login(3).attempt("alice", "nope".toCharArray(), ip("10.0.0.1")));
        assertEquals(List.of(ALICE_HASH), verifiedAgainst);
    }

    @Test
    void unknownUserIsCheckedAgainstTheDummyHash() throws Exception {
        LocalLogin l = login(3);
        assertInstanceOf(LocalLogin.Result.Failure.class, l.attempt("bob", "secret".toCharArray(), ip("10.0.0.1")));
        // Usernames are matched exactly, so "Alice" is unknown too.
        assertInstanceOf(LocalLogin.Result.Failure.class, l.attempt("Alice", "secret".toCharArray(), ip("10.0.0.2")));
        assertEquals(List.of(LocalLogin.dummyHash(), LocalLogin.dummyHash()), verifiedAgainst);
        // The dummy hash has the same cost parameters as real ones.
        assertEquals(PasswordHasher.DEFAULT_ITERATIONS, PasswordHasher.parse(LocalLogin.dummyHash()).iterations());
    }

    @Test
    void overLongPasswordFailsWithDummyVerification() throws Exception {
        char[] longPassword = new char[LocalLogin.MAX_PASSWORD_LENGTH + 1];
        java.util.Arrays.fill(longPassword, 'x');
        assertInstanceOf(LocalLogin.Result.Failure.class, login(3).attempt("alice", longPassword, ip("10.0.0.1")));
        assertEquals(List.of(LocalLogin.dummyHash()), verifiedAgainst);
    }

    @Test
    void rateLimitPerIp() throws Exception {
        LocalLogin l = login(3);
        for (String user : List.of("u1", "u2", "u3")) {
            assertInstanceOf(LocalLogin.Result.Failure.class, l.attempt(user, "x".toCharArray(), ip("10.0.0.1")));
        }
        verifiedAgainst.clear();
        // Even the right password is refused from that IP, without checking it.
        LocalLogin.Result r = l.attempt("alice", "secret".toCharArray(), ip("10.0.0.1"));
        assertLimited(r, Duration.ofMinutes(15));
        assertEquals(List.of(), verifiedAgainst);
        // Another IP is fine.
        assertEquals(new LocalLogin.Result.Success("alice"), l.attempt("alice", "secret".toCharArray(), ip("10.0.0.2")));
        // Once the failures leave the window, the IP may try again.
        clock.advance(Duration.ofMinutes(15).plusSeconds(1));
        assertEquals(new LocalLogin.Result.Success("alice"), l.attempt("alice", "secret".toCharArray(), ip("10.0.0.1")));
    }

    @Test
    void rateLimitPerUsername() throws Exception {
        LocalLogin l = login(3);
        for (String addr : List.of("10.0.0.1", "10.0.0.2", "10.0.0.3")) {
            assertInstanceOf(LocalLogin.Result.Failure.class, l.attempt("alice", "x".toCharArray(), ip(addr)));
            clock.advance(Duration.ofMinutes(1));
        }
        // Blocked from any IP, case-insensitively, until the oldest failure is 15 minutes old.
        assertLimited(l.attempt("alice", "secret".toCharArray(), ip("10.0.0.4")),
                Duration.ofMinutes(12));
        assertLimited(l.attempt("ALICE", "secret".toCharArray(), ip("10.0.0.4")),
                Duration.ofMinutes(12));
        // Other usernames from the same IP aren't affected.
        assertInstanceOf(LocalLogin.Result.Failure.class, l.attempt("bob", "x".toCharArray(), ip("10.0.0.4")));
        clock.advance(Duration.ofMinutes(12).plusSeconds(1));
        assertEquals(new LocalLogin.Result.Success("alice"), l.attempt("alice", "secret".toCharArray(), ip("10.0.0.4")));
    }

    @Test
    void successClearsTheUsernameFailures() throws Exception {
        LocalLogin l = login(3);
        l.attempt("alice", "x".toCharArray(), ip("10.0.0.1"));
        l.attempt("alice", "x".toCharArray(), ip("10.0.0.2"));
        assertEquals(new LocalLogin.Result.Success("alice"), l.attempt("alice", "secret".toCharArray(), ip("10.0.0.3")));
        l.attempt("alice", "x".toCharArray(), ip("10.0.0.4"));
        l.attempt("alice", "x".toCharArray(), ip("10.0.0.5"));
        assertEquals(new LocalLogin.Result.Success("alice"), l.attempt("alice", "secret".toCharArray(), ip("10.0.0.6")));
    }

    @Test
    void disabledLocalLoginAlwaysFails() throws Exception {
        LocalLogin l = new LocalLogin(false, username -> Optional.of(ALICE_HASH), new LoginRateLimiter());
        assertFalse(l.enabled());
        assertInstanceOf(LocalLogin.Result.Failure.class, l.attempt("alice", "secret".toCharArray(), ip("10.0.0.1")));
    }

    private static void assertLimited(LocalLogin.Result r, Duration retryAfter) {
        LocalLogin.Result.RateLimited limited = assertInstanceOf(LocalLogin.Result.RateLimited.class, r);
        assertEquals(retryAfter, limited.retryAfter());
    }

    // ---- settings ----

    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    @Test
    void settingsFromConfig() {
        LocalLogin.Settings s = LocalLogin.settings(json("""
                {"users": [
                  {"id": "alice", "role": "admin", "password": "%s"},
                  {"id": "jane", "role": "viewer", "identities": ["google:1"]},
                  {"id": "old", "role": "viewer", "password": "%s", "disabled": true}
                ]}""".formatted(ALICE_HASH, ALICE_HASH)));
        assertTrue(s.enabled());
        assertEquals(Map.of("alice", ALICE_HASH), s.passwordHashes());
        assertEquals(Optional.of(ALICE_HASH), s.credentials().passwordHash("alice"));
        assertEquals(Optional.empty(), s.credentials().passwordHash("jane"));
        assertEquals(Optional.empty(), s.credentials().passwordHash("old"));
        assertFalse(LocalLogin.settings(json("{\"local\": {\"enabled\": false}}")).enabled());
        assertTrue(LocalLogin.settings(null).enabled());
    }

    @Test
    void settingsErrors() {
        List<String> errors = new ArrayList<>();
        LocalLogin.settings(json("""
                {"local": {"enabled": "no"}, "users": [
                  {"id": "alice", "password": "pbkdf2-sha256$1000$AAAA$BBBB"},
                  {"id": "bob", "password": 5},
                  {"role": "viewer"}
                ]}"""), errors);
        assertEquals(4, errors.size(), errors.toString());
        assertTrue(errors.get(0).startsWith("auth.local.enabled"), errors.get(0));
        assertTrue(errors.get(1).startsWith("auth.users[0] \"alice\": password hash"), errors.get(1));
        assertFalse(errors.get(1).contains("AAAA"), errors.get(1));
        assertTrue(errors.get(2).startsWith("auth.users[1] \"bob\": password"), errors.get(2));
        assertTrue(errors.get(3).startsWith("auth.users[2]: id"), errors.get(3));
        assertThrows(IllegalArgumentException.class, () -> LocalLogin.settings(json("{\"users\": 1}")));
    }

    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
