package com.pedromossi.caching.resilience;

import com.pedromossi.caching.CacheProvider;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;

import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;

/**
 * A resilient cache provider decorator that implements circuit breaker pattern for cache operations.
 *
 * <p>This decorator wraps any {@link CacheProvider} implementation with circuit breaker functionality
 * to provide fault tolerance and prevent cascading failures when the underlying cache becomes
 * unreliable or unavailable. It's particularly useful for distributed cache implementations (L2)
 * where network failures or cache server issues can impact application performance.</p>
 *
 * <p><strong>Circuit Breaker States:</strong></p>
 * <ul>
 *   <li><strong>CLOSED:</strong> Normal operation, all requests pass through to the delegate</li>
 *   <li><strong>OPEN:</strong> Failure threshold exceeded, requests fail fast without hitting the cache</li>
 *   <li><strong>HALF_OPEN:</strong> Limited requests allowed to test if the cache has recovered</li>
 * </ul>
 *
 * <p><strong>Benefits:</strong></p>
 * <ul>
 *   <li>Prevents application threads from blocking on failing cache operations</li>
 *   <li>Provides fast failure responses when cache is unavailable</li>
 *   <li>Automatically recovers when the underlying cache becomes healthy again</li>
 *   <li>Reduces resource consumption during cache outages</li>
 * </ul>
 *
 * <p><strong>Configuration:</strong> The circuit breaker behavior is fully configurable through
 * the provided {@link CircuitBreaker} instance, including failure thresholds, timeout settings,
 * and retry policies.</p>
 *
 * @since 1.0.0
 * @see CacheProvider
 * @see CircuitBreaker
 */
public class CircuitBreakerCacheProvider implements CacheProvider {
    private static final Logger log = LoggerFactory.getLogger(CircuitBreakerCacheProvider.class);

    private final CacheProvider delegate;
    private final CircuitBreaker circuitBreaker;

    /**
     * Creates a new CircuitBreakerCacheProvider with the specified delegate and circuit breaker.
     *
     * <p>The constructor sets up state transition monitoring to log circuit breaker state changes,
     * which is crucial for monitoring cache health and debugging resilience issues.</p>
     *
     * @param delegate the underlying cache provider to protect with circuit breaker (must not be null)
     * @param circuitBreaker the circuit breaker instance that controls fault tolerance behavior (must not be null)
     * @throws NullPointerException if delegate or circuitBreaker is null
     */
    public CircuitBreakerCacheProvider(CacheProvider delegate, CircuitBreaker circuitBreaker) {
        this.delegate = delegate;
        this.circuitBreaker = circuitBreaker;
        this.circuitBreaker.getEventPublisher()
                .onStateTransition(event -> log.warn("L2 Cache Circuit Breaker state changed: {}", event));
    }

    /**
     * Retrieves a value from the cache through the circuit breaker.
     *
     * <p>This method executes the cache retrieval operation through the circuit breaker.
     * If the circuit is OPEN, the operation fails fast without attempting to access
     * the underlying cache. In CLOSED or HALF_OPEN states, the operation is delegated
     * to the wrapped cache provider.</p>
     *
     * @param <T> the expected type of the cached value
     * @param key the cache key to retrieve (must not be null)
     * @param typeRef a reference to the expected type for type-safe deserialization (must not be null)
     * @return the cached value cast to type T, or null if not found or circuit is open
     * @throws NullPointerException if key or typeRef is null
     * @throws io.github.resilience4j.circuitbreaker.CallNotPermittedException if circuit breaker is OPEN
     * @see CacheProvider#get(String, ParameterizedTypeReference)
     */
    @Override
    public <T> T get(String key, ParameterizedTypeReference<T> typeRef) {
        Supplier<T> decoratedSupplier = () -> delegate.get(key, typeRef);
        return circuitBreaker.executeSupplier(decoratedSupplier);
    }

    /**
     * Stores a key-value pair in the cache through the circuit breaker.
     *
     * <p>This method executes the cache storage operation through the circuit breaker.
     * If the circuit is OPEN, the operation fails fast without attempting to store
     * in the underlying cache. This prevents write operations from blocking when
     * the cache is known to be failing.</p>
     *
     * @param key the cache key (must not be null)
     * @param value the value to cache (may be null)
     * @throws NullPointerException if key is null
     * @throws io.github.resilience4j.circuitbreaker.CallNotPermittedException if circuit breaker is OPEN
     * @see CacheProvider#put(String, Object)
     */
    @Override
    public void put(String key, Object value) {
        Runnable decoratedRunnable = () -> delegate.put(key, value);
        circuitBreaker.executeRunnable(decoratedRunnable);
    }

    /**
     * Removes a key from the cache through the circuit breaker.
     *
     * <p>This method executes the cache eviction operation through the circuit breaker.
     * If the circuit is OPEN, the operation fails fast without attempting to evict
     * from the underlying cache. This ensures eviction operations don't block during
     * cache outages.</p>
     *
     * @param key the cache key to remove (must not be null)
     * @throws NullPointerException if key is null
     * @throws io.github.resilience4j.circuitbreaker.CallNotPermittedException if circuit breaker is OPEN
     * @see CacheProvider#evict(String)
     */
    @Override
    public void evict(String key) {
        Runnable decoratedRunnable = () -> delegate.evict(key);
        circuitBreaker.executeRunnable(decoratedRunnable);
    }

    /**
     * Retrieves multiple values from the cache through the circuit breaker.
     *
     * <p>This method executes the batch cache retrieval operation through the circuit breaker.
     * If the circuit is OPEN, the operation fails fast and returns an empty map without
     * attempting to access the underlying cache.</p>
     *
     * @param <T> the expected type of all cached values
     * @param keys the set of cache keys to lookup (must not be null)
     * @param typeRef a reference to the expected type for all values (must not be null)
     * @return a map containing found keys and their corresponding values, or empty map if circuit is open
     * @throws NullPointerException if keys or typeRef is null
     * @throws io.github.resilience4j.circuitbreaker.CallNotPermittedException if circuit breaker is OPEN
     * @see CacheProvider#getAll(Set, ParameterizedTypeReference)
     */
    @Override
    public <T> Map<String, T> getAll(Set<String> keys, ParameterizedTypeReference<T> typeRef) {
        Supplier<Map<String, T>> decoratedSupplier = () -> delegate.getAll(keys, typeRef);
        return circuitBreaker.executeSupplier(decoratedSupplier);
    }

    /**
     * Stores multiple key-value pairs in the cache through the circuit breaker.
     *
     * <p>This method executes the batch cache storage operation through the circuit breaker.
     * If the circuit is OPEN, the operation fails fast without attempting to store
     * in the underlying cache, preventing batch write operations from blocking.</p>
     *
     * @param items a map of key-value pairs to store in the cache (must not be null)
     * @throws NullPointerException if items is null
     * @throws io.github.resilience4j.circuitbreaker.CallNotPermittedException if circuit breaker is OPEN
     * @see CacheProvider#putAll(Map)
     */
    @Override
    public void putAll(Map<String, Object> items) {
        Runnable decoratedRunnable = () -> delegate.putAll(items);
        circuitBreaker.executeRunnable(decoratedRunnable);
    }

    /**
     * Removes multiple keys from the cache through the circuit breaker.
     *
     * <p>This method executes the batch cache eviction operation through the circuit breaker.
     * If the circuit is OPEN, the operation fails fast without attempting to evict
     * from the underlying cache, ensuring batch eviction operations don't block.</p>
     *
     * @param keys the set of cache keys to remove (must not be null)
     * @throws NullPointerException if keys is null
     * @throws io.github.resilience4j.circuitbreaker.CallNotPermittedException if circuit breaker is OPEN
     * @see CacheProvider#evictAll(Set)
     */
    @Override
    public void evictAll(Set<String> keys) {
        Runnable decoratedRunnable = () -> delegate.evictAll(keys);
        circuitBreaker.executeRunnable(decoratedRunnable);
    }
}