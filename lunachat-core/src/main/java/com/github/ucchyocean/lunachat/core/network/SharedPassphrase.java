package com.github.ucchyocean.lunachat.core.network;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

/** Deterministically derives a network key from the operator's shared passphrase. */
public final class SharedPassphrase {
    private static final byte[] SALT = "LunaChat network v2".getBytes(StandardCharsets.UTF_8);
    private static final int ITERATIONS = 120_000;
    private static final int KEY_BITS = 256;

    private SharedPassphrase() { }

    public static byte[] derive(String sharePass) {
        String value = sharePass == null ? "" : sharePass.trim();
        if (value.codePointCount(0, value.length()) < 12) {
            throw new IllegalArgumentException("sharePass must contain at least 12 characters");
        }
        PBEKeySpec specification = new PBEKeySpec(value.toCharArray(), SALT, ITERATIONS, KEY_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(specification).getEncoded();
        } catch (GeneralSecurityException unavailable) {
            throw new IllegalStateException("PBKDF2WithHmacSHA256 is unavailable", unavailable);
        } finally {
            specification.clearPassword();
        }
    }
}
