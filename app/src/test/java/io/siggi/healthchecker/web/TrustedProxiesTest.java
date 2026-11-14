package io.siggi.healthchecker.web;

import io.siggi.healthchecker.config.IpRange;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrustedProxiesTest {
    private static InetAddress ip(String s) {
        InetAddress a = IpRange.parseAddress(s);
        if (a == null) throw new IllegalArgumentException(s);
        return a;
    }

    private static String client(TrustedProxies p, String nextHop, String... xff) {
        return p.clientAddress(ip(nextHop), xff.length == 0 ? null : List.of(xff)).getHostAddress();
    }

    @Test
    void ipRangeContains() {
        IpRange v4 = IpRange.parse("10.0.0.0/8");
        assertTrue(v4.contains(ip("10.255.1.2")));
        assertFalse(v4.contains(ip("11.0.0.1")));
        assertTrue(IpRange.parse("192.168.1.77/24").contains(ip("192.168.1.1")));
        assertTrue(IpRange.parse("0.0.0.0/0").contains(ip("203.0.113.9")));
        assertFalse(IpRange.parse("0.0.0.0/0").contains(ip("::1")));
        assertTrue(IpRange.parse("127.0.0.1").contains(ip("127.0.0.1")));
        assertFalse(IpRange.parse("127.0.0.1").contains(ip("127.0.0.2")));
        IpRange v6 = IpRange.parse("fd00::/8");
        assertTrue(v6.contains(ip("fd12:3456::1")));
        assertFalse(v6.contains(ip("fe80::1")));
        assertTrue(IpRange.parse("[::1]").contains(ip("::1")));
        // IPv4-mapped IPv6 counts as IPv4.
        assertTrue(v4.contains(ip("::ffff:10.0.0.1")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"localhost", "example.com", "1.2.3", "256.0.0.1", "10.0.0.0/33", "::/129",
            "10.0.0.0/", "10.0.0.0/x", "10.0.0.0/-1", "", "1.2.3.4/8/8", "gg::1"})
    void ipRangeRejectsInvalid(String value) {
        assertThrows(IllegalArgumentException.class, () -> IpRange.parse(value));
    }

    @Test
    void parseAddressNeverResolvesNames() {
        assertNull(IpRange.parseAddress("localhost"));
        assertNull(IpRange.parseAddress("unknown"));
        assertNull(IpRange.parseAddress(""));
    }

    @Test
    void untrustedPeerHeadersIgnored() {
        TrustedProxies p = TrustedProxies.parse(List.of("10.0.0.1"));
        assertEquals("203.0.113.1", client(p, "203.0.113.1", "198.51.100.2"));
        assertEquals("127.0.0.1", client(p, "127.0.0.1", "198.51.100.2"));
    }

    @Test
    void noTrustedProxiesMeansNoForwarding() {
        TrustedProxies p = TrustedProxies.parse(List.of());
        assertEquals("127.0.0.1", client(p, "127.0.0.1", "198.51.100.2"));
    }

    @Test
    void trustedPeer() {
        TrustedProxies p = TrustedProxies.parse(List.of("10.0.0.0/8", "::1"));
        assertEquals("10.0.0.1", client(p, "10.0.0.1"));
        assertEquals("198.51.100.2", client(p, "10.0.0.1", "198.51.100.2"));
        assertEquals("198.51.100.2", client(p, "::1", "198.51.100.2"));
        assertEquals("2001:db8:0:0:0:0:0:1", client(p, "10.0.0.1", "[2001:db8::1]"));
    }

    @Test
    void chainWalksRightToLeftUntilUntrusted() {
        TrustedProxies p = TrustedProxies.parse(List.of("10.0.0.0/8"));
        // Spoofed leftmost entries are never reached.
        assertEquals("198.51.100.7", client(p, "10.0.0.1", "192.0.2.66, 198.51.100.7, 10.2.3.4"));
        // Several header lines are one list.
        assertEquals("198.51.100.7", client(p, "10.0.0.1", "192.0.2.66", "198.51.100.7, 10.2.3.4"));
        // All hops trusted: the leftmost is the client.
        assertEquals("10.9.9.9", client(p, "10.0.0.1", "10.9.9.9, 10.2.3.4"));
    }

    @Test
    void invalidEntryStopsTheWalk() {
        TrustedProxies p = TrustedProxies.parse(List.of("10.0.0.0/8"));
        assertEquals("10.0.0.1", client(p, "10.0.0.1", "unknown"));
        assertEquals("10.2.3.4", client(p, "10.0.0.1", "198.51.100.7, garbage, 10.2.3.4"));
        assertEquals("10.0.0.1", client(p, "10.0.0.1", "198.51.100.7, "));
    }
}
