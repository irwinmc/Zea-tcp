package com.akakata.service;

import com.akakata.context.ConfigurationManager;
import com.akakata.context.ServerComponents;
import com.akakata.security.SimpleCredentials;
import com.akakata.security.crypto.AesGcmCipher;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies fail-closed authentication, secret validation and authenticated token expiry.
 */
class SecurityConfigurationRegressionTest {

    /**
     * Creates the security configuration fixture.
     */
    SecurityConfigurationRegressionTest() {
    }

    /**
     * Requires explicit opt-in for anonymous demonstration credentials.
     *
     * @throws Exception if the fixture resource cannot be read
     */
    @Test
    void defaultsDenyAuthenticationAndRejectMisspelledSecuritySwitches() throws Exception {
        ConfigurationManager configuration = new ConfigurationManager("conf-lifecycle.properties");
        ServerComponents defaults = ServerComponents.builder().build();
        assertNull(defaults.createCredentialsVerifier(configuration).verify(null));
        configuration.getProperties().setProperty("security.allow.anonymous", "true");
        assertNotNull(defaults.createCredentialsVerifier(configuration).verify(null));
        configuration.getProperties().setProperty("security.allow.anonymous", "ture");
        assertThrows(IllegalArgumentException.class, () -> defaults.createCredentialsVerifier(configuration));
        var identity = new SimpleCredentials();
        assertSame(identity, ServerComponents.builder().credentialsVerifier(ignored -> identity).build()
                .createCredentialsVerifier(configuration).verify(null));
    }

    /**
     * Uses independent instance secrets unless an application explicitly shares a key.
     */
    @Test
    void ephemeralServicesDoNotShareSecretsAndUseFreshIvs() {
        SecurityService first = new SecurityService();
        SecurityService second = new SecurityService();
        var credentials = new SimpleCredentials();
        String token = first.generateToken(credentials);
        assertEquals(credentials.getRandomKey(), first.verifyToken(token));
        assertNotEquals(token, first.generateToken(credentials));
        assertThrows(IllegalArgumentException.class, () -> second.verifyToken(token));
        assertNull(first.verifyToken(null));
    }

    /**
     * Checks the exact expiry boundary and authenticates all encrypted token bytes.
     */
    @Test
    void tokensExpireAndRejectTamperingLegacyFormatsAndOversizedInputs() {
        SecretKey key = AesGcmCipher.generateKey();
        Instant issued = Instant.parse("2026-10-12T00:00:00Z");
        SecurityService issuer = new SecurityService(key, Duration.ofSeconds(2), Clock.fixed(issued, ZoneOffset.UTC));
        var credentials = new SimpleCredentials();
        String token = issuer.generateToken(credentials);
        SecurityService valid = new SecurityService(key, Duration.ofSeconds(2), Clock.fixed(issued.plusSeconds(1), ZoneOffset.UTC));
        SecurityService expired = new SecurityService(key, Duration.ofSeconds(2), Clock.fixed(issued.plusSeconds(2), ZoneOffset.UTC));
        assertEquals(credentials.getRandomKey(), valid.verifyToken(token));
        assertThrows(IllegalArgumentException.class, () -> expired.verifyToken(token));
        byte[] encoded = Base64.getUrlDecoder().decode(token.substring(3));
        encoded[encoded.length - 1] ^= 1;
        String tampered = "v1." + Base64.getUrlEncoder().withoutPadding().encodeToString(encoded);
        for (String invalid : List.of(tampered, "", "v2." + token.substring(3), "v1.AA", "v1.%%%",
                "v1." + "x".repeat(16_384), AesGcmCipher.encrypt(credentials.getRandomKey(), key))) {
            assertThrows(IllegalArgumentException.class, () -> valid.verifyToken(invalid));
        }
    }

    /**
     * Loads a named environment key without accepting missing or malformed secrets.
     *
     * @throws Exception if the fixture resource cannot be read
     */
    @Test
    void environmentConfigurationFailsClosedWithoutDisclosingTheSecret() throws Exception {
        ConfigurationManager configuration = new ConfigurationManager("conf-lifecycle.properties");
        configuration.getProperties().setProperty("security.token.key.environment", "TEST_TOKEN_KEY");
        configuration.getProperties().setProperty("security.token.ttl.seconds", "60");
        String encoded = Base64.getEncoder().encodeToString(AesGcmCipher.generateKey().getEncoded());
        SecurityService first = SecurityService.fromConfiguration(configuration, name -> {
            assertEquals("TEST_TOKEN_KEY", name);
            return encoded;
        });
        SecurityService second = SecurityService.fromConfiguration(configuration, ignored -> encoded);
        var credentials = new SimpleCredentials();
        assertEquals(credentials.getRandomKey(), second.verifyToken(first.generateToken(credentials)));
        for (String invalid : List.of("", "private-invalid-secret", Base64.getEncoder().encodeToString(new byte[8]))) {
            var failure = assertThrows(IllegalArgumentException.class,
                    () -> SecurityService.fromConfiguration(configuration, ignored -> invalid));
            if (!invalid.isEmpty()) {
                assertFalse(failure.toString().contains(invalid));
            }
            assertNull(failure.getCause());
        }
        assertThrows(IllegalArgumentException.class, () -> SecurityService.fromConfiguration(configuration, ignored -> null));
        for (String ttl : List.of("0", "-1", "invalid")) {
            configuration.getProperties().setProperty("security.token.ttl.seconds", ttl);
            assertThrows(IllegalArgumentException.class, () -> SecurityService.fromConfiguration(configuration, ignored -> encoded));
        }
    }

    /**
     * Preserves Unicode and rejects invalid cipher envelopes or unsupported keys.
     */
    @Test
    void explicitCipherKeysUseUtf8AndValidateEnvelopeLength() {
        SecretKey key = AesGcmCipher.generateKey();
        String text = "player-\u73a9\u5bb6-\ud83c\udfae";
        assertEquals(text, AesGcmCipher.decrypt(AesGcmCipher.encrypt(text, key), key));
        assertThrows(IllegalArgumentException.class, () -> AesGcmCipher.decrypt("AA", key));
        assertThrows(IllegalArgumentException.class, () -> new SecurityService(new SecretKeySpec(new byte[8], "AES"), Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new SecurityService(key, Duration.ZERO));
    }
}
