package com.akakata.cache;

import java.util.List;

/**
 * Looks up named caches and delegates typed entry queries and lifecycle operations.
 *
 * @author Kelvin
 */
public interface CacheStore {

    /**
     * Provides a registry-initialization hook. The base implementation does nothing.
     */
    void init();

    /**
     * Disposes every registered cache and clears the registry.
     */
    void dispose();

    /**
     * Returns the cache registered under the supplied name.
     *
     * @param cacheName cache name
     * @return the cache, or {@code null} for an unknown name
     */
    Cache<?> get(String cacheName);

    /**
     * Delegates an identifier lookup to a named cache.
     *
     * @param <T> the cached entry type
     * @param cacheName cache name
     * @param id the identifier
     * @return the entry, or {@code null} if the cache or entry is absent or lookup fails
     */
    <T> T getById(String cacheName, Integer id);

    /**
     * Delegates a property query to a named cache.
     *
     * @param <T> the cached entry type
     * @param cacheName cache name
     * @param propertyName property name
     * @param value the value to store or encode
     * @return the matching entries, or {@code null} if the cache is absent or lookup fails
     */
    <T> List<T> getByPropertyName(String cacheName, String propertyName, Object value);

    /**
     * Returns entries from a named cache.
     *
     * @param <T> the cached entry type
     * @param cacheName cache name
     * @return the cached entries, or {@code null} if the cache is absent or lookup fails
     */
    <T> List<T> getAll(String cacheName);
}
