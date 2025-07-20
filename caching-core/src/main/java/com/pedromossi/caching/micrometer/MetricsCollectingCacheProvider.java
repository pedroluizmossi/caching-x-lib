package com.pedromossi.caching.micrometer;

import com.pedromossi.caching.CacheProvider;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import org.springframework.core.ParameterizedTypeReference;

/**
 * A CacheProvider decorator that collects granular metrics for each operation, including successes,
 * failures, and latencies. It provides deep visibility into the cache's performance and health.
 */
public class MetricsCollectingCacheProvider implements CacheProvider {

    private static final String METRIC_LATENCY = "cache.latency";
    private static final String METRIC_OPERATIONS = "cache.operations.total";
    private static final String METRIC_ERRORS = "cache.errors.total";
    private static final String METRIC_PAYLOAD_SIZE = "cache.payload.size.bytes";

    private final CacheProvider delegate;
    private final MeterRegistry meterRegistry;
    private final Tags commonTags;

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
                            .tags("operation", "put", "key.prefix", extractKeyPrefix(key))
                            .publishPercentiles(0.5, 0.95, 0.99)
                            .register(meterRegistry));
        } catch (Exception e) {
            recordFailure(sample, "put", key, e);
            throw e;
        }
    }

    @Override
    public void evict(String key) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            delegate.evict(key);
            sample.stop(
                    Timer.builder(METRIC_LATENCY)
                            .tags(commonTags)
                            .tags("operation", "evict", "key.prefix", extractKeyPrefix(key))
                            .publishPercentiles(0.5, 0.95, 0.99)
                            .register(meterRegistry));
        } catch (Exception e) {
            recordFailure(sample, "evict", key, e);
            throw e;
        }
    }

    /**
     * Centralized method to record a failed cache operation. It stops the latency timer and
     * increments the error counter.
     *
     * @param sample The Timer.Sample started at the beginning of the operation.
     * @param operation The name of the operation (e.g., "get", "put").
     * @param key The cache key involved in the operation.
     * @param e The exception that was thrown.
     */
    private void recordFailure(Timer.Sample sample, String operation, String key, Exception e) {
        sample.stop(
                Timer.builder(METRIC_LATENCY)
                        .tags(commonTags)
                        .tags("operation", operation, "key.prefix", extractKeyPrefix(key))
                        .publishPercentiles(0.5, 0.95, 0.99)
                        .register(meterRegistry));

        Counter.builder(METRIC_ERRORS)
                .tags(commonTags)
                .tags(
                        "operation",
                        operation,
                        "key.prefix",
                        extractKeyPrefix(key),
                        "exception.type",
                        e.getClass().getSimpleName())
                .register(meterRegistry)
                .increment();
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