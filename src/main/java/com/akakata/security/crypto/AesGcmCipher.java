package com.akakata.security.crypto;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Encodes and decodes strings with the project's AES-GCM key and URL-safe Base64.
 *
 * <p>Each encrypted value contains a random 12-byte IV followed by ciphertext and
 * an authentication tag. String-to-byte conversion uses the platform default charset.
 *
 * @author Kelvin
 */
public final class AesGcmCipher {

    /**
     * Cipher transformation used for authenticated encryption.
     */
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    /**
     * Authentication-tag size in bits.
     */
    private static final int TAG_LENGTH_BIT = 128;

    /**
     * Initialization-vector size in bytes.
     */
    private static final int IV_LENGTH = 12;

    /**
     * Key bytes used by the default cipher implementation.
     */
    private static final byte[] DEFAULT_KEY = "q7X1FXD1GLNalua5".getBytes();

    /**
     * Secure random source for initialization vectors.
     */
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * Cipher key constructed from the configured default key bytes.
     */
    private static final SecretKey SECRET_KEY = new SecretKeySpec(DEFAULT_KEY, "AES");

    /**
     * Prevents direct construction of this utility or singleton component.
     */
    private AesGcmCipher() {
    }

    /**
     * Encrypts a string using a fresh IV and encodes the result as URL-safe Base64.
     *
     * @param plainText the plaintext string, or null
     * @return the encoded ciphertext, or {@code null} for null input
     * @throws IllegalStateException if encryption fails
     */
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

    /**
     * Decodes and authenticates ciphertext before returning its plaintext.
     *
     * @param cipherText the URL-safe Base64 ciphertext, or null
     * @return the decrypted string, or {@code null} for null input
     * @throws IllegalArgumentException if the input is not valid URL-safe Base64
     * @throws IllegalStateException if decryption or authentication fails
     */
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
