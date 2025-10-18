package com.akakata.context;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Manages application configuration loading and property access.
 * Single responsibility: Configuration management.
 *
 * @author Kelvin
 */
public final class ConfigurationManager {

    private static final Logger LOG = LoggerFactory.getLogger(ConfigurationManager.class);

    private final Properties properties;

    public ConfigurationManager(String configPath) throws IOException {
        this.properties = loadConfig(configPath);
    }

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

    public boolean getBoolean(String key, boolean defaultValue) {
        String value = properties.getProperty(key);
        if (value == null) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value.trim());
    }

    public String getString(String key, String defaultValue) {
        return properties.getProperty(key, defaultValue);
    }

    /**
     * Get the underlying Properties object.
     *
     * @return Properties instance
     */
    public Properties getProperties() {
        return properties;
    }

}
