package com.akakata.cache.impl;

import com.akakata.cache.Cache;
import com.akakata.cache.CacheStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Provides a named cache registry and delegates queries to registered caches.
 *
 * <p>The registry uses an ordinary map. Callers must coordinate concurrent registry
 * mutations. Unknown cache names and delegated query failures return {@code null}.
 *
 * @author Kelvin
 */
public abstract class AbstractCacheStore implements CacheStore {

    /**
     * Logger for component lifecycle and diagnostic messages.
     */
    private static final Logger LOG = LoggerFactory.getLogger(AbstractCacheStore.class);

    /**
     * Cache store for read-only.
     */
    protected final Map<String, Cache> cacheStore;

    /**
     * Creates an empty named-cache registry.
     */
    public AbstractCacheStore() {
        cacheStore = new HashMap<>();
    }

    /**
     * Provides a registry-initialization hook. The base implementation does nothing.
     */
    @Override
    public void init() {
        // Initialization all cache store implements
    }

    /**
     * Disposes every registered cache and clears the registry.
     */
    @Override
    public void dispose() {
        // Initialization all cache store implements
        for (Cache cache : cacheStore.values()) {
            cache.dispose();
        }

        cacheStore.clear();
    }

    /**
     * Returns the cache registered under the supplied name.
     *
     * @param cacheName the registered cache name
     * @return the cache, or {@code null} for an unknown name
     */
    @Override
    public Cache<?> get(String cacheName) {
        if (!cacheStore.containsKey(cacheName)) {
            LOG.warn("Unknown cache name: " + cacheName);
            return null;
        }

        return cacheStore.get(cacheName);
    }

    /**
     * Delegates an identifier lookup to a named cache.
     *
     * @param <T> the cached entry type
     * @param cacheName the registered cache name
     * @param id the identifier
     * @return the entry, or {@code null} if the cache or entry is absent or lookup fails
     */
    @Override
    public <T> T getById(String cacheName, Integer id) {
        if (!cacheStore.containsKey(cacheName)) {
            LOG.warn("Unknown cache name: " + cacheName);
            return null;
        }

        try {
            @SuppressWarnings("unchecked")
            Cache<T> cache = cacheStore.get(cacheName);
            return cache.getById(id);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Delegates a property query to a named cache.
     *
     * @param <T> the cached entry type
     * @param cacheName the registered cache name
     * @param propertyName the property to query
     * @param value the value to store or encode
     * @return the matching entries, or {@code null} if the cache is absent or lookup fails
     */
    @Override
    public <T> List<T> getByPropertyName(String cacheName, String propertyName, Object value) {
        if (!cacheStore.containsKey(cacheName)) {
            LOG.warn("Unknown cache name: " + cacheName);
            return null;
        }

        try {
            @SuppressWarnings("unchecked")
            Cache<T> cache = cacheStore.get(cacheName);
            return cache.getByPropertyName(propertyName, value);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Returns entries from a named cache.
     *
     * @param <T> the cached entry type
     * @param cacheName the registered cache name
     * @return the cached entries, or {@code null} if the cache is absent or lookup fails
     */
    @Override
    public <T> List<T> getAll(String cacheName) {
        if (!cacheStore.containsKey(cacheName)) {
            LOG.warn("Unknown cache name: " + cacheName);
            return null;
        }

        try {
            @SuppressWarnings("unchecked")
            Cache<T> cache = cacheStore.get(cacheName);
            return cache.getAll();
        } catch (Exception e) {
            return null;
        }
    }
}
