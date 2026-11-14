package io.siggi.healthchecker.auth;

/**
 * Hashes passwords for built-in login, as {@code pbkdf2-sha256$<iter>$<salt b64>$<hash b64>}.
 *
 * <p>Stub until M9: {@link #hash(char[])} throws {@link UnsupportedOperationException}.
 */
public final class PasswordHasher {
    public String hash(char[] password) {
        throw new UnsupportedOperationException("password hashing is not implemented yet");
    }
}
