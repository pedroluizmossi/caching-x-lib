package com.pedromossi.caching.impl;

import com.pedromossi.caching.CacheProvider;
import com.pedromossi.caching.CacheService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Multi-level cache service implementation.
 * Manages L1 (local) and L2 (distributed) cache layers with automatic promotion and fallback.
 *
 * @since 1.0.0
 */
public class MultiLevelCacheService implements CacheService {
    private static final Logger log = LoggerFactory.getLogger(MultiLevelCacheService.class);
    private final CacheProvider l1Cache;
    private final CacheProvider l2Cache;

    /**
     * Creates a new MultiLevelCacheService with optional L1 and L2 cache providers.
     *
     * @param l1Cache Optional L1 (local) cache provider
     * @param l2Cache Optional L2 (distributed) cache provider
     */
    public MultiLevelCacheService(Optional<CacheProvider> l1Cache, Optional<CacheProvider> l2Cache) {
        this.l1Cache = l1Cache.orElse(null);
        this.l2Cache = l2Cache.orElse(null);
        log.info("MultiLevelCacheService initialized. L1 Active: {}, L2 Active: {}", this.l1Cache != null, this.l2Cache != null);
    }

    @Override
    public <T> T getOrLoad(String key, Class<T> type, Supplier<T> loader) {
        // Try L1 cache first (fastest)
        if (this.l1Cache != null) {
            T value = (T)this.l1Cache.get(key, type);
            if (value != null) {
                log.debug("Cache HIT on L1 for key: {}", key);
                return value;
            }
        }

        // Try L2 cache second
        if (this.l2Cache != null) {
            T value = (T)this.l2Cache.get(key, type);
            if (value != null) {
                log.debug("Cache HIT on L2 for key: {}", key);
                // Promote to L1 cache
                if (this.l1Cache != null) {
                    try {
                        this.l1Cache.put(key, value);
                    } catch (Exception e) {
                        log.error("Error promoting value from L2 to L1: {}", key, e);
                    }
                }
                return value;
            }
        }

        // Cache miss - load from source
        log.debug("Complete cache MISS for key: {}. Executing loader.", key);
        T valueFromSource = (T)loader.get();
        if (valueFromSource != null) {
            // Store in L2 cache first
            if (this.l2Cache != null) {
                try {
                    this.l2Cache.put(key, valueFromSource);
                } catch (Exception e) {
                    log.error("Error saving value to L2 for key {}: {}", key, e.getMessage(), e);
                }
            }
            // Store in L1 cache
            if (this.l1Cache != null) {
                try {
                    this.l1Cache.put(key, valueFromSource);
                } catch (Exception e) {
                    log.error("Error saving value to L1 for key {}: {}", key, e.getMessage(), e);
                }
            }
        }

        return valueFromSource;
    }

    @Override
    public void invalidate(String key) {
        log.debug("Invalidating key: {}", key);
        if (this.l2Cache != null) {
            this.l2Cache.evict(key);
        } else if (this.l1Cache != null) {
            this.l1Cache.evict(key);
        }
    }
}
