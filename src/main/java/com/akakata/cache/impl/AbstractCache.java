package com.akakata.cache.impl;

import com.akakata.cache.Cache;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @param <T>
 * @author Kelvin
 */
public abstract class AbstractCache<T> implements Cache<T> {

    protected final ConcurrentHashMap<Object, T> store;
    protected String cacheName;

    public AbstractCache() {
        store = new ConcurrentHashMap<>();
    }

    @Override
    public void init() {
        // ... Overridden by custom cache
    }

    @Override
    public void dispose() {
        store.clear();
    }

    @Override
    public void reload() {
        store.clear();

        // ... Override to reload memory cache
    }

    @Override
    public T getById(Object id) {
        return store.get(id);
    }

    @Override
    public List<T> getByPropertyName(String propertyName, Object value) {
        return new ArrayList<>();
    }

    @Override
    public List<T> getAll() {
        return new ArrayList<>(store.values());
    }

    @Override
    public String getCacheName() {
        return cacheName;
    }

    @Override
    public void setCacheName(String cacheName) {
        this.cacheName = cacheName;
    }
}
