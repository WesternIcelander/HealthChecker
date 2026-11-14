package io.siggi.healthchecker.auth;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserDirectoryTest {
    private static final String HASH = new PasswordHasher().hash("pw".toCharArray());
    private static final String PROVIDERS = """
            "providers": [{"id": "google"}, {"id": "github"}]""";

    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    private static List<String> errors(String auth) {
        List<String> errors = new ArrayList<>();
        UserDirectory.parse(json(auth), errors);
        return errors;
    }

    private static void assertHasError(List<String> errors, String expected) {
        assertTrue(errors.stream().anyMatch(e -> e.startsWith(expected)), "expected " + expected + " in " + errors);
    }

    // ---- role expansion ----

    @Test
    void builtInRolesExpandToTheirPermissions() {
        assertEquals(Set.of(Permission.VIEW_PRIVATE), Role.VIEWER.permissions());
        assertEquals(Set.of(Permission.VIEW_PRIVATE, Permission.EVENTS_ANNOTATE, Permission.MAINTENANCE_MANAGE),
                Role.EDITOR.permissions());
        assertTrue(Role.EDITOR.permissions().containsAll(Role.VIEWER.permissions()));
        assertEquals(EnumSet.allOf(Permission.class), Role.ADMIN.permissions());
        assertEquals(List.of("viewer", "editor", "admin"), List.copyOf(Role.BUILT_IN.keySet()));
    }

    @Test
    void customRolesAndUsers() {
        UserDirectory d = UserDirectory.parse(json("""
                {"roles": {"ops": ["view.private", "maintenance.manage", "maintenance.manage"],
                           "auditor": ["audit.view"], "nobody": []},
                 "users": [
                   {"id": "siggi", "displayName": "Siggi", "role": "admin", "password": "%s",
                    "identities": ["google:1", "github:2"]},
                   {"id": "jane", "role": "ops", "identities": ["google:3"]},
                   {"id": "audrey", "role": "auditor"},
                   {"id": "old-intern", "role": "viewer", "password": "%s", "disabled": true}
                 ], %s}""".formatted(HASH, HASH, PROVIDERS)));
        assertEquals(List.of("viewer", "editor", "admin", "ops", "auditor", "nobody"), List.copyOf(d.roles().keySet()));
        assertEquals(Set.of(Permission.VIEW_PRIVATE, Permission.MAINTENANCE_MANAGE), d.roles().get("ops").permissions());
        assertEquals(Set.of(), d.roles().get("nobody").permissions());

        User siggi = d.user("siggi").orElseThrow();
        assertEquals("Siggi", siggi.displayName());
        assertEquals(Role.ADMIN, siggi.role());
        for (Permission p : Permission.values()) assertTrue(siggi.has(p), p.id());

        User jane = d.user("jane").orElseThrow();
        assertEquals("jane", jane.displayName(), "display name defaults to the id");
        assertTrue(jane.has(Permission.VIEW_PRIVATE));
        assertTrue(jane.has(Permission.MAINTENANCE_MANAGE));
        assertFalse(jane.has(Permission.EVENTS_ANNOTATE));
        assertFalse(jane.has(Permission.LAYOUT_EDIT));

        User audrey = d.user("audrey").orElseThrow();
        assertTrue(audrey.has(Permission.AUDIT_VIEW));
        assertFalse(audrey.has(Permission.VIEW_PRIVATE), "a role without view.private is allowed");

        User old = d.user("old-intern").orElseThrow();
        assertTrue(old.disabled());
        assertFalse(old.has(Permission.VIEW_PRIVATE), "a disabled user has no permissions");

        assertEquals(Optional.of(siggi), d.byIdentity("github:2"));
        assertEquals(Optional.of(jane), d.byIdentity("google:3"));
        assertEquals(Optional.empty(), d.byIdentity("google:4"));
        assertEquals(Optional.empty(), d.user("nobody"));

        // Password sign-in: only enabled users with a password.
        assertEquals(Optional.of(HASH), d.credentials().passwordHash("siggi"));
        assertEquals(Optional.empty(), d.credentials().passwordHash("jane"));
        assertEquals(Optional.empty(), d.credentials().passwordHash("old-intern"));
        assertEquals(Optional.empty(), d.credentials().passwordHash("unknown"));
    }

    @Test
    void noAuthSectionMeansNoUsers() {
        UserDirectory d = UserDirectory.parse(null);
        assertTrue(d.users().isEmpty());
        assertEquals(Role.BUILT_IN.keySet(), d.roles().keySet());
    }

    // ---- validation ----

    @Test
    void unknownPermissionIsRejected() {
        List<String> errors = errors("""
                {"roles": {"ops": ["view.private", "maintenance.mange", 5]}}""");
        assertEquals(2, errors.size(), errors.toString());
        assertTrue(errors.get(0).startsWith("auth.roles.\"ops\"[1]: unknown permission \"maintenance.mange\" (known: "
                + "view.private, events.annotate"), errors.get(0));
        assertTrue(errors.get(1).startsWith("auth.roles.\"ops\"[2]: unknown permission 5"), errors.get(1));
        assertThrows(IllegalArgumentException.class,
                () -> UserDirectory.parse(json("{\"roles\": {\"ops\": [\"VIEW.PRIVATE\"]}}")));
    }

    @Test
    void roleErrors() {
        List<String> errors = errors("""
                {"roles": {"admin": ["view.private"], "Ops Team": [], "list": "view.private"}}""");
        assertEquals(3, errors.size(), errors.toString());
        assertHasError(errors, "auth.roles.\"admin\": the built-in role \"admin\" can't be redefined");
        assertHasError(errors, "auth.roles.\"Ops Team\": role name must match");
        assertHasError(errors, "auth.roles.\"list\" must be an array");
        assertEquals(List.of("auth.roles must be an object"), errors("{\"roles\": []}"));
    }

    @Test
    void userErrorsAreAllReported() {
        List<String> errors = errors("""
                {"roles": {"ops": ["view.private"]},
                 "users": [
                   {"id": "alice", "role": "admin", "identities": ["google:1"]},
                   {"id": "alice", "role": "viewer"},
                   {"id": "bob", "role": "superuser"},
                   {"id": "carol", "role": "viewer", "identities": ["google:1", "gitlab:5", "nocolon", "google:", 3]},
                   {"id": "dave", "role": "viewer", "password": "pbkdf2-sha256$1000$AAAA$BBBB"},
                   {"id": "erin", "role": "viewer", "disabled": "yes", "email": "e@example.com"},
                   {"id": "frank"},
                   {"id": "Bad Name", "role": "viewer"},
                   {"id": "gina", "role": "viewer", "displayName": " "},
                   "henry"
                 ], %s}""".formatted(PROVIDERS));
        assertHasError(errors, "auth.users[1] \"alice\": duplicate user id");
        assertHasError(errors, "auth.users[2] \"bob\": unknown role \"superuser\" (available: viewer, editor, admin, ops)");
        assertHasError(errors, "auth.users[3] \"carol\": identities[0]: \"google:1\" already belongs to user \"alice\"");
        assertHasError(errors, "auth.users[3] \"carol\": identities[1]: unknown provider \"gitlab\" (available: google, github)");
        assertHasError(errors, "auth.users[3] \"carol\": identities[2] must be a string \"providerId:value\"");
        assertHasError(errors, "auth.users[3] \"carol\": identities[3] must be a string");
        assertHasError(errors, "auth.users[3] \"carol\": identities[4] must be a string");
        assertHasError(errors, "auth.users[4] \"dave\": password hash");
        assertTrue(errors.stream().noneMatch(e -> e.contains("AAAA")), "the hash is never echoed: " + errors);
        assertHasError(errors, "auth.users[5] \"erin\": unknown key \"email\"");
        assertHasError(errors, "auth.users[5] \"erin\": disabled must be true or false");
        assertHasError(errors, "auth.users[6] \"frank\": role must be a string");
        assertHasError(errors, "auth.users[7]: id must match");
        assertHasError(errors, "auth.users[8] \"gina\": displayName must be a non-empty string");
        assertHasError(errors, "auth.users[9] must be an object");
        assertEquals(14, errors.size(), errors.toString());
    }

    @Test
    void identitiesNeedConfiguredProviders() {
        assertEquals(List.of("auth.users[0] \"a\": identities[0]: unknown provider \"google\" (no providers configured)"),
                errors("{\"users\": [{\"id\": \"a\", \"role\": \"viewer\", \"identities\": [\"google:1\"]}]}"));
    }

    @Test
    void identifierOnTheSameUserTwiceIsAnError() {
        assertEquals(1, errors("""
                {"users": [{"id": "a", "role": "viewer", "identities": ["google:1", "google:1"]}], %s}"""
                .formatted(PROVIDERS)).size());
    }
}
