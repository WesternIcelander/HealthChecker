package io.siggi.healthchecker.web;

import io.siggi.healthchecker.config.IpRange;
import io.siggi.http.HTTPRequest;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * The configured {@code server.trustedProxies}, and the client address they report.
 * <p>
 * {@link WebServer} gives SiggiHTTP exactly these addresses, so {@link HTTPRequest#isSecure()} and
 * {@link HTTPRequest#getIPAddress()} only believe {@code X-Forwarded-Proto} and {@code X-Forwarded-For}
 * from them.
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

    /**
     * The client's address: SiggiHTTP's {@link HTTPRequest#getIPAddress()}, which is the connection's
     * peer, or what trusted proxies report in {@code X-Forwarded-For} (walked from the right while the
     * hop is trusted). {@link WebServer} limits SiggiHTTP's trusted IPs to these ranges.
     */
    public InetAddress clientAddress(HTTPRequest request) {
        InetAddress address = IpRange.parseAddress(request.getIPAddress());
        return address != null ? address : request.getNextHopAddress().getAddress();
    }

    /** Whether the connection's peer is a trusted proxy. */
    public boolean isFromTrustedProxy(HTTPRequest request) {
        return isTrusted(request.getNextHopAddress().getAddress());
    }
}
