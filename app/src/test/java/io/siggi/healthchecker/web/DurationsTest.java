package io.siggi.healthchecker.web;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DurationsTest {
    private static String format(long seconds) {
        return Durations.format(Duration.ofSeconds(seconds));
    }

    @Test
    void secondsOnlyBelowAMinute() {
        assertEquals("0s", format(0));
        assertEquals("45s", format(45));
        assertEquals("59s", Durations.format(Duration.ofMillis(59_999)));
    }

    @Test
    void atMostTwoUnitsTruncated() {
        assertEquals("1m", format(60));
        assertEquals("12m", format(12 * 60 + 59));
        assertEquals("1h", format(3600));
        assertEquals("1h 12m", format(3600 + 12 * 60 + 59));
        assertEquals("23h 59m", format(24 * 3600 - 1));
        assertEquals("1d", format(24 * 3600 + 59 * 60));
        assertEquals("2d 3h", format(2 * 86400 + 3 * 3600 + 59 * 60 + 59));
        assertEquals("400d 1h", format(400 * 86400 + 3600));
    }

    @Test
    void negativeIsZero() {
        assertEquals("0s", format(-5));
    }
}
