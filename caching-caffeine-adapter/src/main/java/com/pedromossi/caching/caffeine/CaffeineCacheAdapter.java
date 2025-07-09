package com.pedromossi.caching.caffeine;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.pedromossi.caching.CacheProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;

/**
 * Caffeine cache adapter implementation for L1 (local) caching.
 * Provides high-performance in-memory caching using the Caffeine library.
 *
 * <p>This adapter implements the {@link CacheProvider} interface to provide
 * local caching capabilities with type-safe operations. It supports all
 * Caffeine cache specifications including size-based eviction, time-based
 * expiration, and refresh policies.</p>
 *
 * @since 1.0.0
 * @see CacheProvider
 */
public class CaffeineCacheAdapter implements CacheProvider {

    /** Logger instance for this class */
    private static final Logger log = LoggerFactory.getLogger(CaffeineCacheAdapter.class);

    /** The underlying Caffeine cache instance */
    private final Cache<String, Object> cache;

    /**
     * Creates a new CaffeineCacheAdapter with the specified configuration.
     *
     * <p>The specification string follows Caffeine's configuration format.
     * Common specifications include:</p>
     * <ul>
     *   <li>{@code maximumSize=1000} - Maximum number of entries</li>
     *   <li>{@code expireAfterWrite=5m} - Expire entries 5 minutes after write</li>
     *   <li>{@code expireAfterAccess=30s} - Expire entries 30 seconds after last access</li>
     *   <li>{@code refreshAfterWrite=1m} - Refresh entries 1 minute after write</li>
     * </ul>
     *
     * @param spec Caffeine cache specification string (e.g., "maximumSize=1000,expireAfterWrite=5m")
     * @throws IllegalArgumentException if the specification string is invalid
     * @see com.github.benmanes.caffeine.cache.Caffeine#from(String)
     */
    public CaffeineCacheAdapter(String spec) {
        this.cache = Caffeine.from(spec).build();
        log.info("CaffeineCacheAdapter initialized with specification: {}", spec);
    }

    /**
     * Retrieves a value from the cache with type safety.
     *
     * <p>This method performs type checking to ensure the cached value
     * matches the requested type. If the types don't match or the key
     * is not found, {@code null} is returned.</p>
     *
     * <p>The method supports both simple classes and parameterized types
     * through the use of {@link ParameterizedTypeReference}.</p>
     *
     * @param <T> the expected type of the cached value
     * @param key the cache key to retrieve
     * @param typeRef a reference to the expected type, used for type safety
     * @return the cached value cast to type T, or {@code null} if not found or type mismatch
     * @throws NullPointerException if key or typeRef is null
     * @see ParameterizedTypeReference
     */
    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(String key, ParameterizedTypeReference<T> typeRef) {
        Object value = cache.getIfPresent(key);
        if (value == null) {
            return null;
        }

        // Extract the raw class from the type reference for type checking
        java.lang.reflect.Type requestedType = typeRef.getType();
        Class<?> rawClass;
        if (requestedType instanceof Class) {
            rawClass = (Class<?>) requestedType;
        } else if (requestedType instanceof java.lang.reflect.ParameterizedType) {
            rawClass = (Class<?>) ((java.lang.reflect.ParameterizedType) requestedType).getRawType();
        } else {
            // Unsupported type for this check, returning null for safety.
            return null;
        }

        // Verify the cached value is an instance of the requested type
        if (rawClass.isInstance(value)) {
            return (T) value;
        }
        return null;
    }


    /**
     * Retrieves the underlying native Caffeine cache instance.
     *
     * <p>This method allows access to the raw Caffeine cache for advanced operations
     * that may not be covered by the {@link CacheProvider} interface.</p>
     *
     * @return the native Caffeine cache instance
     */
    public Cache<String, Object> getNativeCache() {
        return this.cache;
    }

    /**
     * Stores a key-value pair in the cache.
     *
     * <p>If the key already exists, the previous value will be overwritten.
     * The value will be subject to the cache's eviction policies as configured
     * during construction.</p>
     *
     * @param key the cache key (must not be null)
     * @param value the value to cache (may be null)
     * @throws NullPointerException if key is null
     */
    @Override
    public void put(String key, Object value) {
        cache.put(key, value);
    }

    /**
     * Removes a specific key from the cache.
     *
     * <p>This method performs an explicit invalidation of the specified key.
     * If the key doesn't exist, this operation has no effect.</p>
     *
     * <p>A debug log message is generated when a key is invalidated to help
     * with cache debugging and monitoring.</p>
     *
     * @param key the cache key to remove (must not be null)
     * @throws NullPointerException if key is null
     */
    @Override
    public void evict(String key) {
        log.debug("Invalidating local L1 key: {}", key);
        cache.invalidate(key);
    }
}