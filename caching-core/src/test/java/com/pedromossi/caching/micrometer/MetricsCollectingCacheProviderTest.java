package com.pedromossi.caching.micrometer;

import com.pedromossi.caching.CacheProvider;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.ParameterizedTypeReference;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MetricsCollectingCacheProviderTest {

    private static final String CACHE_LEVEL = "L2";
    private static final String METRIC_LATENCY = "cache.latency";
    private static final String METRIC_OPERATIONS = "cache.operations.total";
    private static final String METRIC_ERRORS = "cache.errors.total";
    private static final String BATCH_KEY_PREFIX = "batch";

    @Mock private CacheProvider delegate;

    private MeterRegistry meterRegistry;
    private MetricsCollectingCacheProvider metricsProvider;

    @BeforeEach
    void setUp() {
        // Use SimpleMeterRegistry for testing, as it holds metrics in memory
        meterRegistry = new SimpleMeterRegistry();
        metricsProvider = new MetricsCollectingCacheProvider(delegate, meterRegistry, CACHE_LEVEL);
    }

    @Test
    @DisplayName("get should record hit metric when value is found")
    void get_shouldRecordHitMetric_whenValueFound() {
        // Given
        String key = "user:1";
        String value = "John Doe";
        var typeRef = new ParameterizedTypeReference<String>() {};
        when(delegate.get(key, typeRef)).thenReturn(value);

        // When
        String result = metricsProvider.get(key, typeRef);

        // Then
        assertThat(result).isEqualTo(value);
        verify(delegate).get(key, typeRef);

        assertThat(
                meterRegistry
                        .get(METRIC_OPERATIONS)
                        .tag("cache.level", CACHE_LEVEL)
                        .tag("result", "hit")
                        .counter()
                        .count())
                .isEqualTo(1.0);
        assertThat(
                meterRegistry
                        .get(METRIC_LATENCY)
                        .tag("operation", "get")
                        .tag("key.prefix", "user")
                        .timer()
                        .count())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("get should record miss metric when value is not found")
    void get_shouldRecordMissMetric_whenValueNotFound() {
        // Given
        String key = "user:2";
        var typeRef = new ParameterizedTypeReference<String>() {};
        when(delegate.get(key, typeRef)).thenReturn(null);

        // When
        String result = metricsProvider.get(key, typeRef);

        // Then
        assertThat(result).isNull();
        assertThat(
                meterRegistry
                        .get(METRIC_OPERATIONS)
                        .tag("result", "miss")
                        .counter()
                        .count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("get should record failure metric when delegate throws exception")
    void get_shouldRecordFailureMetric_whenDelegateFails() {
        // Given
        String key = "user:fail";
        var typeRef = new ParameterizedTypeReference<String>() {};
        var exception = new RuntimeException("Cache unavailable");
        doThrow(exception).when(delegate).get(key, typeRef);

        // When / Then
        assertThatThrownBy(() -> metricsProvider.get(key, typeRef)).isSameAs(exception);

        assertThat(
                meterRegistry
                        .get(METRIC_ERRORS)
                        .tag("operation", "get")
                        .tag("key.prefix", "user")
                        .tag("exception.type", "RuntimeException")
                        .counter()
                        .count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("put should record latency on success")
    void put_shouldRecordLatencyOnSuccess() {
        // Given
        String key = "product:123";
        String value = "Laptop";

        // When
        metricsProvider.put(key, value);

        // Then
        verify(delegate).put(key, value);
        assertThat(
                meterRegistry
                        .get(METRIC_LATENCY)
                        .tag("operation", "put")
                        .tag("key.prefix", "product")
                        .timer()
                        .count())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("evict should record latency on success")
    void evict_shouldRecordLatencyOnSuccess() {
        // Given
        String key = "session:xyz";

        // When
        metricsProvider.evict(key);

        // Then
        verify(delegate).evict(key);
        assertThat(
                meterRegistry
                        .get(METRIC_LATENCY)
                        .tag("operation", "evict")
                        .tag("key.prefix", "session")
                        .timer()
                        .count())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("getAll should record hit and miss counts correctly")
    void getAll_shouldRecordHitAndMissCounts() {
        // Given
        Set<String> keys = Set.of("item:1", "item:2", "item:3");
        Map<String, String> foundItems = Map.of("item:1", "A", "item:3", "C");
        var typeRef = new ParameterizedTypeReference<String>() {};
        when(delegate.getAll(keys, typeRef)).thenReturn(foundItems);

        // When
        Map<String, String> result = metricsProvider.getAll(keys, typeRef);

        // Then
        assertThat(result).isEqualTo(foundItems);
        assertThat(
                meterRegistry
                        .get(METRIC_OPERATIONS)
                        .tag("result", "hit")
                        .counter()
                        .count())
                .isEqualTo(2.0);
        assertThat(
                meterRegistry
                        .get(METRIC_OPERATIONS)
                        .tag("result", "miss")
                        .counter()
                        .count())
                .isEqualTo(1.0);
        assertThat(
                meterRegistry
                        .get(METRIC_LATENCY)
                        .tag("operation", "get_all")
                        .tag("key.prefix", BATCH_KEY_PREFIX)
                        .timer()
                        .count())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("putAll should record latency on success")
    void putAll_shouldRecordLatencyOnSuccess() {
        // Given
        Map<String, Object> items = Map.of("item:1", "A", "item:2", "B");

        // When
        metricsProvider.putAll(items);

        // Then
        verify(delegate).putAll(items);
        assertThat(
                meterRegistry
                        .get(METRIC_LATENCY)
                        .tag("operation", "put_all")
                        .tag("key.prefix", BATCH_KEY_PREFIX)
                        .timer()
                        .count())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("evictAll should record latency on success")
    void evictAll_shouldRecordLatencyOnSuccess() {
        // Given
        Set<String> keys = Set.of("item:1", "item:2");

        // When
        metricsProvider.evictAll(keys);

        // Then
        verify(delegate).evictAll(keys);
        assertThat(
                meterRegistry
                        .get(METRIC_LATENCY)
                        .tag("operation", "evict_all")
                        .tag("key.prefix", BATCH_KEY_PREFIX)
                        .timer()
                        .count())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("evictAll should record failure metric when delegate fails")
    void evictAll_shouldRecordFailure_whenDelegateFails() {
        // Given
        Set<String> keys = Set.of("item:1", "item:2");
        var exception = new IllegalStateException("Batch evict failed");
        doThrow(exception).when(delegate).evictAll(keys);

        // When / Then
        assertThatThrownBy(() -> metricsProvider.evictAll(keys)).isSameAs(exception);

        assertThat(
                meterRegistry
                        .get(METRIC_ERRORS)
                        .tag("operation", "evict_all")
                        .tag("key.prefix", BATCH_KEY_PREFIX)
                        .tag("exception.type", "IllegalStateException")
                        .counter()
                        .count())
                .isEqualTo(1.0);
    }
}