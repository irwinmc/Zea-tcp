package com.akakata.security;

/**
 * @author Kelvin
 */
public interface Credentials {

    /**
     * Get key from credentials
     *
     * @return key
     */
    String getRandomKey();

    /**
     * Get attribute by key from credentials
     *
     * @param key key
     * @return value
     */
    Object getAttribute(String key);

    /**
     * Set attribute to credentials
     *
     * @param key   key
     * @param value value
     */
    void setAttribute(String key, Object value);

    /**
     * Remove attribute from credentials
     *
     * @param key key
     */
    void removeAttribute(String key);
}
