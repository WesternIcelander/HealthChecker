package io.siggi.healthchecker.config;

/** What to do with a request whose host matches no configured site. */
public enum UnknownHostsPolicy {
    /** Serve the default site (everything, top-level dashboard settings). */
    DEFAULT,
    /** Respond with 404. */
    REJECT
}
