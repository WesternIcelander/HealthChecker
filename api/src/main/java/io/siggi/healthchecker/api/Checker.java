package io.siggi.healthchecker.api;

import java.time.Duration;

/** One per endpoint. Called from one thread at a time, never concurrently. */
public interface Checker extends AutoCloseable {
    /**
     * Runs one check. Should honour the timeout; the app enforces it too.
     * Throwing counts as a failure.
     */
    CheckResult check(Duration timeout) throws Exception;

    /** Releases resources. Called when the endpoint is removed or replaced, and on shutdown. */
    @Override
    default void close() throws Exception {
    }
}
