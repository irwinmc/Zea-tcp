package com.akakata.security;

import com.akakata.util.RandomStringGenerator;

import java.util.HashMap;
import java.util.Map;

/**
 * Stores a generated credential key and mutable attributes.
 *
 * <p>Equality and hashing depend only on the key, keeping cache lookups stable when
 * attributes change. Attribute access uses an ordinary map and requires external
 * coordination for concurrent mutation.
 *
 * @author Kelvin
 */
public class SimpleCredentials implements Credentials {

    /**
     * Unique key.
     */
    private String randomKey;

    /**
     * Attributes map.
     */
    private Map<String, Object> attributes;

    /**
     * Generates a credential key and creates an empty attribute map.
     */
    public SimpleCredentials() {
        this.randomKey = RandomStringGenerator.generateRandomString(8);
        this.attributes = new HashMap<>();
    }

    /**
     * Returns the credential key used for token generation and cache identity.
     *
     * @return the credential key
     */
    @Override
    public String getRandomKey() {
        return randomKey;
    }

    /**
     * Returns the value stored under an attribute key.
     *
     * @param key the attribute or cache key
     * @return the stored value, or {@code null} if the key is absent or its value is null
     */
    @Override
    public Object getAttribute(String key) {
        return attributes.get(key);
    }

    /**
     * Stores an attribute value under the supplied key.
     *
     * @param key the attribute or cache key
     * @param value the value to store or encode
     */
    @Override
    public void setAttribute(String key, Object value) {
        attributes.put(key, value);
    }

    /**
     * Removes the value stored under an attribute key.
     *
     * @param key the attribute or cache key
     */
    @Override
    public void removeAttribute(String key) {
        attributes.remove(key);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        return "Credentials [key=" + randomKey + ", attributes=" + attributes.toString() + "]";
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((randomKey == null) ? 0 : randomKey.hashCode());
        return result;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        SimpleCredentials other = (SimpleCredentials) obj;
        if (randomKey == null) {
            if (other.randomKey != null) {
                return false;
            }
        } else if (!randomKey.equals(other.randomKey)) {
            return false;
        }
        return true;
    }
}
