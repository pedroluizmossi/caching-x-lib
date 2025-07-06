package com.pedromossi.caching.caffeine;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.pedromossi.caching.CacheProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Caffeine cache adapter implementation for L1 (local) caching.
 * Provides high-performance in-memory caching using the Caffeine library.
 *
 * @since 1.0.0
 */
public class CaffeineCacheAdapter implements CacheProvider {

    private static final Logger log = LoggerFactory.getLogger(CaffeineCacheAdapter.class);
    private final Cache<String, Object> cache;

    /**
     * Creates a new CaffeineCacheAdapter with the specified configuration.
     *
     * @param spec Caffeine cache specification string (e.g., "maximumSize=1000,expireAfterWrite=5m")
     */
    public CaffeineCacheAdapter(String spec) {
        this.cache = Caffeine.from(spec).build();
        log.info("CaffeineCacheAdapter initialized with specification: {}", spec);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(String key, Class<T> type) {
        Object value = cache.getIfPresent(key);
        if (type.isInstance(value)) {
            return (T) value;
        }
        return null;
    }

    @Override
    public void put(String key, Object value) {
        cache.put(key, value);
    }

    @Override
    public void evict(String key) {
        log.debug("Invalidating local L1 key: {}", key);
        cache.invalidate(key);
    }
}
