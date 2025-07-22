package com.pedromossi.caching.impl;

import com.pedromossi.caching.CacheProvider;
import com.pedromossi.caching.CacheService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;

import java.io.Serial;
import java.io.Serializable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.function.Function;
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
 * <li><strong>L1 Cache:</strong> Fast local in-memory cache (e.g., Caffeine)</li>
 * <li><strong>L2 Cache:</strong> Distributed cache for data sharing (e.g., Redis)</li>
 * <li><strong>Data Source:</strong> Original data repository when cache misses occur</li>
 * </ul>
 *
 * <p><strong>Cache Lookup Strategy:</strong></p>
 * <ol>
 * <li>Check L1 cache for immediate response.</li>
 * <li>On L1 miss, check L2 cache.</li>
 * <li>On L2 hit, asynchronously promote value to L1.</li>
 * <li>On complete miss, load from data source.</li>
 * <li>Asynchronously store loaded value in both cache layers.</li>
 * </ol>
 *
 * @since 1.1.0
 * @see CacheService
 * @see CacheProvider
 */
public class MultiLevelCacheService implements CacheService {

    /** Logger instance for this class. */
    private static final Logger log = LoggerFactory.getLogger(MultiLevelCacheService.class);

    /**
     * Map to track ongoing loading operations for cache keys.
     *
     * <p>This map is used to prevent multiple concurrent loads for the same key,
     * ensuring that only one data source query is executed per key at a time.</p>
     */
    private final ConcurrentHashMap<String, Future<Object>> loadingInProgress = new ConcurrentHashMap<>();

    /**
     * Sentinel object to represent null values in cache layers.
     *
     * <p>This sentinel pattern allows the cache to distinguish between:</p>
     * <ul>
     * <li>"Key not found in cache" (returns null from cache.get())</li>
     * <li>"Key found in cache but value is null" (returns NULL_SENTINEL)</li>
     * </ul>
     *
     * <p>This distinction is crucial for proper cache-aside pattern implementation,
     * preventing unnecessary data source queries for explicitly cached null values.</p>
     *
     * <p>The class implements {@link Serializable} to support distributed cache
     * storage where serialization may be required (e.g., Redis).</p>
     */
    private static final class NullValue implements Serializable {

        /** Serial version UID for serialization compatibility. */
        @Serial
        private static final long serialVersionUID = 1L;

        /**
         * Returns a string representation of the null sentinel.
         *
         * @return a descriptive string for logging and debugging purposes.
         */
        @Override
        public String toString() {
            return "NullValue";
        }
    }

    /** Singleton instance of the null value sentinel. */
    private static final NullValue NULL_SENTINEL = new NullValue();

    /** L1 cache provider for local fast access (may be null if not configured). */
    private final CacheProvider l1Cache;

    /** L2 cache provider for distributed access (may be null if not configured). */
    private final CacheProvider l2Cache;

    /** Executor service for asynchronous cache operations. */
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
     * <li>L1 only: Fast local caching without distribution.</li>
     * <li>L2 only: Distributed caching without local optimization.</li>
     * <li>L1 + L2: Full multi-level caching with optimal performance.</li>
     * <li>Neither: Direct data source access (cache disabled).</li>
     * </ul>
     *
     * <p><strong>Executor Requirements:</strong> The ExecutorService should be configured
     * with appropriate thread pool settings to handle the expected cache operation volume
     * without blocking application threads.</p>
     *
     * @param l1Cache  Optional L1 (local) cache provider for fast memory access.
     * @param l2Cache  Optional L2 (distributed) cache provider for data sharing.
     * @param executor ExecutorService for asynchronous cache write and invalidation operations
     * (must not be null).
     * @throws NullPointerException if executor is null.
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
     * @param <T> the type of the cached value.
     * @param key a unique identifier for the cached item (must not be null).
     * @param type the class of the expected return type (must not be null).
     * @param loader a function to fetch the data when not found in any cache layer
     * (must not be null).
     * @return the cached value or the result of the loader function.
     * @throws NullPointerException if any parameter is null.
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
     * <li><strong>L1 Cache Check:</strong> Immediate lookup in local memory cache.</li>
     * <li><strong>L2 Cache Check:</strong> Network lookup in distributed cache on L1 miss.</li>
     * <li><strong>Cache Promotion:</strong> Asynchronously promote L2 hits to L1 for future speed.</li>
     * <li><strong>Data Source Load:</strong> Execute loader function on complete cache miss.</li>
     * <li><strong>Cache Population:</strong> Asynchronously store loaded data in both cache layers.</li>
     * </ol>
     *
     * <p><strong>Type-Safe Deserialization:</strong> This method passes the specific {@code typeRef}
     * to the underlying cache providers. This is crucial for L2 caches (like Redis) to correctly
     * deserialize JSON back into complex Java objects, preventing {@code ClassCastException}.</p>
     *
     * <p><strong>Null Value Handling:</strong> The method properly handles null values
     * returned by the loader function by using a sentinel object for cache storage.
     * This prevents repeated execution of the loader for legitimately null results.</p>
     *
     * @param <T> the type of the cached value.
     * @param key a unique identifier for the cached item (must not be null).
     * @param typeRef a reference to the expected type for complex generic types
     * (must not be null).
     * @param loader a function to fetch the data when not found in any cache layer
     * (must not be null).
     * @return the cached value or the result of the loader function.
     * @throws NullPointerException if any parameter is null.
     * @throws RuntimeException if the loader function throws an exception.
     */
    @Override
    public <T> T getOrLoad(String key, ParameterizedTypeReference<T> typeRef, Supplier<T> loader) {
        // L1 Hit - fastest path with sub-millisecond response
        if (l1Cache != null) {
            try {
                Object value = l1Cache.get(key, typeRef); // Pass specific typeRef
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
                Object value = l2Cache.get(key, typeRef); // Pass specific typeRef
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

        while (true) {
            Future<Object> future = loadingInProgress.get(key);
            if (future == null) {
                FutureTask<Object> newTask = new FutureTask<>(() -> {
                    log.debug("Complete cache MISS for key: {}. Executing loader.", key);
                    T valueFromSource = loader.get();
                    Object valueToStore = wrapNullValue(valueFromSource);
                    executor.execute(() -> storeInCaches(key, valueToStore));
                    return valueToStore;
                });

                future = loadingInProgress.putIfAbsent(key, newTask);
                if (future == null) {
                    future = newTask;
                    newTask.run();
                }
            }

            try {
                Object result = future.get();
                return unwrapNullValue(result);
            } catch (Exception e) {
                loadingInProgress.remove(key, future);
                throw new RuntimeException("Failed to load value for key: " + key, e);
            } finally {
                loadingInProgress.remove(key, future);
            }
        }

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
     * <li><strong>L2 Invalidation:</strong> Remove from distributed cache first.</li>
     * <li><strong>Event Publication:</strong> L2 implementations may publish invalidation events.</li>
     * <li><strong>L1 Invalidation:</strong> Remove from local cache second.</li>
     * </ol>
     *
     * <p><strong>Order Rationale:</strong> L2 invalidation happens first to ensure that
     * invalidation events (e.g., Redis pub/sub) are published before local caches are
     * cleared. This ordering helps maintain consistency in distributed environments.</p>
     *
     * @param key the cache key to invalidate across all layers and instances
     * (must not be null).
     * @throws NullPointerException if key is null.
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
     * caches to optimize future access.</p>
     *
     * @param key the cache key for storage (must not be null).
     * @param value the value to store (may be the null sentinel).
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
     * <p>This allows the cache to distinguish between "key not found" and "key found
     * but value is null".</p>
     *
     * @param value the value to wrap (may be null).
     * @return the original value if not null, or the null sentinel if null.
     */
    private Object wrapNullValue(Object value) {
        return value != null ? value : NULL_SENTINEL;
    }

    /**
     * Unwraps a cached value, converting the sentinel back to null if present.
     *
     * <p>This method complements {@link #wrapNullValue(Object)} by restoring
     * the original null value when the sentinel is encountered during cache
     * retrieval.</p>
     *
     * @param <T> the expected return type for the unwrapped value.
     * @param cachedValue the value retrieved from cache (may be sentinel or actual value).
     * @return null if the cached value is the null sentinel, otherwise the cached value cast to T.
     */
    @SuppressWarnings("unchecked")
    private <T> T unwrapNullValue(Object cachedValue) {
        return cachedValue instanceof NullValue ? null : (T) cachedValue;
    }

    @Override
/**
 * Retrieves multiple items from cache layers or loads them from the data source in a single operation.
 *
 * <p>This method implements an optimized multi-level caching algorithm for batch operations,
 * designed to minimize network round trips and improve performance when dealing with multiple
 * cache keys simultaneously. It follows the same cache-aside pattern as the single-key version
 * but with batch-optimized operations.</p>
 *
 * <p><strong>Batch Lookup Strategy:</strong></p>
 * <ol>
 *   <li><strong>L1 Batch Check:</strong> Attempt to retrieve all keys from local cache in one operation</li>
 *   <li><strong>L2 Batch Check:</strong> For L1 misses, batch query the distributed cache</li>
 *   <li><strong>Batch Processing:</strong> Collect items from L2 and data source for single L1 update</li>
 *   <li><strong>Batch Loading:</strong> Execute loader function with remaining missing keys</li>
 *   <li><strong>Batch Storage:</strong> Asynchronously store all values in both cache layers efficiently</li>
 * </ol>
 *
 * <p><strong>Performance Optimizations:</strong></p>
 * <ul>
 *   <li>Uses LinkedHashSet to preserve key order for deterministic behavior</li>
 *   <li>Minimizes cache provider calls through bulk operations</li>
 *   <li>Reduces network overhead for distributed cache access</li>
 *   <li>Enables efficient data source bulk loading (e.g., SQL IN clauses)</li>
 *   <li>Combines multiple cache updates into a single asynchronous task</li>
 * </ul>
 *
 * <p><strong>Partial Results Handling:</strong> The method gracefully handles scenarios where
 * some keys are found in cache layers while others need to be loaded. The final result
 * contains all successfully retrieved and loaded values.</p>
 *
 * @param <T> the type of the cached values
 * @param keys a set of unique identifiers for the cached items (must not be null)
 * @param typeRef a reference to the expected type for all values (must not be null)
 * @param loader a function that receives missing keys and returns a map of key-value pairs (must not be null)
 * @return a map containing all successfully retrieved keys with their corresponding values
 * @throws NullPointerException if any parameter is null
 * @throws RuntimeException if the loader function throws an exception
 * @see #getOrLoad(String, ParameterizedTypeReference, Supplier)
 */
    public <T> Map<String, T> getOrLoadAll(
            Set<String> keys,
            ParameterizedTypeReference<T> typeRef,
            Function<Set<String>, Map<String, T>> loader) {

        final Map<String, T> result = new HashMap<>();
        // Use LinkedHashSet to preserve order of input keys for deterministic cache calls
        final Set<String> missingKeys = new LinkedHashSet<>(keys);

        // Combined map for L1 cache updates (from both L2 promotion and data source)
        final Map<String, Object> combinedL1Updates = new HashMap<>();

        // 1. Try to get all keys from L1 cache
        if (l1Cache != null) {
            try {
                Map<String, T> l1Result = l1Cache.getAll(keys, typeRef);
                l1Result.forEach((key, value) -> result.put(key, unwrapNullValue(value)));
                missingKeys.removeAll(l1Result.keySet());
                if (!l1Result.isEmpty()) {
                    log.debug("Cache HIT on L1 for keys: {}", l1Result.keySet());
                }
            } catch (Exception e) {
                log.error("Error reading from L1 cache for keys {}: {}", missingKeys, e.getMessage(), e);
            }
        }

        if (missingKeys.isEmpty()) {
            return result;
        }

        // 2. Try to get the remaining keys from L2 cache, preserving order
        if (l2Cache != null) {
            try {
                Map<String, T> l2Result = l2Cache.getAll(new LinkedHashSet<>(missingKeys), typeRef);
                if (!l2Result.isEmpty()) {
                    log.debug("Cache HIT on L2 for keys: {}", l2Result.keySet());
                    // Add to final result
                    l2Result.forEach((key, value) -> result.put(key, unwrapNullValue(value)));
                    missingKeys.removeAll(l2Result.keySet());

                    // Add L2 hits to the combined L1 updates map (for promotion)
                    if (l1Cache != null) {
                        combinedL1Updates.putAll(new HashMap<>(l2Result));
                    }
                }
            } catch (Exception e) {
                log.error("Error reading from L2 cache for keys {}: {}", missingKeys, e.getMessage(), e);
            }
        }

        if (missingKeys.isEmpty()) {
            // If we only have L2 promotions, execute them now
            if (!combinedL1Updates.isEmpty()) {
                executor.execute(() -> {
                    try {
                        l1Cache.putAll(combinedL1Updates);
                    } catch (Exception ex) {
                        log.error("Error promoting values to L1: {}", ex.getMessage(), ex);
                    }
                });
            }
            return result;
        }

        // 3. Load the remaining keys from the data source
        log.debug("Complete cache MISS for keys: {}. Executing loader.", missingKeys);
        Map<String, T> loadedFromSource = loader.apply(missingKeys);

        // Add loaded values to the final result
        result.putAll(loadedFromSource);

        // 4. Prepare loaded data for cache storage
        final Map<String, Object> valuesToStore = new HashMap<>();
        missingKeys.forEach(
                key -> valuesToStore.put(key, wrapNullValue(loadedFromSource.get(key))));

        // 5. Combine L2 promotions with newly loaded values for a single L1 update
        if (l1Cache != null) {
            combinedL1Updates.putAll(valuesToStore);
        }

        // 6. Execute a single async task to update both cache layers
        if (!valuesToStore.isEmpty() || !combinedL1Updates.isEmpty()) {

            executor.execute(() -> {
                // Update L2 cache with new values from data source
                if (!valuesToStore.isEmpty() && l2Cache != null) {
                    try {
                        l2Cache.putAll(valuesToStore);
                    } catch (Exception e) {
                        log.error("Error saving values to L2: {}", e.getMessage(), e);
                    }
                }

                // Update L1 cache with combined values (from L2 + data source)
                if (!combinedL1Updates.isEmpty() && l1Cache != null) {
                    try {
                        l1Cache.putAll(combinedL1Updates);
                    } catch (Exception e) {
                        log.error("Error saving values to L1: {}", e.getMessage(), e);
                    }
                }
            });
        }

        return result;
    }


    @Override
    /**
     * Invalidates multiple cache keys across all cache layers and distributed instances in a single operation.
     *
     * <p>This method provides comprehensive batch invalidation that ensures data consistency
     * across the entire multi-level caching infrastructure. It follows the same invalidation
     * strategy as single-key invalidation but optimized for bulk operations.</p>
     *
     * <p><strong>Batch Invalidation Strategy:</strong></p>
     * <ol>
     *   <li><strong>L2 Batch Eviction:</strong> Remove all keys from distributed cache first</li>
     *   <li><strong>Event Broadcasting:</strong> Publish batch invalidation events to other instances</li>
     *   <li><strong>L1 Batch Eviction:</strong> Remove all keys from local cache</li>
     * </ol>
     *
     * <p><strong>Ordering Rationale:</strong> L2 invalidation occurs first to ensure that
     * distributed invalidation events are published before local caches are cleared,
     * maintaining consistency in multi-instance deployments.</p>
     *
     * <p><strong>Performance Benefits:</strong></p>
     * <ul>
     *   <li>Reduces network round trips for distributed cache operations</li>
     *   <li>Minimizes message bus overhead through batched event publishing</li>
     *   <li>Optimizes resource utilization during bulk invalidation</li>
     *   <li>Enables atomic invalidation where supported by cache providers</li>
     * </ul>
     *
     * <p><strong>Error Handling:</strong> Individual key invalidation failures are logged
     * but don't prevent the invalidation of other keys in the batch. This ensures
     * maximum invalidation coverage even in partial failure scenarios.</p>
     *
     * @param keys a set of cache keys to invalidate across all layers and instances (must not be null)
     * @throws NullPointerException if keys is null
     * @see #invalidate(String)
     */
    public void invalidateAll(Set<String> keys) {
        log.debug("Scheduling invalidation for keys: {}", keys);
        executor.execute(() -> {
            if (l2Cache != null) {
                try {
                    l2Cache.evictAll(keys);
                } catch (Exception e) {
                    log.error("Error invalidating L2 cache for keys {}: {}", keys, e.getMessage(), e);
                }
            }
            if (l1Cache != null) {
                try {
                    l1Cache.evictAll(keys);
                } catch (Exception e) {
                    log.error("Error invalidating L1 cache for keys {}: {}", keys, e.getMessage(), e);
                }
            }
        });
    }

    /**
     * Stores multiple values in all available cache layers asynchronously.
     *
     * <p>This internal method handles batch cache population after multiple values
     * are loaded from the original data source. It stores all values in both L1 and L2
     * caches to optimize future batch access patterns.</p>
     *
     * <p><strong>Storage Strategy:</strong> Values are stored in L2 (distributed) cache
     * first to enable data sharing across instances, followed by L1 (local) cache
     * for immediate future access optimization.</p>
     *
     * @param items a map of key-value pairs to store (must not be null)
     */
    private void storeInCaches(Map<String, Object> items) {
        if (l2Cache != null) {
            try {
                l2Cache.putAll(items);
            } catch (Exception e) {
                log.error("Error saving values to L2: {}", e.getMessage(), e);
            }
        }
        if (l1Cache != null) {
            try {
                l1Cache.putAll(items);
            } catch (Exception e) {
                log.error("Error saving values to L1: {}", e.getMessage(), e);
            }
        }
    }
}