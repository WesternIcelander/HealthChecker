package io.siggi.healthchecker.auth;

/**
 * An OIDC sign-in failed: discovery, the provider's response, or {@code id_token} verification. The message is
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
