package io.siggi.healthchecker.notify;

import java.time.Duration;

/**
 * A failed delivery. The message is shown to logged-in users as the channel's last error,
 * so it must not contain secrets (API keys, webhook URLs).
 */
public class NotifierException extends Exception {
    private final boolean permanent;
    private final Duration retryAfter;

    /**
     * @param permanent  {@code true} if retrying can't help (e.g. 400, 401)
     * @param retryAfter the earliest retry the receiver asked for (e.g. a 429 {@code retry_after}),
     *                   or {@code null}
     */
    public NotifierException(String message, boolean permanent, Duration retryAfter, Throwable cause) {
        super(message, cause);
        this.permanent = permanent;
        this.retryAfter = retryAfter;
    }

    public static NotifierException temporary(String message) {
        return new NotifierException(message, false, null, null);
    }

    public static NotifierException permanent(String message) {
        return new NotifierException(message, true, null, null);
    }

    public static NotifierException retryAfter(String message, Duration retryAfter) {
        return new NotifierException(message, false, retryAfter, null);
    }

    public boolean permanent() {
        return permanent;
    }

    /** The earliest retry the receiver asked for, or {@code null}. */
    public Duration retryAfter() {
        return retryAfter;
    }
}
