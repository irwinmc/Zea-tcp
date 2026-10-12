package com.akakata.security.crypto;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Base64;

/**
 * Encrypts UTF-8 strings with AES-GCM and URL-safe Base64.
 *
 * <p>Each encrypted value contains a random 12-byte IV followed by ciphertext and
 * an authentication tag. Explicit-key overloads support application-managed secrets.
 * The legacy one-argument methods use an unpredictable process-local key; their
 * ciphertext cannot be decrypted by another process or after a restart.
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
     * Secure random source for initialization vectors.
     */
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * Ephemeral process key used only by the legacy convenience methods.
     */
    private static final SecretKey SECRET_KEY = generateKey();

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
        return encrypt(plainText, SECRET_KEY);
    }

    /**
     * Creates a random 256-bit AES key without retaining a fixed library secret.
     *
     * @return a newly generated key
     */
    public static SecretKey generateKey() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return new SecretKeySpec(bytes, "AES");
    }

    /**
     * Encrypts UTF-8 plaintext with the supplied key and a fresh random IV.
     *
     * @param plainText the plaintext, or null
     * @param key the application-managed AES key
     * @return URL-safe authenticated ciphertext, or null for null input
     * @throws IllegalStateException if encryption fails
     */
    public static String encrypt(String plainText, SecretKey key) {
        if (plainText == null) {
            return null;
        }
        byte[] iv = new byte[IV_LENGTH];
        RANDOM.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, Objects.requireNonNull(key), new GCMParameterSpec(TAG_LENGTH_BIT, iv));
            byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

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
     * @throws IllegalArgumentException if the envelope, encoding or authentication is invalid
     */
    public static String decrypt(String cipherText) {
        return decrypt(cipherText, SECRET_KEY);
    }

    /**
     * Validates the envelope and authentication tag before decoding UTF-8 plaintext.
     *
     * @param cipherText URL-safe authenticated ciphertext, or null
     * @param key the application-managed AES key
     * @return authenticated plaintext, or null for null input
     * @throws IllegalArgumentException if decoding, envelope validation or authentication fails
     */
    public static String decrypt(String cipherText, SecretKey key) {
        if (cipherText == null) {
            return null;
        }
        byte[] combined = Base64.getUrlDecoder().decode(cipherText);
        if (combined.length < IV_LENGTH + TAG_LENGTH_BIT / 8) {
            throw new IllegalArgumentException("Invalid AES-GCM envelope");
        }
        byte[] iv = new byte[IV_LENGTH];
        byte[] encrypted = new byte[combined.length - IV_LENGTH];
        System.arraycopy(combined, 0, iv, 0, IV_LENGTH);
        System.arraycopy(combined, IV_LENGTH, encrypted, 0, encrypted.length);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, Objects.requireNonNull(key), new GCMParameterSpec(TAG_LENGTH_BIT, iv));
            byte[] plain = cipher.doFinal(encrypted);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid AES-GCM ciphertext", e);
        }
    }
}
