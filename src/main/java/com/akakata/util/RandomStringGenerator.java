package com.akakata.util;

import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

/**
 * Generates alphanumeric identifiers using a secure random source.
 *
 * @author Kelvin
 */
public class RandomStringGenerator {

    /**
     * Creates a random string generator instance.
     */
    public RandomStringGenerator() {
    }

    /**
     * Default alphanumeric identifier length.
     */
    private static final int DEFAULT_LENGTH = 8;

    /**
     * Alphabet used by the random identifier generator.
     */
    private static final char[] alphaNumeric = new char[]{'A', 'B', 'C', 'D', 'E', 'F',
            'G', 'H', 'I', 'J', 'K', 'L', 'M', 'N', 'O', 'P', 'Q', 'R', 'S',
            'T', 'U', 'V', 'W', 'X', 'Y', 'Z', 'a', 'b', 'c', 'd', 'e', 'f',
            'g', 'h', 'i', 'j', 'k', 'l', 'm', 'n', 'o', 'p', 'q', 'r', 's',
            't', 'u', 'v', 'w', 'x', 'y', 'z', '1', '2', '3', '4', '5', '6',
            '7', '8', '9', '0'};

    /**
     * Generates an alphanumeric string using SHA1PRNG.
     *
     * <p>A nonpositive requested length selects eight characters. If the random
     * provider is unavailable, the implementation prints the failure and returns its
     * fallback string.
     *
     * @param length the number of bytes or characters requested
     * @return the generated string, or the fallback value if the provider is unavailable
     */
    public static String generateRandomString(int length) {
        String random = "ACK";

        int len = DEFAULT_LENGTH;
        if (length > 0) {
            len = length;
        }

        char[] randomChars = new char[len];
        try {
            SecureRandom wheel = SecureRandom.getInstance("SHA1PRNG");
            for (int i = 0; i < len; i++) {
                int nextChar = wheel.nextInt(alphaNumeric.length);
                randomChars[i] = alphaNumeric[nextChar];
            }
            random = new String(randomChars);
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
        }

        return random;
    }
}
