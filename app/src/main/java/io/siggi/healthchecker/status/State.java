package io.siggi.healthchecker.status;

/** Live state of one endpoint. */
public enum State {
    /** No check has completed yet. */
    UNKNOWN,
    UP,
    DOWN,
    /**
     * In a scheduled maintenance window. Shown instead of the state the checks give (which
     * {@link EndpointStatus#checkState()} still has).
     */
    MAINTENANCE
}
