package io.siggi.healthchecker.auth;

import java.util.List;

/**
 * A configured user ({@code auth.users[]}).
 *
 * @param id           the username for password sign-in, and what sessions store
 * @param displayName  shown in the header; defaults to {@code id}
 * @param passwordHash a {@link PasswordHasher} hash, or {@code null} when password sign-in isn't allowed
 * @param identities   OIDC/OAuth identifiers ({@code providerId:value})
 * @param disabled     a disabled user can't sign in and their sessions end on their next request
 */
public record User(String id, String displayName, Role role, String passwordHash, List<String> identities,
                   boolean disabled) {
    public User {
        identities = List.copyOf(identities);
    }

    /** Whether this user may do what {@code permission} allows (never when disabled). */
    public boolean has(Permission permission) {
        return !disabled && role.has(permission);
    }
}
