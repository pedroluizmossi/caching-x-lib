package com.pedromossi.caching;

import org.springframework.core.ParameterizedTypeReference;

import java.util.Map;
import java.util.Set;

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

    /**
     * Retrieves multiple values from the cache with type safety in a single operation.
     *
     * <p>This method performs efficient batch retrieval of cached values for
     * multiple keys simultaneously. It provides the same type safety guarantees
     * as {@link #get(String, ParameterizedTypeReference)} but optimized for
     * bulk operations to minimize cache access overhead.</p>
     *
     * <p><strong>Performance Benefits:</strong></p>
     * <ul>
     *   <li>Reduces cache access overhead through batched operations</li>
     *   <li>Minimizes network round trips for distributed cache implementations</li>
     *   <li>Optimizes memory allocation and CPU usage</li>
     *   <li>Enables more efficient cache backend utilization</li>
     * </ul>
     *
     * <p><strong>Partial Results:</strong> The returned map will only contain
     * entries for keys that were found in the cache and successfully deserialized
     * to the expected type. Missing keys or keys with incompatible types will
     * be absent from the result map.</p>
     *
     * <p><strong>Empty Results:</strong> If no keys are found or all cached
     * values are incompatible with the requested type, an empty map is returned
     * (not null).</p>
     *
     * <p><strong>Thread Safety:</strong> Implementations must ensure this method
     * is thread-safe and can be called concurrently from multiple threads.</p>
     *
     * @param <T> the expected type of all cached values
     * @param keys the set of cache keys to lookup (must not be null, empty sets are allowed)
     * @param typeRef a reference to the expected type for all values
     * @return a map containing found keys and their corresponding values cast to type T.
     *         Missing keys or type-incompatible values will be absent from the map.
     *         Never returns null, but may return an empty map.
     * @throws NullPointerException if keys or typeRef is null
     * @see #get(String, ParameterizedTypeReference)
     * @see ParameterizedTypeReference
     */
    <T> Map<String, T> getAll(Set<String> keys, ParameterizedTypeReference<T> typeRef);

    /**
     * Stores multiple key-value pairs in the cache in a single operation.
     *
     * <p>This method performs efficient batch storage of multiple cache entries
     * simultaneously. It provides the same storage semantics as
     * {@link #put(String, Object)} but optimized for bulk operations to
     * minimize cache access overhead and improve performance.</p>
     *
     * <p><strong>Performance Benefits:</strong></p>
     * <ul>
     *   <li>Reduces cache access overhead through batched operations</li>
     *   <li>Minimizes network round trips for distributed cache implementations</li>
     *   <li>Enables atomic batch operations where supported by the cache backend</li>
     *   <li>Optimizes memory allocation and serialization processes</li>
     * </ul>
     *
     * <p><strong>Existing Key Behavior:</strong> For keys that already exist
     * in the cache, the behavior follows the same semantics as individual
     * {@code put} operations - existing values are typically overwritten.</p>
     *
     * <p><strong>Null Values:</strong> The handling of null values in the map
     * follows the same implementation-specific behavior as single-key puts.
     * Some implementations may treat null values as deletion requests.</p>
     *
     * <p><strong>Partial Failures:</strong> In case of partial failures during
     * batch operations, implementations should make best effort to store as
     * many entries as possible while documenting specific failure behavior.</p>
     *
     * <p><strong>Thread Safety:</strong> Implementations must ensure this method
     * is thread-safe and can be called concurrently from multiple threads.</p>
     *
     * @param items a map of key-value pairs to store in the cache
     *              (must not be null, empty maps are allowed but result in no-op)
     * @throws NullPointerException if items is null or contains null keys
     * @throws IllegalArgumentException if any key or value is not supported by the implementation
     * @see #put(String, Object)
     */
    void putAll(Map<String, Object> items);

    /**
     * Removes multiple keys from the cache in a single operation.
     *
     * <p>This method performs efficient batch eviction of multiple cache keys
     * simultaneously. It provides the same eviction semantics as
     * {@link #evict(String)} but optimized for bulk operations to minimize
     * cache access overhead and improve performance.</p>
     *
     * <p><strong>Performance Benefits:</strong></p>
     * <ul>
     *   <li>Reduces cache access overhead through batched operations</li>
     *   <li>Minimizes network round trips for distributed cache implementations</li>
     *   <li>Enables atomic batch eviction where supported by the cache backend</li>
     *   <li>Optimizes resource utilization during bulk cleanup operations</li>
     * </ul>
     *
     * <p><strong>Missing Key Behavior:</strong> Keys that don't exist in the
     * cache are ignored without any error. The operation completes successfully
     * regardless of whether the keys were actually present.</p>
     *
     * <p><strong>Propagation:</strong> For distributed cache implementations (L2),
     * this method may trigger batch invalidation events that are propagated
     * to other cache instances or application nodes, maintaining cache
     * consistency across the distributed system more efficiently than
     * individual evictions.</p>
     *
     * <p><strong>Atomicity:</strong> While implementations should strive for
     * atomic batch eviction, the actual atomicity guarantees depend on the
     * underlying cache backend capabilities.</p>
     *
     * <p><strong>Thread Safety:</strong> Implementations must ensure this method
     * is thread-safe and can be called concurrently from multiple threads.</p>
     *
     * @param keys the set of cache keys to remove
     *             (must not be null, empty sets are allowed but result in no-op)
     * @throws NullPointerException if keys is null or contains null elements
     * @see #evict(String)
     */
    void evictAll(Set<String> keys);
}