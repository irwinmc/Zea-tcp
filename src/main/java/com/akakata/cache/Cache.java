package com.akakata.cache;

import java.util.List;

/**
 * Defines a named cache with lifecycle operations and identifier or property lookups.
 *
 * @param <T> the cached entry type
 * @author Kelvin
 */
public interface Cache<T> {

    /**
     * Initializes cache contents or resources.
     */
    void init();

    /**
     * Releases cache contents and resources.
     */
    void dispose();

    /**
     * Refreshes cached contents from the backing data source.
     */
    void reload();

    /**
     * Looks up an entry by its identifier.
     *
     * @param id the identifier
     * @return the cached entry, or {@code null} if absent
     */
    T getById(Object id);

    /**
     * Finds entries whose named property matches a value.
     *
     * @param propertyName property name
     * @param value the value to store or encode
     * @return the matching entries
     */
    List<T> getByPropertyName(String propertyName, Object value);

    /**
     * Returns all cached entries.
     *
     * @return the cached entries
     */
    List<T> getAll();

    /**
     * Get cache name.
     *
     * @return cache name
     */
    String getCacheName();

    /**
     * Set cache name.
     *
     * @param cacheName cache name
     */
    void setCacheName(String cacheName);
}
