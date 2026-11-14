package io.siggi.healthchecker.web.view;

/**
 * State counts over the services a viewer can see in one category, for its heading.
 *
 * @param total       visible services
 * @param down        of those, DOWN
 * @param unknown     of those, not checked yet
 * @param maintenance of those, in a maintenance window
 */
public record CategorySummary(int total, int down, int unknown, int maintenance) {
    public CategorySummary(int total, int down, int unknown) {
        this(total, down, unknown, 0);
    }

    /**
     * "All operational", "1 of 4 down", "1 of 4 in maintenance" or "2 of 4 not checked yet". Down
     * takes precedence, then maintenance.
     */
    public String text() {
        if (down > 0) {
            return down + " of " + total + " down";
        }
        if (maintenance > 0) {
            return maintenance + " of " + total + " in maintenance";
        }
        if (unknown > 0) {
            return unknown + " of " + total + " not checked yet";
        }
        return "All operational";
    }
}
