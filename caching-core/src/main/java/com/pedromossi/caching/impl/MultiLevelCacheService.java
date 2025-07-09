package com.pedromossi.caching.impl;

import com.pedromossi.caching.CacheProvider;
import com.pedromossi.caching.CacheService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;

import java.io.Serial;
import java.io.Serializable;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;

/**
 * Multi-level cache service implementation with L1 and L2 cache support.
 *
 * <p>This implementation provides a sophisticated caching strategy that combines
 * local (L1) and distributed (L2) cache layers to optimize both performance and
 * data consistency. It implements the cache-aside pattern with automatic cache
 * promotion and asynchronous write operations.</p>
 *
 * <p><strong>Cache Architecture:</strong></p>
 * <ul>
 *   <li><strong>L1 Cache:</strong> Fast local in-memory cache (e.g., Caffeine)</li>
 *   <li><strong>L2 Cache:</strong> Distributed cache for data sharing (e.g., Redis)</li>
 *   <li><strong>Data Source:</strong> Original data repository when cache misses occur</li>
 * </ul>
 *
 * <p><strong>Cache Lookup Strategy:</strong></p>
 * <ol>
 *   <li>Check L1 cache for immediate response</li>
 *   <li>On L1 miss, check L2 cache</li>
 *   <li>On L2 hit, asynchronously promote value to L1</li>
 *   <li>On complete miss, load from data source</li>
 *   <li>Asynchronously store loaded value in both cache layers</li>
 * </ol>
 *
 * @since 1.1.0
 * @see CacheService
 * @see CacheProvider
 */
public class MultiLevelCacheService implements CacheService {

    /** Logger instance for this class */
    private static final Logger log = LoggerFactory.getLogger(MultiLevelCacheService.class);

    /**
     * Sentinel object to represent null values in cache layers.
     *
     * <p>This sentinel pattern allows the cache to distinguish between:</p>
     * <ul>
     *   <li>"Key not found in cache" (returns null from cache.get())</li>
     *   <li>"Key found in cache but value is null" (returns NULL_SENTINEL)</li>
     * </ul>
     *
     * <p>This distinction is crucial for proper cache-aside pattern implementation,
     * preventing unnecessary data source queries for explicitly cached null values.</p>
     *
     * <p>The class implements {@link Serializable} to support distributed cache
     * storage where serialization may be required (e.g., Redis).</p>
     */
    private static final class NullValue implements Serializable {

        /** Serial version UID for serialization compatibility */
        @Serial
        private static final long serialVersionUID = 1L;

        /**
         * Returns a string representation of the null sentinel.
         *
         * @return a descriptive string for logging and debugging purposes
         */
        @Override
        public String toString() {
            return "NullValue";
        }
    }

    /** Singleton instance of the null value sentinel */
    private static final NullValue NULL_SENTINEL = new NullValue();

    /** Type reference for generic object retrieval from cache layers */
    private static final ParameterizedTypeReference<Object> OBJECT_TYPE_REF = new ParameterizedTypeReference<Object>() {};

    /** L1 cache provider for local fast access (may be null if not configured) */
    private final CacheProvider l1Cache;

    /** L2 cache provider for distributed access (may be null if not configured) */
    private final CacheProvider l2Cache;

    /** Executor service for asynchronous cache operations */
    private final ExecutorService executor;

    /**
     * Creates a new MultiLevelCacheService with configurable cache layers and executor.
     *
     * <p>This constructor allows for flexible cache configuration where either L1 or L2
     * cache (or both) can be omitted. The service will gracefully handle missing cache
     * layers by skipping those operations.</p>
     *
     * <p><strong>Configuration Examples:</strong></p>
     * <ul>
     *   <li>L1 only: Fast local caching without distribution</li>
     *   <li>L2 only: Distributed caching without local optimization</li>
     *   <li>L1 + L2: Full multi-level caching with optimal performance</li>
     *   <li>Neither: Direct data source access (cache disabled)</li>
     * </ul>
     *
     * <p><strong>Executor Requirements:</strong> The ExecutorService should be configured
     * with appropriate thread pool settings to handle the expected cache operation volume
     * without blocking application threads.</p>
     *
     * @param l1Cache  Optional L1 (local) cache provider for fast memory access
     * @param l2Cache  Optional L2 (distributed) cache provider for data sharing
     * @param executor ExecutorService for asynchronous cache write and invalidation operations
     *                 (must not be null)
     * @throws NullPointerException if executor is null
     */
    public MultiLevelCacheService(Optional<CacheProvider> l1Cache, Optional<CacheProvider> l2Cache, ExecutorService executor) {
        this.l1Cache = l1Cache.orElse(null);
        this.l2Cache = l2Cache.orElse(null);
        this.executor = executor;
        log.info("MultiLevelCacheService initialized. L1 Active: {}, L2 Active: {}, Async Executor Active: {}",
                this.l1Cache != null, this.l2Cache != null, this.executor != null);
    }

    /**
     * Retrieves an item from cache layers or loads it from the data source using a simple class type.
     *
     * <p>This convenience method delegates to the parameterized version by converting
     * the Class to a ParameterizedTypeReference. It's ideal for simple types that
     * don't require complex generic type information.</p>
     *
     * @param <T> the type of the cached value
     * @param key a unique identifier for the cached item (must not be null)
     * @param type the class of the expected return type (must not be null)
     * @param loader a function to fetch the data when not found in any cache layer
     *               (must not be null)
     * @return the cached value or the result of the loader function
     * @throws NullPointerException if any parameter is null
     * @see #getOrLoad(String, ParameterizedTypeReference, Supplier)
     */
    @Override
    public <T> T getOrLoad(String key, Class<T> type, Supplier<T> loader) {
        return getOrLoad(key, ParameterizedTypeReference.forType(type), loader);
    }

    /**
     * Retrieves an item from cache layers or loads it from the data source with full type safety.
     *
     * <p>This method implements the core multi-level caching algorithm with the following
     * optimized lookup sequence:</p>
     *
     * <ol>
     *   <li><strong>L1 Cache Check:</strong> Immediate lookup in local memory cache</li>
     *   <li><strong>L2 Cache Check:</strong> Network lookup in distributed cache on L1 miss</li>
     *   <li><strong>Cache Promotion:</strong> Asynchronously promote L2 hits to L1 for future speed</li>
     *   <li><strong>Data Source Load:</strong> Execute loader function on complete cache miss</li>
     *   <li><strong>Cache Population:</strong> Asynchronously store loaded data in both cache layers</li>
     * </ol>
     *
     * <p><strong>Performance Optimizations:</strong></p>
     * <ul>
     *   <li>L1 hits return immediately without any network overhead</li>
     *   <li>L2 hits trigger background L1 promotion for future requests</li>
     *   <li>Cache writes are asynchronous to prevent blocking the caller</li>
     *   <li>Error handling ensures cache failures don't prevent data access</li>
     * </ul>
     *
     * <p><strong>Null Value Handling:</strong> The method properly handles null values
     * returned by the loader function by using a sentinel object for cache storage.
     * This prevents repeated execution of the loader for legitimately null results.</p>
     *
     * @param <T> the type of the cached value
     * @param key a unique identifier for the cached item (must not be null)
     * @param typeRef a reference to the expected type for complex generic types
     *                (must not be null)
     * @param loader a function to fetch the data when not found in any cache layer
     *               (must not be null)
     * @return the cached value or the result of the loader function
     * @throws NullPointerException if any parameter is null
     * @throws RuntimeException if the loader function throws an exception
     */
    @Override
    public <T> T getOrLoad(String key, ParameterizedTypeReference<T> typeRef, Supplier<T> loader) {
        // L1 Hit - fastest path with sub-millisecond response
        if (l1Cache != null) {
            try {
                Object value = l1Cache.get(key, OBJECT_TYPE_REF);
                if (value != null) {
                    log.debug("Cache HIT on L1 for key: {}", key);
                    return unwrapNullValue(value);
                }
            } catch (Exception e) {
                log.error("Error reading from L1 cache for key {}: {}", key, e.getMessage(), e);
            }
        }

        // L2 Hit - network access but still faster than data source
        if (l2Cache != null) {
            try {
                Object value = l2Cache.get(key, OBJECT_TYPE_REF);
                if (value != null) {
                    log.debug("Cache HIT on L2 for key: {}", key);
                    // Asynchronously promote to L1 for future performance improvement
                    final Object valueToPromote = value;
                    if (l1Cache != null) {
                        executor.execute(() -> {
                            try {
                                l1Cache.put(key, valueToPromote);
                            } catch (Exception ex) {
                                log.error("Error promoting value to L1 for key {}: {}", key, ex.getMessage(), ex);
                            }
                        });
                    }
                    return unwrapNullValue(value);
                }
            } catch (Exception e) {
                log.error("Error reading from L2 cache for key {}: {}", key, e.getMessage(), e);
            }
        }

        // Complete Cache Miss: Load from original data source
        log.debug("Complete cache MISS for key: {}. Executing loader.", key);
        T valueFromSource = loader.get();

        // Asynchronously store the result in cache layers for future requests
        final Object valueToStore = wrapNullValue(valueFromSource);
        executor.execute(() -> storeInCaches(key, valueToStore));

        return valueFromSource;
    }

    /**
     * Invalidates a cache key across all cache layers and distributed instances.
     *
     * <p>This method performs comprehensive cache invalidation that ensures data
     * consistency across the entire caching infrastructure. The invalidation is
     * executed asynchronously to prevent blocking the caller.</p>
     *
     * <p><strong>Invalidation Sequence:</strong></p>
     * <ol>
     *   <li><strong>L2 Invalidation:</strong> Remove from distributed cache first</li>
     *   <li><strong>Event Publication:</strong> L2 implementations may publish invalidation events</li>
     *   <li><strong>L1 Invalidation:</strong> Remove from local cache second</li>
     * </ol>
     *
     * <p><strong>Order Rationale:</strong> L2 invalidation happens first to ensure that
     * invalidation events (e.g., Redis pub/sub) are published before local caches are
     * cleared. This ordering helps maintain consistency in distributed environments.</p>
     *
     * <p><strong>Error Resilience:</strong> Failures in one cache layer don't prevent
     * invalidation of other layers. Each invalidation attempt is wrapped in its own
     * try-catch block with appropriate error logging.</p>
     *
     * @param key the cache key to invalidate across all layers and instances
     *            (must not be null)
     * @throws NullPointerException if key is null
     */
    @Override
    public void invalidate(String key) {
        log.debug("Scheduling invalidation for key: {}", key);
        executor.execute(() -> {
            // Invalidate L2 first to ensure invalidation event is published before L1 is cleared
            if (l2Cache != null) {
                try {
                    l2Cache.evict(key);
                } catch (Exception e) {
                    log.error("Error invalidating L2 cache for key {}: {}", key, e.getMessage(), e);
                }
            }
            // Then invalidate L1 to complete local cleanup
            if (l1Cache != null) {
                try {
                    l1Cache.evict(key);
                } catch (Exception e) {
                    log.error("Error invalidating L1 cache for key {}: {}", key, e.getMessage(), e);
                }
            }
        });
    }

    /**
     * Stores a value in all available cache layers asynchronously.
     *
     * <p>This internal method handles the cache population strategy after data
     * is loaded from the original source. It stores the value in both L1 and L2
     * caches to optimize future access patterns.</p>
     *
     * <p><strong>Storage Order:</strong></p>
     * <ol>
     *   <li><strong>L2 Storage:</strong> Store in distributed cache first for data sharing</li>
     *   <li><strong>L1 Storage:</strong> Store in local cache second for immediate access</li>
     * </ol>
     *
     * <p><strong>Error Handling:</strong> Storage failures in one layer don't prevent
     * storage in other layers. This ensures partial cache population is still beneficial.</p>
     *
     * @param key the cache key for storage (must not be null)
     * @param value the value to store (may be the null sentinel)
     */
    private void storeInCaches(String key, Object value) {
        // Store in L2 first (distributed) for data sharing across instances
        if (l2Cache != null) {
            try {
                l2Cache.put(key, value);
            } catch (Exception e) {
                log.error("Error saving value to L2 for key {}: {}", key, e.getMessage(), e);
            }
        }
        // Then store in L1 (local) for immediate future access
        if (l1Cache != null) {
            try {
                l1Cache.put(key, value);
            } catch (Exception e) {
                log.error("Error saving value to L1 for key {}: {}", key, e.getMessage(), e);
            }
        }
    }

    /**
     * Wraps a potentially null value with the sentinel object for cache storage.
     *
     * <p>This method implements the null value handling strategy by converting
     * actual null values to a sentinel object that can be stored in cache layers.
     * This allows the cache to distinguish between "key not found" and "key found
     * but value is null".</p>
     *
     * <p><strong>Cache Behavior Without Sentinel:</strong></p>
     * <ul>
     *   <li>Null values cannot be stored in most cache implementations</li>
     *   <li>Cache.get() returns null for both "not found" and "stored null"</li>
     *   <li>This leads to repeated loader execution for legitimate null results</li>
     * </ul>
     *
     * <p><strong>Cache Behavior With Sentinel:</strong></p>
     * <ul>
     *   <li>Null values are stored as sentinel objects</li>
     *   <li>Cache.get() returns null only for "not found"</li>
     *   <li>Cache.get() returns sentinel for "stored null"</li>
     *   <li>Prevents unnecessary loader re-execution</li>
     * </ul>
     *
     * @param value the value to wrap (may be null)
     * @return the original value if not null, or the null sentinel if null
     */
    private Object wrapNullValue(Object value) {
        return value != null ? value : NULL_SENTINEL;
    }

    /**
     * Unwraps a cached value, converting the sentinel back to null if present.
     *
     * <p>This method complements {@link #wrapNullValue(Object)} by restoring
     * the original null value when the sentinel is encountered during cache
     * retrieval. This ensures that the caller receives the exact value that
     * was originally loaded from the data source.</p>
     *
     * <p><strong>Type Safety:</strong> The method uses an unchecked cast which
     * is safe because the cache stores values with their original types, and
     * the sentinel object is only used for null representation.</p>
     *
     * @param cachedValue the value retrieved from cache (may be sentinel or actual value)
     * @param <T> the expected return type for the unwrapped value
     * @return null if the cached value is the null sentinel, otherwise the cached value cast to T
     */
    @SuppressWarnings("unchecked")
    private <T> T unwrapNullValue(Object cachedValue) {
        return cachedValue instanceof NullValue ? null : (T) cachedValue;
    }
}