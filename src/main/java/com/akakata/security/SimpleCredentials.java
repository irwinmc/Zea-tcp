package com.akakata.security;

import com.akakata.util.RandomStringGenerator;

import java.util.HashMap;
import java.util.Map;

/**
 * @author Kelvin
 */
public class SimpleCredentials implements Credentials {

    /**
     * Unique key
     */
    private String randomKey;

    /**
     * Attributes map
     */
    private Map<String, Object> attributes;

    /**
     * Constructor
     */
    public SimpleCredentials() {
        this.randomKey = RandomStringGenerator.generateRandomString(8);
        this.attributes = new HashMap<>();
    }

    @Override
    public String getRandomKey() {
        return randomKey;
    }

    @Override
    public Object getAttribute(String key) {
        return attributes.get(key);
    }

    @Override
    public void setAttribute(String key, Object value) {
        attributes.put(key, value);
    }

    @Override
    public void removeAttribute(String key) {
        attributes.remove(key);
    }

    @Override
    public String toString() {
        return "Credentials [key=" + randomKey + ", attributes=" + attributes.toString() + "]";
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((randomKey == null) ? 0 : randomKey.hashCode());
        result = prime * result + ((attributes == null) ? 0 : attributes.hashCode());
        return result;
    }

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
