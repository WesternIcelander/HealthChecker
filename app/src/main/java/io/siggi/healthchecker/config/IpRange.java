package io.siggi.healthchecker.config;

import io.siggi.http.util.Util;

import java.net.InetAddress;
import java.util.Arrays;

/**
 * An IP address or CIDR range, such as {@code 127.0.0.1}, {@code 10.0.0.0/8} or {@code fd00::/8}.
 * Parsing never does a DNS lookup: only IP literals are accepted. IPv4-mapped IPv6 addresses
 * ({@code ::ffff:a.b.c.d}) count as IPv4.
 */
public final class IpRange {
    private final byte[] network;
    private final int prefixLength;
    private final String text;

    private IpRange(byte[] network, int prefixLength, String text) {
        this.network = network;
        this.prefixLength = prefixLength;
        this.text = text;
    }

    /**
     * Parses an address ({@code 192.0.2.1}, {@code 2001:db8::1}) or a CIDR range
     * ({@code 192.0.2.0/24}, {@code 2001:db8::/32}). Host bits below the prefix are ignored.
     *
     * @throws IllegalArgumentException if it isn't one
     */
    public static IpRange parse(String value) {
        String s = value.trim();
        int slash = s.indexOf('/');
        String addressPart = slash < 0 ? s : s.substring(0, slash);
        InetAddress address = parseAddress(addressPart);
        if (address == null) {
            throw new IllegalArgumentException("not an IP address or CIDR range: \"" + value + "\"");
        }
        byte[] bytes = address.getAddress();
        int bits = bytes.length * 8;
        int prefix = bits;
        if (slash >= 0) {
            String prefixPart = s.substring(slash + 1);
            if (prefixPart.isEmpty() || prefixPart.length() > 3 || !prefixPart.chars().allMatch(c -> c >= '0' && c <= '9')) {
                throw new IllegalArgumentException("invalid prefix length in \"" + value + "\"");
            }
            prefix = Integer.parseInt(prefixPart);
            if (prefix > bits) {
                throw new IllegalArgumentException("prefix length in \"" + value + "\" must be at most " + bits);
            }
        }
        return new IpRange(mask(bytes, prefix), prefix, s);
    }

    /**
     * Parses an IP literal (IPv6 optionally in brackets), or returns {@code null}. Never does a DNS lookup.
     */
    public static InetAddress parseAddress(String value) {
        if (value == null) return null;
        String s = value.trim();
        if (s.isEmpty()) return null;
        return Util.parseIPLiteral(s);
    }

    /** Whether this range contains {@code address}. IPv4 and IPv6 ranges never contain each other's addresses. */
    public boolean contains(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (bytes.length != network.length) return false;
        return Arrays.equals(mask(bytes, prefixLength), network);
    }

    private static byte[] mask(byte[] bytes, int prefix) {
        byte[] out = bytes.clone();
        for (int i = 0; i < out.length; i++) {
            int keep = Math.min(8, Math.max(0, prefix - i * 8));
            out[i] &= (byte) (0xff << (8 - keep));
        }
        return out;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof IpRange r && r.prefixLength == prefixLength && Arrays.equals(r.network, network);
    }

    @Override
    public int hashCode() {
        return 31 * Arrays.hashCode(network) + prefixLength;
    }

    @Override
    public String toString() {
        return text;
    }
}
