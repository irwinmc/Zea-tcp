package com.akakata.cache;

import java.util.List;

/**
 * @author Kelvin
 */
public interface CacheStore {

    /**
     * Initialization of cache
     */
    void init();

    /**
     * Dispose when it shut down
     */
    void dispose();

    /**
     * Get cache
     *
     * @param cacheName cache name
     * @return cache
     */
    Cache<?> get(String cacheName);

    /**
     * Get by id
     *
     * @param <T>       One Object filter by id
     * @param cacheName cache name
     * @param id        id
     * @return T
     */
    <T> T getById(String cacheName, Integer id);

    /**
     * Get by property name
     *
     * @param <T>          Object list filter by property name
     * @param cacheName    cache name
     * @param propertyName property name
     * @param value        value
     * @return List<T>
     */
    <T> List<T> getByPropertyName(String cacheName, String propertyName, Object value);

    /**
     * Get all
     *
     * @param <T>       Object list of all the cache
     * @param cacheName cache name
     * @return List<T>
     */
    <T> List<T> getAll(String cacheName);
}
