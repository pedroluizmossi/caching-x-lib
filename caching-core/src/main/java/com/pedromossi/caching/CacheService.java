package com.pedromossi.caching;

import org.springframework.core.ParameterizedTypeReference;

import java.util.function.Supplier;

/**
 * High-level caching service interface for multi-layer cache operations.
 *
 * <p>Provides intelligent cache-aside patterns with automatic fallback mechanisms
 * across L1 (local) and L2 (distributed) cache layers. Implements layered caching
 * strategy with automatic promotion and asynchronous operations.</p>
 *
 * <p>Cache lookup order: L1 → L2 → Data Source</p>
 *
 * @since 1.0.0
 * @see CacheProvider
 * @see ParameterizedTypeReference
 */
public interface CacheService {

    /**
     * Retrieves an item from cache layers or loads it from the data source.
     *
     * <p>This method implements a comprehensive cache-aside pattern with multiple
     * cache layers. It follows this execution flow:</p>
     * <ol>
     *   <li>Attempts to retrieve from L1 cache (local memory)</li>
     *   <li>On L1 miss, attempts to retrieve from L2 cache (distributed)</li>
     *   <li>On L2 miss, executes the loader function to fetch from data source</li>
     *   <li>Stores the loaded value asynchronously in appropriate cache layers</li>
     *   <li>Returns the value to the caller</li>
     * </ol>
     *
     * <p>The method supports complex parameterized types through the use of
     * {@link ParameterizedTypeReference}, enabling type-safe caching of collections,
     * maps, and other generic types.</p>
     *
     * <p><strong>Cache Population Strategy:</strong> When data is loaded from the
     * source, it's typically stored in both L1 and L2 caches to optimize future
     * access patterns. The exact strategy may vary based on implementation.</p>
     *
     * <p><strong>Error Handling:</strong> If cache retrieval fails, the method
     * should gracefully fall back to the next layer or the loader function.
     * Cache failures should not prevent data access.</p>
     *
     * @param <T> the type of the cached value
     * @param key a unique identifier for the cached item (must not be null)
     * @param typeRef a reference to the expected type, enabling type-safe operations
     *                with complex generic types
     * @param loader a function to fetch the data when not found in any cache layer
     *               (must not be null)
     * @return the cached value or the result of the loader function, never null
     *         unless the loader itself returns null
     * @throws NullPointerException if key, typeRef, or loader is null
     * @throws RuntimeException if the loader function throws an exception
     * @see ParameterizedTypeReference
     */
    <T> T getOrLoad(String key, ParameterizedTypeReference<T> typeRef, Supplier<T> loader);

    /**
     * Retrieves an item from cache layers or loads it from the data source using a simple class type.
     *
     * <p>This is a convenience method that provides the same functionality as
     * {@link #getOrLoad(String, ParameterizedTypeReference, Supplier)} but uses
     * a simple {@link Class} reference instead of {@link ParameterizedTypeReference}.
     * This method is ideal for simple types that don't require generic type information.</p>
     *
     * <p>The execution flow is identical to the parameterized version:</p>
     * <ol>
     *   <li>L1 cache lookup</li>
     *   <li>L2 cache lookup on L1 miss</li>
     *   <li>Loader execution on cache miss</li>
     *   <li>Asynchronous cache population</li>
     * </ol>
     *
     * <p><strong>Type Safety:</strong> While this method is more convenient for simple
     * types, it cannot handle complex parameterized types like {@code List<String>}
     * or {@code Map<String, Object>}. For such cases, use the parameterized version.</p>
     *
     * @param <T> the type of the cached value
     * @param key a unique identifier for the cached item (must not be null)
     * @param type the class of the expected return type (must not be null)
     * @param loader a function to fetch the data when not found in any cache layer
     *               (must not be null)
     * @return the cached value or the result of the loader function, never null
     *         unless the loader itself returns null
     * @throws NullPointerException if key, type, or loader is null
     * @throws RuntimeException if the loader function throws an exception
     * @see #getOrLoad(String, ParameterizedTypeReference, Supplier)
     */
    <T> T getOrLoad(String key, Class<T> type, Supplier<T> loader);

    /**
     * Invalidates a cache key across all cache layers and application instances.
     *
     * <p>This method performs a comprehensive cache invalidation that includes:</p>
     * <ul>
     *   <li><strong>Local Invalidation:</strong> Removes the key from L1 cache</li>
     *   <li><strong>Distributed Invalidation:</strong> Removes the key from L2 cache</li>
     *   <li><strong>Propagation:</strong> Notifies other application instances to
     *       invalidate their local caches for the same key</li>
     * </ul>
     *
     * <p><strong>Distribution Mechanism:</strong> For distributed environments,
     * implementations should use messaging systems (such as Redis pub/sub) to
     * propagate invalidation events to all application instances. This ensures
     * cache consistency across the distributed system.</p>
     *
     * <p><strong>Event Flow:</strong></p>
     * <ol>
     *   <li>Invalidate key in local L1 cache</li>
     *   <li>Invalidate key in distributed L2 cache</li>
     *   <li>Publish invalidation event to message bus</li>
     *   <li>Other instances receive event and invalidate their L1 caches</li>
     * </ol>
     *
     * <p><strong>Use Cases:</strong></p>
     * <ul>
     *   <li>Data updates that require immediate cache consistency</li>
     *   <li>Manual cache management and cleanup</li>
     *   <li>Integration with external data modification events</li>
     *   <li>Cache warming/refresh strategies</li>
     * </ul>
     *
     * <p><strong>Performance Considerations:</strong> This operation may involve
     * network calls for distributed cache invalidation and message publishing.
     * Consider using asynchronous invalidation for performance-critical paths.</p>
     *
     * @param key the cache key to invalidate across all layers and instances
     *            (must not be null)
     * @throws NullPointerException if key is null
     */
    void invalidate(String key);
}