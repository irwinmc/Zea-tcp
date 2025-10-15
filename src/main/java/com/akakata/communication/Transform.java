package com.akakata.communication;

/**
 * @author Kelvin
 */
public interface Transform<T, V> {

    /**
     * Convert object of type T to type V
     *
     * @param object
     * @return
     * @throws Exception
     */
    V convert(T object) throws Exception;
}
