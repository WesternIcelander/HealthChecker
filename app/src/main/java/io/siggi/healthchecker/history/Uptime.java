package io.siggi.healthchecker.history;

import java.math.BigInteger;
import java.time.Duration;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.OptionalLong;

/**
 * Uptime over some window: how long the endpoint was monitored in it ({@code covered}) and
 * how much of that was downtime.
 *
 * <p>{@code uptime = 1 − downtime / covered}. When nothing was covered there is no data,
 * which is neither up nor down (shown as {@code —}, never as 100 %). The percentage is
 * truncated (not rounded) to two decimals, so any downtime at all shows below 100 %
 * (99.995 % shows as 99.99 %).
 *
 * @param covered  monitored time in the window
 * @param downtime downtime in the window that counts against uptime; never more than {@code covered}
 */
public record Uptime(Duration covered, Duration downtime) {
    /** What's shown when there is no data. */
    public static final String NO_DATA = "—";

    public static final Uptime NONE = new Uptime(Duration.ZERO, Duration.ZERO);

    private static final BigInteger TEN_THOUSAND = BigInteger.valueOf(10_000);

    public Uptime {
        Objects.requireNonNull(covered, "covered");
        Objects.requireNonNull(downtime, "downtime");
        if (covered.isNegative() || downtime.isNegative()) {
            throw new IllegalArgumentException("negative duration");
        }
        if (downtime.compareTo(covered) > 0) {
            throw new IllegalArgumentException("downtime " + downtime + " exceeds coverage " + covered);
        }
    }

    /** Whether any time in the window was monitored. */
    public boolean hasData() {
        return !covered.isZero();
    }

    /**
     * Uptime in hundredths of a percent (0–10000), truncated, so 10000 only when there
     * was no downtime at all. Empty when there is no data.
     */
    public OptionalLong hundredths() {
        if (!hasData()) {
            return OptionalLong.empty();
        }
        if (downtime.isZero()) {
            return OptionalLong.of(10_000);
        }
        BigInteger coveredNanos = nanos(covered);
        BigInteger upNanos = coveredNanos.subtract(nanos(downtime));
        long value = upNanos.multiply(TEN_THOUSAND).divide(coveredNanos).longValue();
        return OptionalLong.of(Math.min(value, 9_999));
    }

    /** Uptime percentage with at most two decimals (truncated), e.g. {@code 99.99}; empty without data. */
    public OptionalDouble percent() {
        OptionalLong h = hundredths();
        return h.isPresent() ? OptionalDouble.of(h.getAsLong() / 100.0) : OptionalDouble.empty();
    }

    /** E.g. {@code "100%"}, {@code "99.5%"}, {@code "99.99%"}, or {@link #NO_DATA}. */
    public String format() {
        OptionalLong h = hundredths();
        if (h.isEmpty()) {
            return NO_DATA;
        }
        long v = h.getAsLong();
        long whole = v / 100;
        long fraction = v % 100;
        if (fraction == 0) {
            return whole + "%";
        }
        if (fraction % 10 == 0) {
            return whole + "." + (fraction / 10) + "%";
        }
        return whole + "." + (fraction < 10 ? "0" : "") + fraction + "%";
    }

    private static BigInteger nanos(Duration d) {
        return BigInteger.valueOf(d.getSeconds()).multiply(BigInteger.valueOf(1_000_000_000L))
                .add(BigInteger.valueOf(d.getNano()));
    }
}
