package io.siggi.healthchecker.auth;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PasswordHasherTest {
    private final PasswordHasher hasher = new PasswordHasher();

    private static String b64(int bytes) {
        return Base64.getEncoder().encodeToString(new byte[bytes]);
    }

    @Test
    void hashVerifyRoundTrip() {
        String hash = hasher.hash("correct horse battery staple".toCharArray());
        assertTrue(hash.matches("pbkdf2-sha256\\$600000\\$[A-Za-z0-9+/=]+\\$[A-Za-z0-9+/=]+"), hash);
        PasswordHasher.Hash parsed = PasswordHasher.parse(hash);
        assertEquals(600_000, parsed.iterations());
        assertEquals(16, parsed.salt().length);
        assertEquals(32, parsed.hash().length);
        assertEquals(hash, parsed.encode());
        assertTrue(hasher.verify("correct horse battery staple".toCharArray(), hash));
    }

    @Test
    void unicodePasswordRoundTrip() {
        String hash = hasher.hash("pässwörð ✓".toCharArray());
        assertTrue(hasher.verify("pässwörð ✓".toCharArray(), hash));
        assertFalse(hasher.verify("passwoerd ✓".toCharArray(), hash));
    }

    @Test
    void saltIsRandom() {
        String a = hasher.hash("same".toCharArray());
        String b = hasher.hash("same".toCharArray());
        assertNotEquals(a, b);
        assertTrue(hasher.verify("same".toCharArray(), a));
        assertTrue(hasher.verify("same".toCharArray(), b));
    }

    @Test
    void wrongPassword() {
        String hash = hasher.hash("secret".toCharArray());
        assertFalse(hasher.verify("Secret".toCharArray(), hash));
        assertFalse(hasher.verify("secret ".toCharArray(), hash));
        assertFalse(hasher.verify(new char[0], hash));
    }

    @Test
    void sameSaltGivesSameHash() {
        byte[] salt = new byte[16];
        for (int i = 0; i < salt.length; i++) {
            salt[i] = (byte) i;
        }
        String encoded = new PasswordHasher.Hash(600_000, salt, new byte[32]).encode();
        assertFalse(hasher.verify("x".toCharArray(), encoded));
        PasswordHasher fixed = new PasswordHasher(new java.security.SecureRandom() {
            @Override
            public void nextBytes(byte[] bytes) {
                for (int i = 0; i < bytes.length; i++) {
                    bytes[i] = (byte) i;
                }
            }
        });
        String hash = fixed.hash("x".toCharArray());
        assertArrayEquals(salt, PasswordHasher.parse(hash).salt());
        assertEquals(hash, fixed.hash("x".toCharArray()));
    }

    @Test
    void verifyAcceptsHigherIterationsAndUnpaddedBase64() {
        PasswordHasher.Hash h = PasswordHasher.parse(hasher.hash("pw".toCharArray()));
        Base64.Encoder noPad = Base64.getEncoder().withoutPadding();
        String unpadded = "pbkdf2-sha256$600000$" + noPad.encodeToString(h.salt()) + "$" + noPad.encodeToString(h.hash());
        assertTrue(hasher.verify("pw".toCharArray(), unpadded));
    }

    @Test
    void malformedHashStrings() {
        String salt = b64(16);
        String hash = b64(32);
        String[] bad = {
                null,
                "",
                "plaintext",
                "pbkdf2-sha256$600000$" + salt,
                "pbkdf2-sha256$600000$" + salt + "$" + hash + "$extra",
                "pbkdf2-sha1$600000$" + salt + "$" + hash,
                "PBKDF2-SHA256$600000$" + salt + "$" + hash,
                "pbkdf2-sha256$$" + salt + "$" + hash,
                "pbkdf2-sha256$abc$" + salt + "$" + hash,
                "pbkdf2-sha256$-600000$" + salt + "$" + hash,
                "pbkdf2-sha256$1000$" + salt + "$" + hash,
                "pbkdf2-sha256$599999$" + salt + "$" + hash,
                "pbkdf2-sha256$10000001$" + salt + "$" + hash,
                "pbkdf2-sha256$99999999999$" + salt + "$" + hash,
                "pbkdf2-sha256$600000$$" + hash,
                "pbkdf2-sha256$600000$" + salt + "$",
                "pbkdf2-sha256$600000$not*base64$" + hash,
                "pbkdf2-sha256$600000$" + salt + "$not*base64",
                "pbkdf2-sha256$600000$" + b64(8) + "$" + hash,
                "pbkdf2-sha256$600000$" + b64(65) + "$" + hash,
                "pbkdf2-sha256$600000$" + salt + "$" + b64(31),
                "pbkdf2-sha256$600000$" + salt + "$" + b64(64),
                " pbkdf2-sha256$600000$" + salt + "$" + hash,
                "${env:ADMIN_PASSWORD_HASH}",
        };
        for (String s : bad) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> PasswordHasher.parse(s), String.valueOf(s));
            assertTrue(e.getMessage().startsWith("password hash"), e.getMessage());
            if (s != null && !s.isEmpty()) {
                assertFalse(e.getMessage().contains(s), "message must not echo the hash: " + e.getMessage());
            }
            assertThrows(IllegalArgumentException.class, () -> hasher.verify("pw".toCharArray(), s), String.valueOf(s));
        }
    }
}
