package io.siggi.healthchecker.auth;

/** Who an OIDC/OAuth sign-in belongs to ({@link UserDirectory#resolve}). */
public sealed interface SignInMatch {
    /**
     * The person may sign in.
     *
     * @param user the configured user, or for a rule a user whose id is the identifier
     * @param rule the rule that granted access, or {@code null} for a configured user
     */
    record Allowed(User user, Rule rule) implements SignInMatch {
    }

    /** The identifier belongs to a configured user who is disabled. */
    record Disabled(String identifier) implements SignInMatch {
    }

    /** Neither a user nor a rule matches the identifier. */
    record Unknown(String identifier) implements SignInMatch {
    }

    /** The provider didn't give a usable identifier (e.g. {@code identifyBy: email} with an unverified address). */
    record NoIdentifier() implements SignInMatch {
    }
}
