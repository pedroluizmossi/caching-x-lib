package com.pedromossi.caching;

import java.util.function.Supplier;

/**
 * Main interface for the cache service.
 * Abstracts the complexity of multiple cache layers and data loading.
 *
 * @since 1.0.0
 */
public interface CacheService {

    /**
     * Gets an item from the cache. If the item is not found in any layer,
     * the provided 'loader' function will be executed to fetch the data from the source of truth.
     * The result will then be stored asynchronously in the applicable cache layers.
     *
     * @param key A unique key for the item in the cache.
     * @param type The type of object to be returned (required for deserialization).
     * @param loader A function (Supplier) that knows how to fetch the original data.
     *               Will only be called in case of a complete cache miss.
     * @param <T> The type of the cached value.
     * @return The object, either from cache or freshly fetched from the source of truth.
     */
    <T> T getOrLoad(String key, Class<T> type, Supplier<T> loader);

    /**
     * Invalidates a cache key in all layers.
     * The implementation must ensure that invalidation is propagated to
     * other application instances (e.g., via Redis pub/sub).
     *
     * @param key The key to be invalidated.
     */
    void invalidate(String key);
}