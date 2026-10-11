package com.akakata.security;

/**
 * Provides a credential key and extensible authentication attributes.
 *
 * @author Kelvin
 */
public interface Credentials {

    /**
     * Returns the credential key used for token generation and cache identity.
     *
     * @return the credential key
     */
    String getRandomKey();

    /**
     * Returns the value stored under an attribute key.
     *
     * @param key the attribute or cache key
     * @return the stored value, or {@code null} if the key is absent or its value is null
     */
    Object getAttribute(String key);

    /**
     * Stores an attribute value under the supplied key.
     *
     * @param key the attribute or cache key
     * @param value the value to store or encode
     */
    void setAttribute(String key, Object value);

    /**
     * Removes the value stored under an attribute key.
     *
     * @param key the attribute or cache key
     */
    void removeAttribute(String key);
}
