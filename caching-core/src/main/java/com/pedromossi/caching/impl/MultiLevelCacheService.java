package com.pedromossi.caching.impl;

import com.pedromossi.caching.CacheProvider;
import com.pedromossi.caching.CacheService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;

import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * Multi-level cache service implementation.
 * Manages L1 (local) and L2 (distributed) cache layers with automatic promotion and fallback.
 * Write and invalidation operations are executed asynchronously.
 *
 * @since 1.1.0
 */
public class MultiLevelCacheService implements CacheService {
    private static final Logger log = LoggerFactory.getLogger(MultiLevelCacheService.class);
    private final CacheProvider l1Cache;
    private final CacheProvider l2Cache;
    private final ExecutorService executor;

    /**
     * Creates a new MultiLevelCacheService with optional L1/L2 providers and a custom ExecutorService.
     *
     * @param l1Cache  Optional L1 (local) cache provider
     * @param l2Cache  Optional L2 (distributed) cache provider
     * @param executor ExecutorService for asynchronous operations
     */
    public MultiLevelCacheService(Optional<CacheProvider> l1Cache, Optional<CacheProvider> l2Cache, ExecutorService executor) {
        this.l1Cache = l1Cache.orElse(null);
        this.l2Cache = l2Cache.orElse(null);
        this.executor = executor;
        log.info("MultiLevelCacheService initialized. L1 Active: {}, L2 Active: {}, Async Executor Active: {}",
                this.l1Cache != null, this.l2Cache != null, this.executor != null);
    }

    @Override
    public <T> T getOrLoad(String key, ParameterizedTypeReference<T> typeRef, Supplier<T> loader) {
        // L1 Hit
        if (l1Cache != null) {
            try {
                T value = l1Cache.get(key, typeRef);
                if (value != null) {
                    log.debug("Cache HIT on L1 for key: {}", key);
                    return value;
                }
            } catch (Exception e) {
                log.error("Error reading from L1 cache for key {}: {}", key, e.getMessage(), e);
            }
        }

        // L2 Hit
        if (l2Cache != null) {
            try {
                T value = l2Cache.get(key, typeRef);
                if (value != null) {
                    log.debug("Cache HIT on L2 for key: {}", key);
                    // Asynchronously promote to L1
                    final T valueToPromote = value;
                    if (l1Cache != null) {
                        executor.execute(() -> {
                            try {
                                l1Cache.put(key, valueToPromote);
                            } catch (Exception ex) {
                                log.error("Error promoting value to L1 for key {}: {}", key, ex.getMessage(), ex);
                            }
                        });
                    }
                    return value;
                }
            } catch (Exception e) {
                log.error("Error reading from L2 cache for key {}: {}", key, e.getMessage(), e);
            }
        }

        // Complete Cache Miss: Load from source
        log.debug("Complete cache MISS for key: {}. Executing loader.", key);
        T valueFromSource = loader.get();

        if (valueFromSource != null) {
            executor.execute(() -> storeInCaches(key, valueFromSource));
        }

        return valueFromSource;
    }

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
            if (l1Cache != null) {
                try {
                    l1Cache.evict(key);
                } catch (Exception e) {
                    log.error("Error invalidating L1 cache for key {}: {}", key, e.getMessage(), e);
                }
            }
        });
    }

    private void storeInCaches(String key, Object value) {
        // Store in L2 first (distributed)
        if (l2Cache != null) {
            try {
                l2Cache.put(key, value);
            } catch (Exception e) {
                log.error("Error saving value to L2 for key {}: {}", key, e.getMessage(), e);
            }
        }
        // Then store in L1 (local)
        if (l1Cache != null) {
            try {
                l1Cache.put(key, value);
            } catch (Exception e) {
                log.error("Error saving value to L1 for key {}: {}", key, e.getMessage(), e);
            }
        }
    }
}