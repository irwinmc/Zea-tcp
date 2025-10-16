package com.akakata.security.crypto;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Base64;

/**
 *
 * @author Kelvin
 */
public final class AesGcmCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int TAG_LENGTH_BIT = 128;
    private static final int IV_LENGTH = 12;
    private static final byte[] DEFAULT_KEY = "q7X1FXD1GLNalua5".getBytes();

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final SecretKey SECRET_KEY = new SecretKeySpec(DEFAULT_KEY, "AES");

    private AesGcmCipher() {
    }

    public static String encrypt(String plainText) {
        if (plainText == null) {
            return null;
        }
        byte[] iv = new byte[IV_LENGTH];
        RANDOM.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, SECRET_KEY, new GCMParameterSpec(TAG_LENGTH_BIT, iv));
            byte[] encrypted = cipher.doFinal(plainText.getBytes());

            byte[] combined = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(combined);
        } catch (Exception e) {
            throw new IllegalStateException("AES encryption failed", e);
        }
    }

    public static String decrypt(String cipherText) {
        if (cipherText == null) {
            return null;
        }
        byte[] combined = Base64.getUrlDecoder().decode(cipherText);
        byte[] iv = new byte[IV_LENGTH];
        byte[] encrypted = new byte[combined.length - IV_LENGTH];
        System.arraycopy(combined, 0, iv, 0, IV_LENGTH);
        System.arraycopy(combined, IV_LENGTH, encrypted, 0, encrypted.length);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, SECRET_KEY, new GCMParameterSpec(TAG_LENGTH_BIT, iv));
            byte[] plain = cipher.doFinal(encrypted);
            return new String(plain);
        } catch (Exception e) {
            throw new IllegalStateException("AES decryption failed", e);
        }
    }
}
