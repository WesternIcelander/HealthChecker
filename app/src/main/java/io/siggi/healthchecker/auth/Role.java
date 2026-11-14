package io.siggi.healthchecker.auth;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * A named set of permissions. The built-in roles {@code viewer}, {@code editor} and {@code admin}
 * can't be redefined; custom roles come from {@code auth.roles}.
 */
public record Role(String name, Set<Permission> permissions) {
    public static final Role VIEWER = new Role("viewer", EnumSet.of(Permission.VIEW_PRIVATE));
    public static final Role EDITOR = new Role("editor", EnumSet.of(Permission.VIEW_PRIVATE,
            Permission.EVENTS_ANNOTATE, Permission.MAINTENANCE_MANAGE));
    public static final Role ADMIN = new Role("admin", EnumSet.allOf(Permission.class));

    /** The built-in roles by name, in order. */
    public static final Map<String, Role> BUILT_IN;

    static {
        Map<String, Role> builtIn = new LinkedHashMap<>();
        for (Role role : new Role[]{VIEWER, EDITOR, ADMIN}) builtIn.put(role.name(), role);
        BUILT_IN = java.util.Collections.unmodifiableMap(builtIn);
    }

    public Role {
        EnumSet<Permission> copy = EnumSet.noneOf(Permission.class);
        copy.addAll(permissions);
        permissions = java.util.Collections.unmodifiableSet(copy);
    }

    public boolean has(Permission permission) {
        return permissions.contains(permission);
    }
}
