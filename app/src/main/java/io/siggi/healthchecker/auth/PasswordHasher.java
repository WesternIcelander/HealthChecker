package io.siggi.healthchecker.auth;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Hashes and verifies passwords for built-in login with PBKDF2-HMAC-SHA256 (JDK only).
 *
 * <p>Encoded form: {@code pbkdf2-sha256$<iterations>$<salt base64>$<hash base64>} (standard Base64,
 * padding optional when parsing). New hashes use {@value #DEFAULT_ITERATIONS} iterations, a random
 * {@value #SALT_BYTES}-byte salt and a {@value #HASH_BYTES}-byte hash. Parsing accepts
 * {@value #MIN_ITERATIONS}–{@value #MAX_ITERATIONS} iterations, a salt of
 * {@value #MIN_SALT_BYTES}–{@value #MAX_SALT_BYTES} bytes and exactly {@value #HASH_BYTES} hash bytes.
 */
public final class PasswordHasher {
    public static final String PREFIX = "pbkdf2-sha256";
    public static final int DEFAULT_ITERATIONS = 600_000;
    public static final int MIN_ITERATIONS = 600_000;
    public static final int MAX_ITERATIONS = 10_000_000;
    public static final int SALT_BYTES = 16;
    public static final int MIN_SALT_BYTES = 16;
    public static final int MAX_SALT_BYTES = 64;
    public static final int HASH_BYTES = 32;

    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";

    private final SecureRandom random;

    public PasswordHasher() {
        this(new SecureRandom());
    }

    PasswordHasher(SecureRandom random) {
        this.random = random;
    }

    /** A parsed hash string. */
    public record Hash(int iterations, byte[] salt, byte[] hash) {
        public Hash {
            salt = salt.clone();
            hash = hash.clone();
        }

        @Override
        public byte[] salt() {
            return salt.clone();
        }

        @Override
        public byte[] hash() {
            return hash.clone();
        }

        public String encode() {
            Base64.Encoder b64 = Base64.getEncoder();
            return PREFIX + "$" + iterations + "$" + b64.encodeToString(salt) + "$" + b64.encodeToString(hash);
        }
    }

    /** Hashes {@code password} with a fresh random salt. */
    public String hash(char[] password) {
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        return new Hash(DEFAULT_ITERATIONS, salt, derive(password, salt, DEFAULT_ITERATIONS, HASH_BYTES)).encode();
    }

    /**
     * Checks {@code password} against an encoded hash, comparing in constant time.
     *
     * @throws IllegalArgumentException when {@code encoded} is malformed (see {@link #parse(String)})
     */
    public boolean verify(char[] password, String encoded) {
        Hash parsed = parse(encoded);
        byte[] expected = parsed.hash;
        byte[] actual = derive(password, parsed.salt, parsed.iterations, expected.length);
        return MessageDigest.isEqual(expected, actual);
    }

    /**
     * Parses an encoded hash. The message of the exception never contains the hash itself.
     *
     * @throws IllegalArgumentException when the string isn't a valid {@code pbkdf2-sha256} hash
     */
    public static Hash parse(String encoded) {
        if (encoded == null) {
            throw new IllegalArgumentException("password hash is missing");
        }
        String[] parts = encoded.split("\\$", -1);
        if (parts.length != 4) {
            throw new IllegalArgumentException("password hash must look like " + PREFIX + "$<iterations>$<salt>$<hash>");
        }
        if (!parts[0].equals(PREFIX)) {
            throw new IllegalArgumentException("password hash must start with " + PREFIX + "$");
        }
        int iterations;
        if (!parts[1].matches("[0-9]{1,9}")) {
            throw new IllegalArgumentException("password hash has an invalid iteration count");
        }
        iterations = Integer.parseInt(parts[1]);
        if (iterations < MIN_ITERATIONS || iterations > MAX_ITERATIONS) {
            throw new IllegalArgumentException("password hash iteration count must be " + MIN_ITERATIONS + "–" + MAX_ITERATIONS);
        }
        byte[] salt = decode(parts[2], "salt");
        if (salt.length < MIN_SALT_BYTES || salt.length > MAX_SALT_BYTES) {
            throw new IllegalArgumentException("password hash salt must be " + MIN_SALT_BYTES + "–" + MAX_SALT_BYTES + " bytes");
        }
        byte[] hash = decode(parts[3], "hash");
        if (hash.length != HASH_BYTES) {
            throw new IllegalArgumentException("password hash must contain a " + HASH_BYTES + "-byte hash");
        }
        return new Hash(iterations, salt, hash);
    }

    private static byte[] decode(String value, String what) {
        if (value.isEmpty()) {
            throw new IllegalArgumentException("password hash has an empty " + what);
        }
        try {
            return Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("password hash has an invalid Base64 " + what);
        }
    }

    private static byte[] derive(char[] password, byte[] salt, int iterations, int bytes) {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, bytes * 8);
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(ALGORITHM + " is not available", e);
        } finally {
            spec.clearPassword();
        }
    }
}
