package com.akakata.service;

import com.akakata.security.Credentials;
import com.akakata.security.crypto.AesGcmCipher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Singleton;

/**
 * Encrypts credential keys into tokens and decrypts tokens to their original keys.
 *
 * @author Kelvin
 * @since 0.7.8
 */
@Singleton
public class SecurityService {

    /**
     * Creates a security service instance.
     */
    public SecurityService() {
    }

    /**
     * Logger for component lifecycle and diagnostic messages.
     */
    private static final Logger LOG = LoggerFactory.getLogger(SecurityService.class);

    /**
     * Encrypts the credential key as a URL-safe token.
     *
     * @param credentials the verified credential identity
     * @return the encrypted credential key
     * @throws RuntimeException if token generation fails
     */
    public String generateToken(Credentials credentials) {
        try {
            String randomKey = credentials.getRandomKey();
            String token = AesGcmCipher.encrypt(randomKey);
            LOG.debug("Token generated for credentials: {}", credentials);
            return token;
        } catch (Exception e) {
            LOG.error("Failed to generate token for credentials: {}", credentials, e);
            throw new RuntimeException("Token generation failed", e);
        }
    }

    /**
     * Decrypts a token to recover its credential key.
     *
     * @param encryptedToken the encrypted credential token
     * @return the credential key, or {@code null} for a null token
     * @throws RuntimeException if token decoding or authentication fails
     */
    public String verifyToken(String encryptedToken) {
        try {
            String randomKey = AesGcmCipher.decrypt(encryptedToken);
            LOG.debug("Token verified successfully");
            return randomKey;
        } catch (Exception e) {
            LOG.error("Failed to verify token", e);
            throw new RuntimeException("Token verification failed", e);
        }
    }
}
