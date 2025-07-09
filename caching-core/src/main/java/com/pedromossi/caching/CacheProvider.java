package com.pedromossi.caching;

import org.springframework.core.ParameterizedTypeReference;

/**
 * Core interface for cache providers in the caching library.
 *
 * <p>Defines fundamental operations that any cache implementation must support,
 * providing a consistent contract for different cache backends such as
 * local in-memory caches (L1) and distributed caches (L2).</p>
 *
 * <p>Implementations must handle:</p>
 * <ul>
 *   <li>Type-safe retrieval of cached values</li>
 *   <li>Storage of arbitrary objects with string keys</li>
 *   <li>Explicit cache eviction/invalidation</li>
 *   <li>Thread-safe operations for concurrent access</li>
 * </ul>
 *
 * @since 1.0.0
 * @see ParameterizedTypeReference
 */
public interface CacheProvider {

    /**
     * Retrieves a value from the cache with type safety.
     *
     * <p>This method attempts to retrieve a cached value for the given key
     * and ensures type safety by using a {@link ParameterizedTypeReference}.
     * If the key is not found or the cached value doesn't match the expected
     * type, {@code null} is returned.</p>
     *
     * <p>The type reference mechanism allows for safe retrieval of both simple
     * types and complex parameterized types (e.g., {@code List<String>},
     * {@code Map<String, Object>}).</p>
     *
     * <p><strong>Thread Safety:</strong> Implementations must ensure this method
     * is thread-safe and can be called concurrently from multiple threads.</p>
     *
     * @param <T> the expected type of the cached value
     * @param key the cache key to lookup (must not be null)
     * @param typeRef a reference to the expected type, used for runtime type checking
     * @return the cached value cast to type T, or {@code null} if:
     *         <ul>
     *           <li>The key is not found in the cache</li>
     *           <li>The cached value is not compatible with the requested type</li>
     *           <li>The cached value is explicitly null</li>
     *         </ul>
     * @throws NullPointerException if key or typeRef is null
     * @see ParameterizedTypeReference
     */
    <T> T get(String key, ParameterizedTypeReference<T> typeRef);

    /**
     * Stores a key-value pair in the cache.
     *
     * <p>This method inserts a new entry into the cache or updates an existing
     * entry if the key already exists. The behavior for existing keys is
     * implementation-specific but typically involves overwriting the previous value.</p>
     *
     * <p>The stored value will be subject to the cache's eviction policies
     * (such as size limits, TTL, or LRU policies) as configured by the specific
     * cache implementation.</p>
     *
     * <p><strong>Null Values:</strong> Implementations may choose to support
     * null values or treat them as cache deletions. Consult the specific
     * implementation documentation for null handling behavior.</p>
     *
     * <p><strong>Thread Safety:</strong> Implementations must ensure this method
     * is thread-safe and can be called concurrently from multiple threads.</p>
     *
     * @param key the cache key (must not be null)
     * @param value the object to be cached (may be null depending on implementation)
     * @throws NullPointerException if key is null
     * @throws IllegalArgumentException if the key or value is not supported by the implementation
     */
    void put(String key, Object value);

    /**
     * Removes a specific key from the cache.
     *
     * <p>This method performs an explicit eviction/invalidation of the specified
     * cache key. If the key doesn't exist in the cache, this operation should
     * complete successfully without any side effects.</p>
     *
     * <p>For distributed cache implementations (L2), this method may also
     * trigger invalidation events that are propagated to other cache instances
     * or application nodes to maintain cache consistency across the distributed
     * system.</p>
     *
     * <p><strong>Propagation:</strong> L2 cache implementations should consider
     * implementing cache invalidation propagation mechanisms (such as Redis pub/sub)
     * to ensure that cache evictions are communicated to all relevant cache instances.</p>
     *
     * <p><strong>Thread Safety:</strong> Implementations must ensure this method
     * is thread-safe and can be called concurrently from multiple threads.</p>
     *
     * @param key the cache key to remove (must not be null)
     * @throws NullPointerException if key is null
     */
    void evict(String key);
}