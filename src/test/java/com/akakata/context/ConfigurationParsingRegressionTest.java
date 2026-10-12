package com.akakata.context;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies strict parsing of configured values and explicit required-property handling.
 */
class ConfigurationParsingRegressionTest {

    /**
     * Creates the parser fixture.
     */
    ConfigurationParsingRegressionTest() {
    }

    /**
     * Uses declared defaults only for absent optional values and rejects missing required values.
     *
     * @throws Exception if the configuration fixture cannot be loaded
     */
    @Test
    void optionalDefaultsDoNotMakeRequiredPropertiesOptional() throws Exception {
        var configuration = new ConfigurationManager("conf-lifecycle.properties");
        String key = "test.required";
        assertEquals(42, configuration.getInt(key, 42));
        assertEquals(4_294_967_296L, configuration.getLong(key, 4_294_967_296L));
        assertTrue(configuration.getBoolean(key, true));
        assertEquals("default", configuration.getString(key, "default"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> configuration.getRequiredString(key)).getMessage().contains(key));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> configuration.getRequiredInt(key)).getMessage().contains(key));
        configuration.getProperties().setProperty(key, " 123 ");
        assertEquals("123", configuration.getRequiredString(key));
        assertEquals(123, configuration.getRequiredInt(key));
    }

    /**
     * Rejects malformed integer values rather than silently substituting an optional default.
     *
     * @param value the malformed configured number
     * @throws Exception if the configuration fixture cannot be loaded
     */
    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "invalid", "1.5", "2147483648"})
    void configuredIntegersCannotFallBackOnParseFailure(String value) throws Exception {
        var configuration = new ConfigurationManager("conf-lifecycle.properties");
        String key = "test.integer";
        configuration.getProperties().setProperty(key, value);
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> configuration.getInt(key, 42)).getMessage().contains(key));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> configuration.getRequiredInt(key)).getMessage().contains(key));
    }

    /**
     * Rejects malformed byte-budget values without silently enlarging or replacing the allowance.
     *
     * @param value the malformed configured number
     * @throws Exception if the configuration fixture cannot be loaded
     */
    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "invalid", "1.5", "9223372036854775808"})
    void configuredLongsCannotFallBackOnParseFailure(String value) throws Exception {
        var configuration = new ConfigurationManager("conf-lifecycle.properties");
        String key = "test.long";
        configuration.getProperties().setProperty(key, value);
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> configuration.getLong(key, 42)).getMessage().contains(key));
    }

    /**
     * Rejects misspelled switches rather than treating them as false or using a default.
     *
     * @param value the malformed configured switch
     * @throws Exception if the configuration fixture cannot be loaded
     */
    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "ture", "yes", "1"})
    void configuredBooleansCannotFallBackOnParseFailure(String value) throws Exception {
        var configuration = new ConfigurationManager("conf-lifecycle.properties");
        String key = "test.boolean";
        configuration.getProperties().setProperty(key, value);
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> configuration.getBoolean(key, false)).getMessage().contains(key));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> configuration.getBoolean(key, true)).getMessage().contains(key));
    }
}
