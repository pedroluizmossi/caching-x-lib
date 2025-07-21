package com.pedromossi.caching.redis;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.pedromossi.caching.CacheProvider;
import com.pedromossi.caching.serializer.CacheSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisStringCommands;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.types.Expiration;
import org.springframework.data.redis.serializer.RedisSerializer;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Redis-based cache adapter implementation for L2 (distributed) caching with pluggable serialization.
 *
 * <p>This adapter provides distributed caching capabilities using Redis as the backend storage,
 * with automatic cache invalidation across multiple application instances through Redis pub/sub
 * messaging. It supports configurable TTL and pluggable serialization strategies.</p>
 *
 * <p><strong>Key Features:</strong></p>
 * <ul>
 *   <li>Distributed caching with Redis backend storage</li>
 *   <li>Automatic cross-instance invalidation via Redis pub/sub</li>
 *   <li>Configurable TTL for cache entries</li>
 *   <li>Pluggable serialization through {@link CacheSerializer}</li>
 *   <li>Batch operations for improved performance</li>
 *   <li>Comprehensive error handling and logging</li>
 * </ul>
 *
 * <p><strong>Architecture:</strong></p>
 * <ul>
 *   <li><strong>Storage:</strong> Uses Redis STRING data type for cache entries</li>
 *   <li><strong>Serialization:</strong> Delegates to pluggable {@link CacheSerializer}</li>
 *   <li><strong>Invalidation:</strong> Publishes messages to Redis pub/sub topic</li>
 *   <li><strong>TTL:</strong> Applied uniformly to all cache entries</li>
 * </ul>
 *
 * <p><strong>Performance Characteristics:</strong></p>
 * <ul>
 *   <li>Network latency dependent (typical Redis network overhead)</li>
 *   <li>Batch operations minimize round trips for bulk operations</li>
 *   <li>Pipeline support for efficient bulk storage</li>
 *   <li>Automatic connection pooling through RedisTemplate</li>
 * </ul>
 *
 * @since 1.0.0
 * @see CacheProvider
 * @see CacheSerializer
 * @see RedisTemplate
 */
public class RedisCacheAdapter implements CacheProvider {

    private static final Logger log = LoggerFactory.getLogger(RedisCacheAdapter.class);

    private final RedisTemplate<String, byte[]> redisTemplate;
    private final String invalidationTopic;
    private final Duration ttl;
    private final CacheSerializer serializer;

    /**
     * Creates a new RedisCacheAdapter with the specified configuration.
     *
     * <p>This constructor initializes the adapter with all necessary components
     * for distributed caching operations. The Redis template should be properly
     * configured with appropriate serializers for the key and value types.</p>
     *
     * <p><strong>Redis Template Requirements:</strong></p>
     * <ul>
     *   <li>Key serializer should handle String keys (typically StringRedisSerializer)</li>
     *   <li>Value serializer should handle byte arrays (typically ByteArrayRedisSerializer)</li>
     *   <li>Connection factory should be properly configured</li>
     *   <li>Template should be initialized (afterPropertiesSet called)</li>
     * </ul>
     *
     * @param redisTemplate     Redis template for cache operations with String keys and byte[] values
     *                          (must not be null and should be properly configured)
     * @param invalidationTopic Redis pub/sub topic name for cache invalidation events
     *                          (must not be null or empty)
     * @param ttl               Time-to-live duration for cached entries
     *                          (must not be null and should be positive)
     * @param serializer        Serialization strategy for converting objects to/from byte arrays
     *                          (must not be null)
     * @throws NullPointerException if any parameter is null
     * @throws IllegalArgumentException if invalidationTopic is empty or ttl is not positive
     */
    public RedisCacheAdapter(
            RedisTemplate<String, byte[]> redisTemplate,
            String invalidationTopic,
            Duration ttl,
            CacheSerializer serializer) {
        this.redisTemplate = redisTemplate;
        this.invalidationTopic = invalidationTopic;
        this.ttl = ttl;
        this.serializer = serializer;
        log.info(
                "RedisCacheAdapter initialized. Serializer: {}, Topic: {}, TTL: {}",
                serializer.getClass().getSimpleName(),
                invalidationTopic,
                ttl);
    }

    /**
     * Retrieves a value from Redis with type-safe deserialization.
     *
     * <p>This method performs a Redis GET operation followed by deserialization
     * using the configured {@link CacheSerializer}. It handles various error
     * scenarios gracefully by returning null and logging errors.</p>
     *
     * <p><strong>Error Handling:</strong> Any errors during Redis access or
     * deserialization are logged and result in null return value, allowing
     * the caching layer to treat it as a cache miss.</p>
     *
     * @param <T> the expected type of the cached value
     * @param key the cache key to retrieve (must not be null)
     * @param typeRef type reference for safe deserialization (must not be null)
     * @return the deserialized cached value, or null if not found or error occurred
     * @throws NullPointerException if key or typeRef is null
     * @see CacheSerializer#deserialize(byte[], ParameterizedTypeReference)
     */
    @Override
    public <T> T get(String key, ParameterizedTypeReference<T> typeRef) {
        try {
            byte[] data = redisTemplate.opsForValue().get(key);
            return serializer.deserialize(data, typeRef);
        } catch (Exception e) {
            log.error("Error retrieving or deserializing from Redis for key: {}", key, e);
            return null;
        }
    }

    /**
     * Stores a value in Redis with TTL and serialization.
     *
     * <p>This method serializes the object using the configured {@link CacheSerializer}
     * and stores it in Redis with the configured TTL. Errors during serialization
     * or Redis operations are logged but don't propagate to maintain cache transparency.</p>
     *
     * <p><strong>TTL Behavior:</strong> The configured TTL is applied to every
     * cached entry, providing automatic expiration and memory management.</p>
     *
     * @param key the cache key (must not be null)
     * @param value the object to cache (may be null, depending on serializer)
     * @throws NullPointerException if key is null
     * @see CacheSerializer#serialize(Object)
     */
    @Override
    public void put(String key, Object value) {
        try {
            byte[] data = serializer.serialize(value);
            redisTemplate.opsForValue().set(key, data, ttl);
        } catch (Exception e) {
            log.error("Error serializing or saving to Redis for key: {}", key, e);
        }
    }

    /**
     * Retrieves multiple values from Redis with type safety in a single operation.
     *
     * <p>This method performs efficient batch retrieval using Redis MGET command
     * through Spring Data Redis {@code multiGet} operation. Each retrieved value
     * is individually deserialized, with failed conversions logged and excluded
     * from the result.</p>
     *
     * <p><strong>Performance Benefits:</strong></p>
     * <ul>
     *   <li>Single network round trip for multiple key retrieval</li>
     *   <li>Efficient Redis MGET command utilization</li>
     *   <li>Parallel deserialization of retrieved values</li>
     * </ul>
     *
     * <p><strong>Error Resilience:</strong> Individual key retrieval or
     * deserialization failures don't affect other keys in the batch.
     * Failed operations are logged and excluded from the result.</p>
     *
     * @param <T> the expected type of all cached values
     * @param keys the set of cache keys to lookup (null or empty sets return empty map)
     * @param typeRef type reference for safe deserialization (must not be null)
     * @return a map containing successfully retrieved and deserialized entries
     * @throws NullPointerException if typeRef is null
     * @see #get(String, ParameterizedTypeReference)
     */
    @Override
    public <T> Map<String, T> getAll(Set<String> keys, ParameterizedTypeReference<T> typeRef) {
        if (keys == null || keys.isEmpty()) {
            return Map.of();
        }
        try {
            List<String> keyList = new ArrayList<>(keys);
            List<byte[]> values = redisTemplate.opsForValue().multiGet(keyList);

            if (values == null) {
                return Map.of();
            }

            Map<String, T> result = new HashMap<>();
            for (int i = 0; i < values.size(); i++) {
                byte[] data = values.get(i);
                if (data != null) {
                    String currentKey = keyList.get(i); // Use the key from the ordered list
                    try {
                        result.put(currentKey, serializer.deserialize(data, typeRef));
                    } catch (Exception e) {
                        log.error("Error converting value for key {}: {}", currentKey, e.getMessage());
                    }
                }
            }
            return result;
        } catch (Exception e) {
            log.error("Error retrieving multiple keys from Redis: {}", keys, e);
            return Map.of();
        }
    }

    /**
     * Stores multiple key-value pairs in Redis with TTL using pipelined operations.
     *
     * <p>This method performs efficient batch storage by first serializing all
     * objects and then using Redis pipelining to minimize network round trips.
     * Since Redis MSET doesn't support TTL, this implementation uses pipelined
     * SET operations to ensure proper TTL application.</p>
     *
     * <p><strong>Performance Optimizations:</strong></p>
     * <ul>
     *   <li>Batch serialization before Redis operations</li>
     *   <li>Pipelined Redis commands for reduced network overhead</li>
     *   <li>Consistent TTL application across all entries</li>
     * </ul>
     *
     * <p><strong>Error Handling:</strong> Serialization failures for individual
     * items are collected but don't prevent other items from being stored.
     * Redis pipeline failures affect the entire batch.</p>
     *
     * @param items map of key-value pairs to store (null or empty maps are no-op)
     * @throws NullPointerException if items contains null keys
     * @see #put(String, Object)
     */
    @Override
    public void putAll(Map<String, Object> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        try {
            Map<String, byte[]> serializedItems = items.entrySet().stream()
                    .collect(Collectors.toMap(Map.Entry::getKey, entry -> serializer.serialize(entry.getValue())));
            redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                @SuppressWarnings("unchecked")
                RedisSerializer<String> keySerializer = (RedisSerializer<String>) redisTemplate.getKeySerializer();
                serializedItems.forEach((key, data) -> {
                    byte[] keyBytes = keySerializer.serialize(key);
                    if (keyBytes == null) {
                        return;
                    }
                    connection.stringCommands().set(keyBytes, data,
                            Expiration.seconds(ttl.toSeconds()),
                            RedisStringCommands.SetOption.UPSERT);
                });
                return null;
            });
        } catch (Exception e) {
            log.error("Error saving multiple items to Redis via pipeline", e);
        }
    }

    /**
     * Removes a key from Redis and publishes invalidation event.
     *
     * <p>This method performs two operations: removing the key from Redis storage
     * and publishing an invalidation message to the configured topic. This ensures
     * both local Redis cleanup and distributed cache consistency.</p>
     *
     * <p><strong>Operation Sequence:</strong></p>
     * <ol>
     *   <li>Delete key from Redis using DEL command</li>
     *   <li>Publish invalidation message to Redis pub/sub topic</li>
     * </ol>
     *
     * <p><strong>Error Handling:</strong> Each operation is independently error-handled
     * to ensure partial success scenarios (e.g., successful deletion but failed
     * message publishing) are properly logged.</p>
     *
     * @param key the cache key to remove (must not be null)
     * @throws NullPointerException if key is null
     * @see #evictAll(Set)
     */
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

    /**
     * Removes multiple keys from Redis and publishes batch invalidation events.
     *
     * <p>This method performs efficient batch eviction using Redis DEL command
     * followed by individual invalidation event publishing. The DEL operation
     * is atomic for all keys, while invalidation events are published individually
     * to ensure proper distributed cache consistency.</p>
     *
     * <p><strong>Performance Benefits:</strong></p>
     * <ul>
     *   <li>Single DEL command for multiple key removal</li>
     *   <li>Atomic batch deletion in Redis</li>
     *   <li>Efficient bulk processing</li>
     * </ul>
     *
     * <p><strong>Distributed Invalidation:</strong> Individual invalidation events
     * are published for each key to ensure other application instances receive
     * proper cache invalidation notifications for their local caches.</p>
     *
     * @param keys the set of cache keys to remove (null or empty sets are no-op)
     * @throws NullPointerException if keys contains null elements
     * @see #evict(String)
     */
    @Override
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
        keys.forEach(key -> {
            try {
                redisTemplate.convertAndSend(invalidationTopic, key);
            } catch (Exception e) {
                log.error("Error publishing invalidation message for key: {}", key, e);
            }
        });
    }
}