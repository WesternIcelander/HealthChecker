package io.siggi.healthchecker.auth;

/**
 * An OIDC or OAuth 2.0 sign-in failed: discovery, the provider's response, {@code id_token} verification, or a
 * userinfo response without a usable identifier. The message is
 * meant for the log; pages show a generic "sign-in failed" instead.
 */
public final class OidcException extends Exception {
    public OidcException(String message) {
        super(message);
    }

    public OidcException(String message, Throwable cause) {
        super(message, cause);
    }
}
