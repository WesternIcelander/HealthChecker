package io.siggi.healthchecker.auth;

import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Collectors;

/** What a role can allow. Ids are the names used in {@code auth.roles}. */
public enum Permission {
    /** See private endpoints, targets, private notes, failure messages, details and notification settings. */
    VIEW_PRIVATE("view.private"),
    /** Rename events; add, edit and delete notes. */
    EVENTS_ANNOTATE("events.annotate"),
    /** Create, edit, cancel, end and extend maintenance windows. */
    MAINTENANCE_MANAGE("maintenance.manage"),
    /** Reorder categories and services, move services between categories. */
    LAYOUT_EDIT("layout.edit"),
    /** Create, edit and delete endpoints and categories, "Test now", "Copy to dashboard". */
    ENDPOINTS_MANAGE("endpoints.manage"),
    /** "Also delete history" when deleting an endpoint. */
    HISTORY_DELETE("history.delete"),
    /** "Send test notification". */
    NOTIFICATIONS_TEST("notifications.test"),
    /** The audit log. */
    AUDIT_VIEW("audit.view");

    private final String id;

    Permission(String id) {
        this.id = id;
    }

    /** The name used in the config, e.g. {@code view.private}. */
    public String id() {
        return id;
    }

    /** The permission with this config name (exact, case-sensitive). */
    public static Optional<Permission> byId(String id) {
        for (Permission p : values()) {
            if (p.id.equals(id)) return Optional.of(p);
        }
        return Optional.empty();
    }

    /** All config names, comma-separated, for error messages. */
    static String knownIds() {
        return Arrays.stream(values()).map(Permission::id).collect(Collectors.joining(", "));
    }

    @Override
    public String toString() {
        return id;
    }
}
