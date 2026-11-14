package io.siggi.healthchecker.auth;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link UserDirectory#resolve}: identity → user lookup, then rules (first match); and {@code auth.rules} parsing. */
class SignInResolutionTest {
    private static final String AUTH = """
            {
              "roles": {"ops": ["view.private", "maintenance.manage"]},
              "users": [
                {"id": "siggi", "displayName": "Siggi", "role": "admin", "identities": ["google:111", "corp:jane@example.com"]},
                {"id": "old", "role": "viewer", "identities": ["google:222"], "disabled": true}
              ],
              "rules": [
                {"provider": "google", "emailDomain": "example.com", "role": "viewer"},
                {"provider": "google", "group": "ops", "role": "ops"},
                {"provider": "github", "group": "staff", "role": "editor"}
              ],
              "providers": [
                {"id": "google", "type": "oidc"}, {"id": "corp", "type": "oidc"}, {"id": "github", "type": "oauth2"}
              ]
            }""";

    private static final ProviderConfig GOOGLE = oidc("google", ProviderConfig.SUB);
    private static final ProviderConfig CORP = oidc("corp", ProviderConfig.EMAIL);
    private static final ProviderConfig GITHUB = new ProviderConfig("github", ProviderConfig.Type.OAUTH2, "GitHub",
            "client", null, List.of(), List.of(), null, null, "https://github.example/authorize",
            "https://github.example/token", "https://github.example/user", "id", "login", true);

    private static ProviderConfig oidc(String id, String identifyBy) {
        return new ProviderConfig(id, ProviderConfig.Type.OIDC, id, "client", null, List.of("openid"), List.of(),
                "https://" + id + ".example", identifyBy, null, null, null, null, null, true);
    }

    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    private static UserDirectory directory() {
        return UserDirectory.parse(json(AUTH));
    }

    @Test
    void unknownIdentityGivesItsIdentifier() {
        SignInMatch match = directory().resolve(GOOGLE, json("""
                {"sub": "999", "email": "jane@elsewhere.example", "email_verified": true}"""));
        assertEquals("google:999", assertInstanceOf(SignInMatch.Unknown.class, match).identifier());
    }

    @Test
    void explicitUserIsFound() {
        SignInMatch.Allowed allowed = assertInstanceOf(SignInMatch.Allowed.class,
                directory().resolve(GOOGLE, json("{\"sub\": \"111\"}")));
        assertEquals("siggi", allowed.user().id());
        assertNull(allowed.rule());
    }

    @Test
    void explicitUserBeatsRule() {
        // google:111 is siggi (admin), although the first rule would make them a viewer.
        SignInMatch.Allowed allowed = assertInstanceOf(SignInMatch.Allowed.class, directory().resolve(GOOGLE, json("""
                {"sub": "111", "email": "siggi@example.com", "email_verified": true, "groups": ["ops"]}""")));
        assertEquals("siggi", allowed.user().id());
        assertEquals(Role.ADMIN, allowed.user().role());
        assertNull(allowed.rule());
    }

    @Test
    void disabledUserIsDisabledEvenWhenARuleMatches() {
        SignInMatch match = directory().resolve(GOOGLE, json("""
                {"sub": "222", "email": "old@example.com", "email_verified": true}"""));
        assertEquals("google:222", assertInstanceOf(SignInMatch.Disabled.class, match).identifier());
    }

    @Test
    void ruleGrantsRole() {
        SignInMatch.Allowed allowed = assertInstanceOf(SignInMatch.Allowed.class, directory().resolve(GOOGLE, json("""
                {"sub": "333", "name": "Jane Doe", "email": "Jane@Example.COM", "email_verified": true}""")));
        assertEquals("google:333", allowed.user().id());
        assertEquals("Jane Doe", allowed.user().displayName());
        assertEquals(Role.VIEWER, allowed.user().role());
        assertEquals("example.com", allowed.rule().emailDomain());
    }

    @Test
    void firstMatchingRuleWins() {
        // Both the domain rule (viewer) and the group rule (ops) match: the first one counts.
        SignInMatch.Allowed both = assertInstanceOf(SignInMatch.Allowed.class, directory().resolve(GOOGLE, json("""
                {"sub": "444", "email": "a@example.com", "email_verified": true, "groups": ["ops"]}""")));
        assertEquals("viewer", both.user().role().name());
        SignInMatch.Allowed group = assertInstanceOf(SignInMatch.Allowed.class, directory().resolve(GOOGLE, json("""
                {"sub": "445", "groups": ["dev", "ops"]}""")));
        assertEquals("ops", group.user().role().name());
        assertTrue(group.user().has(Permission.MAINTENANCE_MANAGE));
    }

    @Test
    void rulesOnlyApplyToTheirProvider() {
        SignInMatch match = directory().resolve(CORP, json("""
                {"sub": "x", "email": "new@example.com", "email_verified": true}"""));
        assertEquals("corp:new@example.com", assertInstanceOf(SignInMatch.Unknown.class, match).identifier());
        SignInMatch.Allowed staff = assertInstanceOf(SignInMatch.Allowed.class,
                directory().resolve(GITHUB, json("{\"id\": 42, \"login\": \"octo\", \"groups\": \"staff\"}")));
        assertEquals("github:42", staff.user().id());
        assertEquals("octo", staff.user().displayName());
        assertEquals(Role.EDITOR, staff.user().role());
    }

    @Test
    void emailIdentifierIgnoredWhenNotVerified() {
        UserDirectory users = directory();
        // corp identifies by email; jane@example.com is siggi's, but only a verified address counts.
        assertInstanceOf(SignInMatch.NoIdentifier.class, users.resolve(CORP, json("""
                {"sub": "x", "email": "jane@example.com", "email_verified": false}""")));
        assertInstanceOf(SignInMatch.NoIdentifier.class, users.resolve(CORP, json("""
                {"sub": "x", "email": "jane@example.com"}""")));
        assertEquals("siggi", assertInstanceOf(SignInMatch.Allowed.class, users.resolve(CORP, json("""
                {"sub": "x", "email": "jane@example.com", "email_verified": "true"}"""))).user().id());
    }

    @Test
    void emailDomainRuleIgnoresUnverifiedEmail() {
        SignInMatch match = directory().resolve(GOOGLE, json("""
                {"sub": "555", "email": "mallory@example.com", "email_verified": false}"""));
        assertEquals("google:555", assertInstanceOf(SignInMatch.Unknown.class, match).identifier());
        // Exact domain only, not subdomains or look-alikes.
        assertInstanceOf(SignInMatch.Unknown.class, directory().resolve(GOOGLE, json("""
                {"sub": "556", "email": "a@sub.example.com", "email_verified": true}""")));
        assertInstanceOf(SignInMatch.Unknown.class, directory().resolve(GOOGLE, json("""
                {"sub": "557", "email": "a@badexample.com", "email_verified": true}""")));
    }

    @Test
    void ruleUserIsLookedUpAgainstCurrentRules() {
        UserDirectory users = directory();
        SignInMatch.Allowed allowed = assertInstanceOf(SignInMatch.Allowed.class, users.resolve(GOOGLE, json("""
                {"sub": "333", "email": "jane@example.com", "email_verified": true}""")));
        String key = allowed.rule().key();
        assertEquals(Role.VIEWER, users.ruleUser("google:333", key, "Jane").orElseThrow().role());
        assertEquals("Jane", users.ruleUser("google:333", key, "Jane").orElseThrow().displayName());
        // The rule is gone (or changed) after a restart: the session ends.
        UserDirectory changed = UserDirectory.parse(json(AUTH.replace(
                "\"emailDomain\": \"example.com\", \"role\": \"viewer\"",
                "\"emailDomain\": \"example.com\", \"role\": \"editor\"")));
        assertTrue(changed.ruleUser("google:333", key, "Jane").isEmpty());
        // A rule key can't be used for another provider's identifier, nor for one that is now a configured user.
        assertTrue(users.ruleUser("github:333", key, "Jane").isEmpty());
        assertTrue(users.ruleUser("google:111", key, "Jane").isEmpty());
    }

    @Test
    void ruleValidation() {
        List<String> errors = new ArrayList<>();
        UserDirectory.parse(json("""
                {
                  "rules": [
                    {"provider": "nope", "group": "x", "role": "viewer"},
                    {"provider": "google", "group": "x", "role": "boss"},
                    {"provider": "google", "role": "viewer"},
                    {"provider": "github", "emailDomain": "example.com", "role": "viewer"},
                    {"provider": "google", "emailDomain": "not a domain", "role": "viewer"},
                    {"provider": "google", "group": "", "role": "viewer"},
                    {"provider": "google", "group": "x", "role": "viewer", "extra": 1},
                    "nope"
                  ],
                  "providers": [{"id": "google", "type": "oidc"}, {"id": "github", "type": "oauth2"}]
                }"""), errors);
        assertHasError(errors, "auth.rules[0]: unknown provider \"nope\"");
        assertHasError(errors, "auth.rules[1]: unknown role \"boss\"");
        assertHasError(errors, "auth.rules[2]: needs a condition");
        assertHasError(errors, "auth.rules[3]: emailDomain needs an oidc provider");
        assertHasError(errors, "auth.rules[4]: emailDomain must be a domain name");
        assertHasError(errors, "auth.rules[5]: group must be a non-empty string");
        assertHasError(errors, "auth.rules[6]: unknown key \"extra\"");
        assertHasError(errors, "auth.rules[7] must be an object");
        assertEquals(8, errors.size(), errors.toString());
    }

    @Test
    void exampleRulesAreValid() {
        List<String> errors = new ArrayList<>();
        UserDirectory users = UserDirectory.parse(json(AUTH), errors);
        assertEquals(List.of(), errors);
        assertEquals(3, users.rules().size());
    }

    private static void assertHasError(List<String> errors, String expected) {
        assertTrue(errors.stream().anyMatch(e -> e.startsWith(expected)), "expected " + expected + " in " + errors);
    }
}
