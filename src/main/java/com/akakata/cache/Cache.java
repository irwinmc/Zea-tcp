package com.akakata.cache;

import java.util.List;

/**
 * @author Kelvin
 */
public interface Cache<T> {

    /**
     * Initialization of cache
     */
    void init();

    /**
     * Dispose when it shutdown
     */
    void dispose();

    /**
     * Reload cache when is modified
     */
    void reload();

    /**
     * Get by id
     *
     * @param id id
     * @return T
     */
    T getById(Object id);

    /**
     * Get by property name
     *
     * @param propertyName property name
     * @param value        value
     * @return T
     */
    List<T> getByPropertyName(String propertyName, Object value);

    /**
     * Get all cache
     *
     * @return List<T>
     */
    List<T> getAll();

    /**
     * Get cache name
     *
     * @return cache name
     */
    String getCacheName();

    /**
     * Set cache name
     *
     * @param cacheName cache name
     */
    void setCacheName(String cacheName);
}
