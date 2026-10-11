package com.akakata.communication;

/**
 * Converts a value from one representation to another.
 *
 * @param <T> the input value type
 * @param <V> the converted value type
 * @author Kelvin
 */
public interface Transform<T, V> {

    /**
     * Converts the supplied input to the target representation.
     *
     * @param object the object to convert or compare
     * @return the converted value
     * @throws Exception if conversion fails
     */
    V convert(T object) throws Exception;
}
