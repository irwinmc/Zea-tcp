package com.akakata.context;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Loads classpath properties and exposes typed values with caller-supplied defaults.
 *
 * <p>Optional getters use their defaults only when a property is absent. Present
 * values are trimmed and parsed strictly; malformed values fail with the property
 * name. Required getters also reject missing and blank values.
 *
 * @author Kelvin
 */
public final class ConfigurationManager {

    /**
     * Logger for component lifecycle and diagnostic messages.
     */
    private static final Logger LOG = LoggerFactory.getLogger(ConfigurationManager.class);

    /**
     * Properties loaded from the classpath configuration resource.
     */
    private final Properties properties;

    /**
     * Loads the specified classpath properties resource.
     *
     * @param configPath the classpath properties resource name
     * @throws IOException if the classpath configuration cannot be loaded
     */
    public ConfigurationManager(String configPath) throws IOException {
        this.properties = loadConfig(configPath);
    }

    /**
     * Loads properties from a classpath resource and closes the input stream.
     *
     * @param path the resource or file path
     * @return the loaded properties
     * @throws IOException if the resource is absent or cannot be read
     */
    private Properties loadConfig(String path) throws IOException {
        Properties props = new Properties();
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(path)) {
            if (is == null) {
                throw new IOException("Config file not found: " + path);
            }
            props.load(is);
            LOG.info("Loaded configuration from {}", path);
        }
        return props;
    }

    /**
     * Returns a trimmed integer property, using the default only when absent.
     *
     * @param key          the attribute or cache key
     * @param defaultValue the value used when the property is absent
     * @return the parsed integer or supplied default
     * @throws IllegalArgumentException if a present value is not an integer
     */
    public int getInt(String key, int defaultValue) {
        String value = properties.getProperty(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("Invalid integer configuration: " + key);
        }
    }

    /**
     * Returns a trimmed long property, using the default only when absent.
     *
     * @param key          the property name
     * @param defaultValue the fallback value
     * @return the parsed value or fallback
     * @throws IllegalArgumentException if a present value is not a long integer
     */
    public long getLong(String key, long defaultValue) {
        String value = properties.getProperty(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("Invalid long configuration: " + key);
        }
    }

    /**
     * Reads a boolean property without silently accepting misspelled values.
     *
     * @param key          the property name
     * @param defaultValue the fallback for an absent property
     * @return the explicitly parsed switch or fallback
     * @throws IllegalArgumentException if a present value is neither true nor false
     */
    public boolean getBoolean(String key, boolean defaultValue) {
        String value = properties.getProperty(key);
        if (value == null) {
            return defaultValue;
        }
        if ("true".equalsIgnoreCase(value.trim())) {
            return true;
        }
        if ("false".equalsIgnoreCase(value.trim())) {
            return false;
        }
        throw new IllegalArgumentException("Invalid boolean configuration: " + key);
    }

    /**
     * Returns a property or the supplied default when absent.
     *
     * @param key          the attribute or cache key
     * @param defaultValue the value used when the property is absent, or invalid where supported
     * @return the property value or supplied default
     */
    public String getString(String key, String defaultValue) {
        return properties.getProperty(key, defaultValue);
    }

    /**
     * Reads a required nonblank property without substituting a default value.
     *
     * @param key the required property name
     * @return the trimmed configured value
     * @throws IllegalArgumentException if the property is missing or blank
     */
    public String getRequiredString(String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing or blank configuration: " + key);
        }
        return value.trim();
    }

    /**
     * Reads a required integer without substituting a default value.
     *
     * @param key the required property name
     * @return the configured integer
     * @throws IllegalArgumentException if the property is missing, blank or malformed
     */
    public int getRequiredInt(String key) {
        String value = getRequiredString(key);
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("Invalid integer configuration: " + key);
        }
    }

    /**
     * Returns the live properties object without making a copy.
     *
     * @return the mutable loaded properties
     */
    public Properties getProperties() {
        return properties;
    }

}
