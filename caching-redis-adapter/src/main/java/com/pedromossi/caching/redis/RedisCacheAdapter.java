package com.pedromossi.caching.redis;

import com.pedromossi.caching.CacheProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import java.time.Duration;

/**
 * Redis cache adapter implementation for L2 (distributed) caching.
 * Provides distributed caching capabilities with automatic invalidation across application instances.
 *
 * @since 1.0.0
 */
public class RedisCacheAdapter implements CacheProvider {

    private static final Logger log = LoggerFactory.getLogger(RedisCacheAdapter.class);

    private final RedisTemplate<String, Object> redisTemplate;
    private final String invalidationTopic;
    private final Duration ttl;

    /**
     * Creates a new RedisCacheAdapter with the specified configuration.
     *
     * @param redisTemplate     Redis template for cache operations
     * @param invalidationTopic Redis pub/sub topic for cache invalidation events
     * @param ttl                Time-to-live for cached entries
     */
    public RedisCacheAdapter(RedisTemplate<String, Object> redisTemplate, String invalidationTopic, Duration ttl) {
        this.redisTemplate = redisTemplate;
        this.invalidationTopic = invalidationTopic;
        this.ttl = ttl;
        log.info("RedisCacheAdapter initialized. Invalidation topic: {}, TTL: {}", invalidationTopic, ttl);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(String key, Class<T> type) {
        try {
            Object value = redisTemplate.opsForValue().get(key);
            if (type.isInstance(value)) {
                return (T) value;
            }
        } catch (Exception e) {
            log.error("Error retrieving from Redis for key: {}", key, e);
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
        try {
            log.debug("Removing key from Redis and publishing invalidation: {}", key);
            redisTemplate.delete(key);
            redisTemplate.convertAndSend(invalidationTopic, key);
        } catch (Exception e) {
            log.error("Error invalidating in Redis for key: {}", key, e);
        }
    }
}
