package io.siggi.healthchecker.auth;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class LoginRateLimiterTest {
    private final LocalLoginTest.MutableClock clock = new LocalLoginTest.MutableClock(Instant.parse("2026-11-14T12:00:00Z"));
    private final LoginRateLimiter limiter = new LoginRateLimiter(2, Duration.ofMinutes(10), clock);

    @Test
    void slidingWindow() {
        assertEquals(Optional.empty(), limiter.blocked("k"));
        limiter.recordFailure("k");
        assertEquals(Optional.empty(), limiter.blocked("k"));
        clock.advance(Duration.ofMinutes(4));
        limiter.recordFailure("k");
        assertEquals(Optional.of(Duration.ofMinutes(6)), limiter.blocked("k"));
        assertEquals(Optional.empty(), limiter.blocked("other"));
        clock.advance(Duration.ofMinutes(6));
        // The first failure has left the window; the second still counts.
        assertEquals(Optional.empty(), limiter.blocked("k"));
        limiter.recordFailure("k");
        assertEquals(Optional.of(Duration.ofMinutes(4)), limiter.blocked("k"));
        limiter.reset("k");
        assertEquals(Optional.empty(), limiter.blocked("k"));
    }

    @Test
    void expiredKeysAreForgotten() {
        for (int i = 0; i < 2000; i++) limiter.recordFailure("k" + i);
        clock.advance(Duration.ofMinutes(11));
        for (int i = 0; i < 1024; i++) limiter.recordFailure("fresh");
        assertEquals(1, limiter.size());
    }

    @Test
    void keys() throws Exception {
        assertEquals("ip 192.0.2.7", LoginRateLimiter.ipKey(InetAddress.getByName("192.0.2.7")));
        // IPv6 clients are grouped by /64.
        assertEquals(LoginRateLimiter.ipKey(InetAddress.getByName("2001:db8:1:2::1")),
                LoginRateLimiter.ipKey(InetAddress.getByName("2001:db8:1:2:ffff::9")));
        assertNotEquals(LoginRateLimiter.ipKey(InetAddress.getByName("2001:db8:1:2::1")),
                LoginRateLimiter.ipKey(InetAddress.getByName("2001:db8:1:3::1")));
        assertEquals(LoginRateLimiter.userKey("Alice"), LoginRateLimiter.userKey("alice"));
        assertNotEquals(LoginRateLimiter.userKey("alice"), LoginRateLimiter.ipKey(InetAddress.getByName("192.0.2.7")));
        assertEquals(LoginRateLimiter.userKey("x".repeat(300)), LoginRateLimiter.userKey("x".repeat(400)));
    }
}
