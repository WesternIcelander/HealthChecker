package io.siggi.healthchecker.auth;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import io.siggi.simplejwt.alg.ES256;
import io.siggi.simplejwt.alg.ES384;
import io.siggi.simplejwt.alg.ES512;
import io.siggi.simplejwt.alg.JWTAlgorithm;
import io.siggi.simplejwt.alg.RS256;
import io.siggi.simplejwt.alg.RS384;
import io.siggi.simplejwt.alg.RS512;

import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;

/**
 * A provider's signing keys, parsed from its JWKS document. Only keys usable with the allowed algorithms are kept:
 * RSA (at least 2048 bits) for RS256/384/512, and EC P-256/P-384/P-521 for ES256/384/512. Keys marked for another
 * {@code use} than {@code sig}, and keys that can't be parsed, are skipped.
 */
final class Jwks {
    /** The {@code id_token} algorithms accepted; never {@code none}, HS* or anything else. */
    static final Set<String> ALLOWED_ALGORITHMS = Set.of("RS256", "RS384", "RS512", "ES256", "ES384", "ES512");
    static final int MIN_RSA_BITS = 2048;

    /** One verification key. {@code kid} and {@code alg} are {@code null} when the JWK doesn't set them. */
    record Key(String kid, String alg, RSAPublicKey rsa, String curve, byte[] ecPoint) {
        /** Whether this key can verify a token signed with {@code algorithm}. */
        boolean supports(String algorithm) {
            if (alg != null && !alg.equals(algorithm)) return false;
            if (rsa != null) return algorithm.startsWith("RS");
            return switch (algorithm) {
                case "ES256" -> curve.equals("P-256");
                case "ES384" -> curve.equals("P-384");
                case "ES512" -> curve.equals("P-521");
                default -> false;
            };
        }

        /** A SimpleJWT verifier for {@code algorithm} with this key ({@link #supports} must be true). */
        JWTAlgorithm verifier(String algorithm) {
            return switch (algorithm) {
                case "RS256" -> new RS256(rsa);
                case "RS384" -> new RS384(rsa);
                case "RS512" -> new RS512(rsa);
                case "ES256" -> new ES256(ecPoint);
                case "ES384" -> new ES384(ecPoint);
                case "ES512" -> new ES512(ecPoint);
                default -> throw new IllegalArgumentException("unsupported algorithm " + algorithm);
            };
        }

        /** The raw signature length an ES algorithm produces with this key's curve (r ‖ s), or -1 for RSA. */
        int ecSignatureLength() {
            return ecPoint == null ? -1 : ecPoint.length - 1;
        }
    }

    private final List<Key> keys;

    private Jwks(List<Key> keys) {
        this.keys = List.copyOf(keys);
    }

    List<Key> keys() {
        return keys;
    }

    /** Whether some key has this {@code kid}. */
    boolean hasKid(String kid) {
        for (Key key : keys) {
            if (kid.equals(key.kid())) return true;
        }
        return false;
    }

    /**
     * The keys to try for a token: those with the token's {@code kid} (or every key when the token has none)
     * that support its algorithm.
     */
    List<Key> candidates(String kid, String algorithm) {
        List<Key> out = new ArrayList<>();
        for (Key key : keys) {
            if (kid != null && !kid.equals(key.kid())) continue;
            if (key.supports(algorithm)) out.add(key);
        }
        return out;
    }

    /** Parses a JWKS document ({@code {"keys": [...]}}). */
    static Jwks parse(JsonObject document) throws OidcException {
        if (!(document.get("keys") instanceof JsonArray array)) {
            throw new OidcException("JWKS has no \"keys\" array");
        }
        List<Key> keys = new ArrayList<>();
        for (JsonElement element : array) {
            if (!(element instanceof JsonObject jwk)) continue;
            Key key = parseKey(jwk);
            if (key != null) keys.add(key);
        }
        return new Jwks(keys);
    }

    private static Key parseKey(JsonObject jwk) {
        String use = string(jwk, "use");
        if (use != null && !use.equals("sig")) return null;
        String kid = string(jwk, "kid");
        String alg = string(jwk, "alg");
        if (alg != null && !ALLOWED_ALGORITHMS.contains(alg)) return null;
        String kty = string(jwk, "kty");
        try {
            if ("RSA".equals(kty)) {
                BigInteger n = new BigInteger(1, decode(jwk, "n"));
                BigInteger e = new BigInteger(1, decode(jwk, "e"));
                if (n.bitLength() < MIN_RSA_BITS || e.signum() <= 0) return null;
                RSAPublicKey key = (RSAPublicKey) KeyFactory.getInstance("RSA")
                        .generatePublic(new RSAPublicKeySpec(n, e));
                return new Key(kid, alg, key, null, null);
            }
            if ("EC".equals(kty)) {
                String crv = string(jwk, "crv");
                int size = switch (crv == null ? "" : crv) {
                    case "P-256" -> 32;
                    case "P-384" -> 48;
                    case "P-521" -> 66;
                    default -> -1;
                };
                if (size < 0) return null;
                byte[] x = decode(jwk, "x");
                byte[] y = decode(jwk, "y");
                if (x.length != size || y.length != size) return null;
                byte[] point = new byte[1 + 2 * size];
                point[0] = 0x04; // uncompressed
                System.arraycopy(x, 0, point, 1, size);
                System.arraycopy(y, 0, point, 1 + size, size);
                Key key = new Key(kid, alg, null, crv, point);
                // Fail now for points the curve rejects, not at verification time.
                key.verifier(switch (crv) {
                    case "P-256" -> "ES256";
                    case "P-384" -> "ES384";
                    default -> "ES512";
                });
                return key;
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    private static byte[] decode(JsonObject jwk, String member) {
        String value = string(jwk, member);
        if (value == null || value.isEmpty()) throw new IllegalArgumentException("missing " + member);
        return Base64.getUrlDecoder().decode(value);
    }

    private static String string(JsonObject object, String key) {
        return object.get(key) instanceof JsonPrimitive p && p.isString() ? p.getAsString() : null;
    }
}
