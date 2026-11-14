package io.siggi.healthchecker.web;

import io.siggi.healthchecker.config.IpRange;
import io.siggi.http.HTTPRequest;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * The configured {@code server.trustedProxies}, and the client address they report.
 * <p>
 * {@link WebServer} gives SiggiHTTP exactly these addresses, so {@link HTTPRequest#isSecure()} only
 * believes {@code X-Forwarded-Proto} from them. The client address is still worked out here from the
 * socket's peer ({@link HTTPRequest#getNextHopAddress()}) and {@code X-Forwarded-For}, because
 * {@link HTTPRequest#getIPAddress()} also believes {@code CF-Connecting-IP} from any trusted peer,
 * which a proxy that doesn't strip it would pass through from the client.
 */
public final class TrustedProxies {
    private final List<IpRange> ranges;

    public TrustedProxies(List<IpRange> ranges) {
        this.ranges = List.copyOf(ranges);
    }

    /** @throws IllegalArgumentException if an entry isn't an IP address or CIDR range */
    public static TrustedProxies parse(List<String> entries) {
        List<IpRange> ranges = new ArrayList<>();
        for (String entry : entries) {
            ranges.add(IpRange.parse(entry));
        }
        return new TrustedProxies(ranges);
    }

    public List<IpRange> ranges() {
        return ranges;
    }

    public boolean isTrusted(InetAddress address) {
        for (IpRange range : ranges) {
            if (range.contains(address)) return true;
        }
        return false;
    }

    /** The client's address: the connection's peer, or what trusted proxies report in {@code X-Forwarded-For}. */
    public InetAddress clientAddress(HTTPRequest request) {
        return clientAddress(request.getNextHopAddress().getAddress(), request.getHeaders("X-Forwarded-For"));
    }

    /**
     * Walks {@code X-Forwarded-For} from the right (nearest hop first) while the current hop is a
     * trusted proxy. Stops at the first untrusted hop, or at an entry that isn't an IP literal
     * (then the last trusted hop is the client). An untrusted peer's headers are ignored entirely.
     *
     * @param nextHop       the connection's peer address
     * @param forwardedFor  the {@code X-Forwarded-For} header lines in order, or {@code null}
     */
    public InetAddress clientAddress(InetAddress nextHop, List<String> forwardedFor) {
        InetAddress client = nextHop;
        if (forwardedFor == null || forwardedFor.isEmpty()) return client;
        List<String> hops = splitList(forwardedFor);
        for (int i = hops.size() - 1; i >= 0 && isTrusted(client); i--) {
            InetAddress hop = IpRange.parseAddress(hops.get(i));
            if (hop == null) break;
            client = hop;
        }
        return client;
    }

    /** Whether the connection's peer is a trusted proxy. */
    public boolean isFromTrustedProxy(HTTPRequest request) {
        return isTrusted(request.getNextHopAddress().getAddress());
    }

    private static List<String> splitList(List<String> headerLines) {
        List<String> out = new ArrayList<>();
        for (String line : headerLines) {
            for (String part : line.split(",", -1)) {
                out.add(part.trim());
            }
        }
        return out;
    }
}
