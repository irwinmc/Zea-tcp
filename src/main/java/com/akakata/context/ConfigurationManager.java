package com.akakata.context;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Loads classpath properties and exposes typed values with caller-supplied defaults.
 *
 * <p>Integer parsing trims whitespace and falls back on missing or malformed values.
 * Boolean parsing follows {@link Boolean#parseBoolean(String)} for present values.
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
     * Returns a trimmed integer property, using the default for absent or malformed values.
     *
     * @param key the attribute or cache key
     * @param defaultValue the value used when the property is absent, or invalid where supported
     * @return the parsed integer or supplied default
     */
    public int getInt(String key, int defaultValue) {
        String value = properties.getProperty(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            LOG.warn("Invalid integer for {}: {}, using default {}", key, value, defaultValue);
            return defaultValue;
        }
    }

    /**
     * Parses a present property using Boolean.parseBoolean and uses the default only for absent
     * values.
     *
     * @param key the attribute or cache key
     * @param defaultValue the value used when the property is absent, or invalid where supported
     * @return the parsed boolean or supplied default
     */
    public boolean getBoolean(String key, boolean defaultValue) {
        String value = properties.getProperty(key);
        if (value == null) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value.trim());
    }

    /**
     * Returns a property or the supplied default when absent.
     *
     * @param key the attribute or cache key
     * @param defaultValue the value used when the property is absent, or invalid where supported
     * @return the property value or supplied default
     */
    public String getString(String key, String defaultValue) {
        return properties.getProperty(key, defaultValue);
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
