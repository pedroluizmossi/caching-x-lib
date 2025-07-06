package com.pedromossi.caching;

/**
 * Core interface for cache providers.
 * Defines the basic operations that any cache implementation must support.
 *
 * @since 1.0.0
 */
public interface CacheProvider {

    /**
     * Retrieves a value from the cache.
     *
     * @param key The cache key.
     * @param type The expected type of the value (for deserialization).
     * @param <T> The type of the cached value.
     * @return The found value or null if it doesn't exist.
     */
    <T> T get(String key, Class<T> type);

    /**
     * Inserts or updates a value in the cache.
     *
     * @param key The cache key.
     * @param value The object to be stored.
     */
    void put(String key, Object value);

    /**
     * Removes an item from the cache.
     * In L2 implementations, this may also trigger invalidation events.
     *
     * @param key The key to be removed.
     */
    void evict(String key);
}
