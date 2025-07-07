package com.pedromossi.caching;

import org.springframework.core.ParameterizedTypeReference;

import java.util.function.Supplier;

/**
 * Main interface for the cache service.
 * Abstracts the complexity of multiple cache layers and data loading.
 *
 * @since 1.0.0
 */
public interface CacheService {

    /**
     * Gets an item from the cache or loads it from the source.
     * <p>
     * If the item is not found in any cache layer, the provided {@code loader}
     * function will be executed to fetch the data. The result will then be
     * stored asynchronously in the applicable cache layers.
     *
     * @param key A unique key for the item.
     * @param typeRef A reference to the type of object to be returned.
     * @param loader A function to fetch the data on a cache miss.
     * @param <T> The type of the cached value.
     * @return The object from the cache or the source.
     */
    <T> T getOrLoad(String key, ParameterizedTypeReference<T> typeRef, Supplier<T> loader);

    /**
     * Gets an item from the cache or loads it from the source.
     * <p>
     * If the item is not found in any cache layer, the provided {@code loader}
     * function will be executed to fetch the data. The result will then be
     * stored asynchronously in the applicable cache layers.
     *
     * @param key A unique key for the item.
     * @param type The class of the object to be returned.
     * @param loader A function to fetch the data on a cache miss.
     * @param <T> The type of the cached value.
     * @return The object from the cache or the source.
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