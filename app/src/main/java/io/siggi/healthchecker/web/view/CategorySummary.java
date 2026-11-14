package io.siggi.healthchecker.web.view;

/**
 * State counts over the services a viewer can see in one category, for its heading.
 *
 * @param total   visible services
 * @param down    of those, DOWN
 * @param unknown of those, not checked yet
 */
public record CategorySummary(int total, int down, int unknown) {
    /** "All operational", "1 of 4 down", or "2 of 4 not checked yet". Down takes precedence. */
    public String text() {
        if (down > 0) {
            return down + " of " + total + " down";
        }
        if (unknown > 0) {
            return unknown + " of " + total + " not checked yet";
        }
        return "All operational";
    }
}
