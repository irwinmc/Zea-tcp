package com.akakata.cache.impl;

import com.akakata.cache.Cache;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Provides a concurrent identifier-to-entry map for custom cache implementations.
 *
 * <p>Initialization is an extension hook. Reloading and disposal clear the map;
 * property queries return an empty list until a subclass supplies a query strategy.
 *
 * @param <T> the cached entry type
 * @author Kelvin
 */
public abstract class AbstractCache<T> implements Cache<T> {

    /**
     * Concurrent identifier-to-entry map backing the cache.
     */
    protected final ConcurrentHashMap<Object, T> store;
    /**
     * Name used to register this cache in a cache store.
     */
    protected String cacheName;

    /**
     * Creates an empty concurrent entry map.
     */
    public AbstractCache() {
        store = new ConcurrentHashMap<>();
    }

    /**
     * Provides an initialization hook. The base implementation does nothing.
     */
    @Override
    public void init() {
        // ... Overridden by custom cache
    }

    /**
     * Clears all cached entries.
     */
    @Override
    public void dispose() {
        store.clear();
    }

    /**
     * Clears the cache. Subclasses must populate it again when implementing reload.
     */
    @Override
    public void reload() {
        store.clear();

        // ... Override to reload memory cache
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public T getById(Object id) {
        return store.get(id);
    }

    /**
     * Provides a property-query extension hook. The base implementation returns an empty list.
     *
     * @param propertyName the property to query
     * @param value the value to store or encode
     * @return an empty list unless a subclass supplies property lookup
     */
    @Override
    public List<T> getByPropertyName(String propertyName, Object value) {
        return new ArrayList<>();
    }

    /**
     * Returns a snapshot of values currently in the concurrent map.
     *
     * @return a new list of cached values
     */
    @Override
    public List<T> getAll() {
        return new ArrayList<>(store.values());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getCacheName() {
        return cacheName;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setCacheName(String cacheName) {
        this.cacheName = cacheName;
    }
}
