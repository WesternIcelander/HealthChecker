package io.siggi.healthchecker.web.view;

/**
 * Who is looking at a page or API response, as far as {@link ViewFilter} is concerned.
 *
 * @param userName    the logged-in user's display name, or {@code null} when anonymous
 * @param viewPrivate whether the viewer has the {@code view.private} permission; without
 *                    it a logged-in user gets the same view as an anonymous visitor
 */
public record Viewer(String userName, boolean viewPrivate) {
    public static final Viewer ANONYMOUS = new Viewer(null, false);

    public boolean anonymous() {
        return userName == null;
    }
}
