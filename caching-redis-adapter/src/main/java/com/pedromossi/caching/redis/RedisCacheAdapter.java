package com.pedromossi.caching.redis;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.pedromossi.caching.CacheProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SessionCallback;

import java.time.Duration;
import java.util.*;
import java.util.stream.IntStream;

/**
 * Redis cache adapter implementation for L2 (distributed) caching.
 * Provides distributed caching capabilities with automatic invalidation across application instances.
 *
 * @since 1.0.0
 * @see CacheProvider
 */
public class RedisCacheAdapter implements CacheProvider {

    private static final Logger log = LoggerFactory.getLogger(RedisCacheAdapter.class);

    private final RedisTemplate<String, Object> redisTemplate;
    private final String invalidationTopic;
    private final Duration ttl;
    private final ObjectMapper objectMapper;

    /**
     * Creates a new RedisCacheAdapter with the specified configuration.
     *
     * @param redisTemplate     Redis template for cache operations (must not be null)
     * @param invalidationTopic Redis pub/sub topic for cache invalidation events (must not be null)
     * @param ttl               Time-to-live for cached entries (must not be null)
     * @param objectMapper      Jackson ObjectMapper for type conversion (must not be null)
     * @throws NullPointerException if any parameter is null
     */
    public RedisCacheAdapter(RedisTemplate<String, Object> redisTemplate, String invalidationTopic, Duration ttl, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.invalidationTopic = invalidationTopic;
        this.ttl = ttl;
        this.objectMapper = objectMapper;
        log.info("RedisCacheAdapter initialized. Invalidation topic: {}, TTL: {}", invalidationTopic, ttl);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(String key, ParameterizedTypeReference<T> typeRef) {
        try {
            Object value = redisTemplate.opsForValue().get(key);
            if (value == null) {
                return null;
            }

            JavaType javaType = TypeFactory.defaultInstance().constructType(typeRef.getType());
            return objectMapper.convertValue(value, javaType);

        } catch (Exception e) {
            log.error("Error retrieving or converting from Redis for key: {}", key, e);
        }
        return null;
    }

    @Override
    public void put(String key, Object value) {
        try {
            redisTemplate.opsForValue().set(key, value, ttl);
        } catch (Exception e) {
            log.error("Error saving to Redis for key: {}", key, e);
        }
    }

    @Override
    public void evict(String key) {
        log.debug("Removing key from Redis and publishing invalidation: {}", key);

        try {
            redisTemplate.delete(key);
        } catch (Exception e) {
            log.error("Error deleting key from Redis: {}", key, e);
        }

        try {
            redisTemplate.convertAndSend(invalidationTopic, key);
        } catch (Exception e) {
            log.error("Error publishing invalidation message for key: {}", key, e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    /**
     * Retrieves multiple values from Redis with type safety in a single operation.
     *
     * <p>This method performs efficient batch retrieval using Redis MGET command through
     * Spring Data Redis {@code multiGet} operation. It converts each retrieved value
     * from Redis format to the requested type using Jackson ObjectMapper.</p>
     *
     * <p><strong>Performance Benefits:</strong></p>
     * <ul>
     *   <li>Single network round trip for multiple key retrieval</li>
     *   <li>Efficient Redis MGET command utilization</li>
     *   <li>Batch type conversion processing</li>
     * </ul>
     *
     * <p><strong>Type Conversion:</strong> Each non-null value is converted using the
     * ObjectMapper with the specified JavaType. Values that fail conversion are
     * excluded from the result and logged as errors.</p>
     *
     * <p><strong>Order Preservation:</strong> The method preserves the relationship
     * between keys and their corresponding values by using indexed access to the
     * Redis response values.</p>
     *
     * @param <T> the expected type of all cached values
     * @param keys the set of cache keys to lookup (must not be null, empty sets are allowed)
     * @param typeRef a reference to the expected type for all values (must not be null)
     * @return a map containing found keys and their corresponding converted values.
     *         Missing keys or conversion failures will be absent from the map.
     *         Never returns null, but may return an empty map.
     * @throws NullPointerException if keys or typeRef is null
     * @see #get(String, ParameterizedTypeReference)
     */
    public <T> Map<String, T> getAll(Set<String> keys, ParameterizedTypeReference<T> typeRef) {
        if (keys == null || keys.isEmpty()) {
            return Map.of();
        }
        try {
            List<Object> values = redisTemplate.opsForValue().multiGet(keys);
            if (values == null) {
                return Map.of();
            }

            JavaType javaType = TypeFactory.defaultInstance().constructType(typeRef.getType());
            Map<String, T> result = new HashMap<>();
            List<String> keyList = new ArrayList<>(keys);

            IntStream.range(0, keyList.size()).forEach(i -> {
                Object value = values.get(i);
                if (value != null) {
                    try {
                        result.put(keyList.get(i), objectMapper.convertValue(value, javaType));
                    } catch (Exception e) {
                        log.error("Error converting value for key {}: {}", keyList.get(i), e.getMessage());
                    }
                }
            });
            return result;
        } catch (Exception e) {
            log.error("Error retrieving multiple keys from Redis: {}", keys, e);
            return Map.of();
        }
    }

    @Override
    /**
     * Stores multiple key-value pairs in Redis with TTL in a single operation.
     *
     * <p>This method performs efficient batch storage using Redis pipelining to ensure
     * optimal performance and proper TTL application. Since Redis multiSet doesn't support
     * TTL parameters, this implementation uses a pipelined approach to set each key-value
     * pair with the configured TTL.</p>
     *
     * <p><strong>Performance Benefits:</strong></p>
     * <ul>
     *   <li>Pipelined operations reduce network round trips</li>
     *   <li>Atomic execution within the pipeline context</li>
     *   <li>Consistent TTL application across all entries</li>
     * </ul>
     *
     * <p><strong>TTL Behavior:</strong> All entries are stored with the same TTL as
     * configured during adapter construction. This ensures consistent expiration
     * behavior across batch-stored entries.</p>
     *
     * <p><strong>Error Handling:</strong> If any error occurs during the batch operation,
     * it is logged and the entire batch operation may fail. Partial success scenarios
     * depend on Redis transaction behavior.</p>
     *
     * @param items a map of key-value pairs to store in Redis
     *              (must not be null, empty maps are allowed but result in no-op)
     * @throws NullPointerException if items is null
     * @see #put(String, Object)
     */
    public void putAll(Map<String, Object> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        try {
            redisTemplate.executePipelined(
                    new SessionCallback<Object>() {
                        @Override
                        public <K, V> Object execute(RedisOperations<K, V> operations)
                                throws DataAccessException {
                            items.forEach(
                                    (key, value) -> {
                                        operations
                                                .opsForValue()
                                                .set((K) key, (V) value, ttl);
                                    });
                            return null;
                        }
                    });
        } catch (Exception e) {
            log.error("Error saving multiple items to Redis", e);
        }
    }

    @Override
    /**
     * Removes multiple keys from Redis and publishes batch invalidation events.
     *
     * <p>This method performs efficient batch eviction using Redis DEL command and
     * publishes individual invalidation events for distributed cache consistency.
     * The combination ensures both local Redis cleanup and proper propagation to
     * other application instances.</p>
     *
     * <p><strong>Operation Sequence:</strong></p>
     * <ol>
     *   <li>Batch delete all keys from Redis using DEL command</li>
     *   <li>Publish individual invalidation events for each key</li>
     * </ol>
     *
     * <p><strong>Performance Benefits:</strong></p>
     * <ul>
     *   <li>Single DEL command for multiple key removal</li>
     *   <li>Efficient batch processing in Redis</li>
     *   <li>Minimal network overhead for the deletion operation</li>
     * </ul>
     *
     * <p><strong>Event Publishing:</strong> Individual invalidation events are published
     * for each key to ensure proper cache invalidation propagation to other application
     * instances listening on the invalidation topic.</p>
     *
     * @param keys the set of cache keys to remove from Redis
     *             (must not be null, empty sets are allowed but result in no-op)
     * @throws NullPointerException if keys is null
     * @see #evict(String)
     */
    public void evictAll(Set<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return;
        }
        log.debug("Removing keys from Redis and publishing invalidations: {}", keys);
        try {
            redisTemplate.delete(keys);
        } catch (Exception e) {
            log.error("Error deleting multiple keys from Redis: {}", keys, e);
        }

        keys.forEach(
                key -> {
                    try {
                        redisTemplate.convertAndSend(invalidationTopic, key);
                    } catch (Exception e) {
                        log.error("Error publishing invalidation message for key: {}", key, e);
                    }
                });
    }
}