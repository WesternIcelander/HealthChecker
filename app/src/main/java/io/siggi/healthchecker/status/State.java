package io.siggi.healthchecker.status;

/** Live state of one endpoint. */
public enum State {
    /** No check has completed yet. */
    UNKNOWN,
    UP,
    DOWN
}
