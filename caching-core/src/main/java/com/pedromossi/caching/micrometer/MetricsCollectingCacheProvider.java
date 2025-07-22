package com.pedromossi.caching.micrometer;

import com.pedromossi.caching.CacheProvider;
import io.micrometer.core.instrument.*;
import org.springframework.core.ParameterizedTypeReference;

import java.util.Map;
import java.util.Set;

/**
 * A CacheProvider decorator that collects granular metrics for each operation, including successes,
 * failures, and latencies. It provides deep visibility into the cache's performance and health.
 */
public class MetricsCollectingCacheProvider implements CacheProvider {

    private static final String METRIC_LATENCY = "cache.latency";
    private static final String METRIC_OPERATIONS = "cache.operations.total";
    private static final String METRIC_ERRORS = "cache.errors.total";
    private static final String METRIC_PAYLOAD_SIZE = "cache.payload.size.bytes";
    private static final String BATCH_KEY_PREFIX = "batch";

    private final CacheProvider delegate;
    private final MeterRegistry meterRegistry;
    private final Tags commonTags;

    /**
     * Creates a new MetricsCollectingCacheProvider with the specified delegate and metrics configuration.
     *
     * @param delegate the underlying cache provider to collect metrics from (must not be null)
     * @param meterRegistry the Micrometer registry for publishing metrics (must not be null)
     * @param cacheLevel a descriptive label for the cache level (e.g., "L1", "L2") used in metric tags
     * @throws NullPointerException if delegate, meterRegistry, or cacheLevel is null
     */
    public MetricsCollectingCacheProvider(
            CacheProvider delegate, MeterRegistry meterRegistry, String cacheLevel) {
        this.delegate = delegate;
        this.meterRegistry = meterRegistry;
        this.commonTags = Tags.of("cache.level", cacheLevel);
    }

    @Override
    public <T> T get(String key, ParameterizedTypeReference<T> typeRef) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String keyPrefix = extractKeyPrefix(key);
        try {
            T value = delegate.get(key, typeRef);

            Tag resultTag = (value != null) ? Tag.of("result", "hit") : Tag.of("result", "miss");
            Counter.builder(METRIC_OPERATIONS)
                    .tags(commonTags.and(resultTag))
                    .register(meterRegistry)
                    .increment();

            sample.stop(
                    Timer.builder(METRIC_LATENCY)
                            .tags(commonTags)
                            .tags("operation", "get", "key.prefix", keyPrefix)
                            .publishPercentiles(0.5, 0.95, 0.99)
                            .register(meterRegistry));
            return value;
        } catch (Exception e) {
            recordFailure(sample, "get", keyPrefix, e);
            throw e;
        }
    }

    @Override
    public void put(String key, Object value) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String keyPrefix = extractKeyPrefix(key);
        try {
            delegate.put(key, value);

            if (value != null) {
                if (value instanceof byte[]) {
                    meterRegistry.summary(METRIC_PAYLOAD_SIZE, commonTags).record(((byte[]) value).length);
                } else if (value instanceof String) {
                    meterRegistry.summary(METRIC_PAYLOAD_SIZE, commonTags).record(((String) value).length());
                }
            }

            sample.stop(
                    Timer.builder(METRIC_LATENCY)
                            .tags(commonTags)
                            .tags("operation", "put", "key.prefix", keyPrefix)
                            .publishPercentiles(0.5, 0.95, 0.99)
                            .register(meterRegistry));
        } catch (Exception e) {
            recordFailure(sample, "put", keyPrefix, e);
            throw e;
        }
    }

    @Override
    public void evict(String key) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String keyPrefix = extractKeyPrefix(key);
        try {
            delegate.evict(key);
            sample.stop(
                    Timer.builder(METRIC_LATENCY)
                            .tags(commonTags)
                            .tags("operation", "evict", "key.prefix", keyPrefix)
                            .publishPercentiles(0.5, 0.95, 0.99)
                            .register(meterRegistry));
        } catch (Exception e) {
            recordFailure(sample, "evict", keyPrefix, e);
            throw e;
        }
    }

    /**
     * Centralized method to record a failed cache operation. It stops the latency timer and
     * increments the error counter.
     *
     * @param sample The Timer.Sample started at the beginning of the operation.
     * @param operation The name of the operation (e.g., "get", "put").
     * @param keyPrefix The extracted prefix of the cache key involved.
     * @param e The exception that was thrown.
     */
    private void recordFailure(Timer.Sample sample, String operation, String keyPrefix, Exception e) {
        sample.stop(
                Timer.builder(METRIC_LATENCY)
                        .tags(commonTags)
                        .tags("operation", operation, "key.prefix", keyPrefix)
                        .publishPercentiles(0.5, 0.95, 0.99)
                        .register(meterRegistry));

        Counter.builder(METRIC_ERRORS)
                .tags(commonTags)
                .tags(
                        "operation",
                        operation,
                        "key.prefix",
                        keyPrefix,
                        "exception.type",
                        e.getClass().getSimpleName())
                .register(meterRegistry)
                .increment();
    }

    @Override
    public <T> Map<String, T> getAll(Set<String> keys, ParameterizedTypeReference<T> typeRef) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            Map<String, T> foundItems = delegate.getAll(keys, typeRef);

            int hits = foundItems.size();
            int misses = keys.size() - hits;

            if (hits > 0) {
                Counter.builder(METRIC_OPERATIONS)
                        .tags(commonTags.and(Tag.of("result", "hit")))
                        .register(meterRegistry)
                        .increment(hits);
            }
            if (misses > 0) {
                Counter.builder(METRIC_OPERATIONS)
                        .tags(commonTags.and(Tag.of("result", "miss")))
                        .register(meterRegistry)
                        .increment(misses);
            }

            sample.stop(
                    Timer.builder(METRIC_LATENCY)
                            .tags(commonTags)
                            .tags("operation", "get_all", "key.prefix", BATCH_KEY_PREFIX)
                            .publishPercentiles(0.5, 0.95, 0.99)
                            .register(meterRegistry));
            return foundItems;
        } catch (Exception e) {
            recordFailure(sample, "get_all", BATCH_KEY_PREFIX, e);
            throw e;
        }
    }

    @Override
    public void putAll(Map<String, Object> items) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            delegate.putAll(items);
            sample.stop(
                    Timer.builder(METRIC_LATENCY)
                            .tags(commonTags)
                            .tags("operation", "put_all", "key.prefix", BATCH_KEY_PREFIX)
                            .publishPercentiles(0.5, 0.95, 0.99)
                            .register(meterRegistry));
        } catch (Exception e) {
            recordFailure(sample, "put_all", BATCH_KEY_PREFIX, e);
            throw e;
        }
    }

    @Override
    public void evictAll(Set<String> keys) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            delegate.evictAll(keys);
            sample.stop(
                    Timer.builder(METRIC_LATENCY)
                            .tags(commonTags)
                            .tags("operation", "evict_all", "key.prefix", BATCH_KEY_PREFIX)
                            .publishPercentiles(0.5, 0.95, 0.99)
                            .register(meterRegistry));
        } catch (Exception e) {
            recordFailure(sample, "evict_all", BATCH_KEY_PREFIX, e);
            throw e;
        }
    }

    /**
     * Extracts a prefix from the cache key for metric tagging. The prefix is typically the part of
     * the key before the first colon. This helps in grouping metrics by data type (e.g., "user",
     * "product") without causing high cardinality issues.
     *
     * @param key The full cache key.
     * @return The extracted prefix, or "none" if no colon is found.
     */
    private String extractKeyPrefix(String key) {
        if (key == null) {
            return "unknown";
        }
        int firstColon = key.indexOf(':');
        return (firstColon == -1) ? "none" : key.substring(0, firstColon);
    }
}