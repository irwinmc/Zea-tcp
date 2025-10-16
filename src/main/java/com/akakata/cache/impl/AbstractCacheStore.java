package com.akakata.cache.impl;

import com.akakata.cache.Cache;
import com.akakata.cache.CacheStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author Kelvin
 */
public abstract class AbstractCacheStore implements CacheStore {

    private static final Logger LOG = LoggerFactory.getLogger(AbstractCacheStore.class);

    /**
     * Cache store for read-only
     */
    protected final Map<String, Cache> cacheStore;

    public AbstractCacheStore() {
        cacheStore = new HashMap<>();
    }

    /**
     * Initialization of cache
     */
    @Override
    public void init() {
        // Initialization all cache store implements
    }

    /**
     * Dispose when it shut down
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
     * Get cache by cache name
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
     * Get by id
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
     * Get by property name
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
     * Get all
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
