package io.siggi.healthchecker.web.view;

/**
 * Who is looking at a page or API response, as far as {@link ViewFilter} and the page header are concerned.
 *
 * @param userName    the logged-in user's display name, or {@code null} when anonymous
 * @param role        the logged-in user's role name for the header, or {@code null}
 * @param viewPrivate whether the viewer has the {@code view.private} permission; without
 *                    it a logged-in user gets the same view as an anonymous visitor
 * @param csrfToken   the session's CSRF token for the header's sign-out form, or {@code null}
 */
public record Viewer(String userName, String role, boolean viewPrivate, String csrfToken) {
    public static final Viewer ANONYMOUS = new Viewer(null, null, false, null);

    /** A viewer without header details (role, CSRF token). */
    public Viewer(String userName, boolean viewPrivate) {
        this(userName, null, viewPrivate, null);
    }

    public boolean anonymous() {
        return userName == null;
    }

    @Override
    public String toString() {
        // Never print the CSRF token.
        return "Viewer[userName=" + userName + ", role=" + role + ", viewPrivate=" + viewPrivate + "]";
    }
}
