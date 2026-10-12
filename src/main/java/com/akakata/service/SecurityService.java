package com.akakata.service;

import com.akakata.context.ConfigurationManager;
import com.akakata.security.Credentials;
import com.akakata.security.crypto.AesGcmCipher;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import javax.inject.Singleton;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Objects;

/**
 * Issues versioned, expiring AES-GCM tokens for authenticated credential identities.
 *
 * <p>The default constructor generates a private instance key. Configuration can
 * name an environment variable containing a Base64 AES key for persistence across
 * restarts. A token proves possession of an issued identity; applications still
 * enforce account authorization, revocation and reconnect policy in their verifier.
 * Legacy tokens encrypted with the former fixed library key are intentionally invalid.
 */
@Singleton
public class SecurityService {

    /**
     * Default validity period for issued tokens.
     */
    public static final Duration DEFAULT_TOKEN_TTL = Duration.ofHours(1);

    /**
     * Maximum accepted token characters, preventing unlimited decode allocation.
     */
    private static final int MAXIMUM_TOKEN_LENGTH = 16_384;

    /**
     * Instance key copied from the supplied secret.
     */
    private final SecretKey key;

    /**
     * Positive validity period in whole seconds.
     */
    private final long ttlSeconds;

    /**
     * Clock used for issue and expiry checks.
     */
    private final Clock clock;

    /**
     * Creates an instance with an ephemeral 256-bit key and one-hour token validity.
     */
    public SecurityService() {
        this(AesGcmCipher.generateKey(), DEFAULT_TOKEN_TTL);
    }

    /**
     * Creates an instance with an application-managed AES key.
     *
     * @param secretKey the 128-, 192- or 256-bit AES key
     * @param ttl       the positive validity period, at least one second
     */
    public SecurityService(SecretKey secretKey, Duration ttl) {
        this(secretKey, ttl, Clock.systemUTC());
    }

    /**
     * Creates an instance with an explicit clock for deterministic expiry handling.
     *
     * @param secretKey the AES key copied by this service
     * @param ttl       the positive validity period in whole seconds
     * @param clock     the issue and verification clock
     * @throws IllegalArgumentException if the key or validity period is invalid
     */
    public SecurityService(SecretKey secretKey, Duration ttl, Clock clock) {
        byte[] encoded = Objects.requireNonNull(secretKey).getEncoded();
        if (!"AES".equalsIgnoreCase(secretKey.getAlgorithm()) || encoded == null
                || (encoded.length != 16 && encoded.length != 24 && encoded.length != 32)) {
            throw new IllegalArgumentException("Token key must be a 128-, 192- or 256-bit AES key");
        }
        key = new SecretKeySpec(encoded, "AES");
        ttlSeconds = Objects.requireNonNull(ttl).getSeconds();
        if (ttlSeconds <= 0) {
            throw new IllegalArgumentException("Token TTL must be at least one second");
        }
        try {
            Math.addExact(Objects.requireNonNull(clock).instant().getEpochSecond(), ttlSeconds);
        } catch (ArithmeticException invalid) {
            throw new IllegalArgumentException("Token TTL is too large");
        }
        this.clock = clock;
    }

    /**
     * Creates the default token service from properties and an optional environment secret.
     *
     * <p>{@code security.token.key.environment} contains a variable name, never the
     * secret itself. A blank name selects an ephemeral key. A named but absent,
     * malformed or wrongly sized secret fails initialization without logging its value.
     * {@code security.token.ttl.seconds} must be a positive integer.
     *
     * @param configuration the loaded application properties
     * @return the configured token service
     * @throws IllegalArgumentException if token configuration is invalid
     */
    public static SecurityService fromConfiguration(ConfigurationManager configuration) {
        return fromConfiguration(configuration, System::getenv);
    }

    /**
     * Reads secrets through a supplied environment accessor without logging their values.
     *
     * @param configuration the loaded application properties
     * @param environment   the environment variable accessor
     * @return the configured token service
     */
    static SecurityService fromConfiguration(ConfigurationManager configuration,
                                             java.util.function.Function<String, String> environment) {
        String ttl = configuration.getString("security.token.ttl.seconds", "3600").trim();
        long seconds;
        try {
            seconds = Long.parseLong(ttl);
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("Invalid security.token.ttl.seconds");
        }
        String variable = configuration.getString("security.token.key.environment", "").trim();
        if (variable.isEmpty()) {
            return new SecurityService(AesGcmCipher.generateKey(), Duration.ofSeconds(seconds));
        }
        String value = environment.apply(variable);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Token key environment variable is missing");
        }
        try {
            return new SecurityService(new SecretKeySpec(Base64.getDecoder().decode(value.trim()), "AES"),
                    Duration.ofSeconds(seconds));
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Invalid token key or TTL configuration");
        }
    }

    /**
     * Encrypts a bounded identity and its authenticated expiration timestamp.
     *
     * @param credentials the verified account identity
     * @return the versioned URL-safe token
     * @throws IllegalArgumentException if the identity is empty or excessively large
     */
    public String generateToken(Credentials credentials) {
        String identity = Objects.requireNonNull(Objects.requireNonNull(credentials).getRandomKey());
        if (identity.isBlank() || identity.length() > 2_048) {
            throw new IllegalArgumentException("Invalid token identity");
        }
        long expires = Math.addExact(clock.instant().getEpochSecond(), ttlSeconds);
        return "v1." + AesGcmCipher.encrypt(expires + "\n" + identity, key);
    }

    /**
     * Authenticates and checks expiry before returning the credential identity.
     *
     * @param encryptedToken the versioned credential token, or null
     * @return the verified identity, or null for null input
     * @throws IllegalArgumentException if a token is malformed, expired or unauthentic
     */
    public String verifyToken(String encryptedToken) {
        if (encryptedToken == null) {
            return null;
        }
        if (encryptedToken.length() > MAXIMUM_TOKEN_LENGTH || !encryptedToken.startsWith("v1.")) {
            throw new IllegalArgumentException("Invalid token");
        }
        try {
            String plain = AesGcmCipher.decrypt(encryptedToken.substring(3), key);
            int separator = plain.indexOf('\n');
            if (separator < 1) {
                throw new IllegalArgumentException("Invalid token");
            }
            long expires = Long.parseLong(plain.substring(0, separator));
            String identity = plain.substring(separator + 1);
            if (expires <= clock.instant().getEpochSecond() || identity.isBlank() || identity.length() > 2_048) {
                throw new IllegalArgumentException("Invalid or expired token");
            }
            return identity;
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Invalid or expired token");
        }
    }
}
