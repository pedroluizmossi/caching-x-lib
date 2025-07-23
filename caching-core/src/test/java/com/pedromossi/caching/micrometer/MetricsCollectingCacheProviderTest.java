package com.pedromossi.caching.micrometer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.pedromossi.caching.CacheProvider;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.ParameterizedTypeReference;

@ExtendWith(MockitoExtension.class)
@DisplayName("MetricsCollectingCacheProvider")
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
        meterRegistry = new SimpleMeterRegistry();
        metricsProvider = new MetricsCollectingCacheProvider(delegate, meterRegistry, CACHE_LEVEL);
    }

    @Nested
    @DisplayName("for get()")
    class GetTests {
        private final String key = "user:1";
        private final ParameterizedTypeReference<String> typeRef = new ParameterizedTypeReference<>() {};

        @Test
        @DisplayName("should record hit and latency metrics on success")
        void shouldRecordHitAndLatencyMetricsOnSuccess() {
            when(delegate.get(key, typeRef)).thenReturn("John Doe");
            String result = metricsProvider.get(key, typeRef);
            assertThat(result).isEqualTo("John Doe");
            assertOperationsCounterIncremented("hit", 1.0);
            assertTimerRecorded("get", "user", 1);
        }

        @Test
        @DisplayName("should record miss and latency metrics on success")
        void shouldRecordMissAndLatencyMetricsOnSuccess() {
            when(delegate.get(key, typeRef)).thenReturn(null);
            String result = metricsProvider.get(key, typeRef);
            assertThat(result).isNull();
            assertOperationsCounterIncremented("miss", 1.0);
            assertTimerRecorded("get", "user", 1);
        }

        @Test
        @DisplayName("should record error and latency metrics on failure")
        void shouldRecordErrorAndLatencyMetricsOnFailure() {
            var exception = new RuntimeException("Cache unavailable");
            doThrow(exception).when(delegate).get(key, typeRef);
            assertThatThrownBy(() -> metricsProvider.get(key, typeRef)).isSameAs(exception);
            assertErrorCounterIncremented("get", "user", "RuntimeException");
            assertTimerRecorded("get", "user", 1);
        }
    }

    @Nested
    @DisplayName("for put()")
    class PutTests {
        @Test
        @DisplayName("should record latency on success")
        void shouldRecordLatencyOnSuccess() {
            metricsProvider.put("product:123", "Laptop");
            verify(delegate).put("product:123", "Laptop");
            assertTimerRecorded("put", "product", 1);
        }

        @Test
        @DisplayName("should record error and latency on failure")
        void shouldRecordErrorAndLatencyOnFailure() {
            var exception = new RuntimeException("Cache unavailable");
            doThrow(exception).when(delegate).put(anyString(), any());
            assertThatThrownBy(() -> metricsProvider.put("product:123", "Laptop")).isSameAs(exception);
            assertErrorCounterIncremented("put", "product", "RuntimeException");
            assertTimerRecorded("put", "product", 1);
        }
    }

    @Nested
    @DisplayName("for evict()")
    class EvictTests {
        @Test
        @DisplayName("should record latency on success")
        void shouldRecordLatencyOnSuccess() {
            metricsProvider.evict("session:xyz");
            verify(delegate).evict("session:xyz");
            assertTimerRecorded("evict", "session", 1);
        }
    }

    @Nested
    @DisplayName("for getAll()")
    class GetAllTests {
    
    private Set<String> keys;
    private ParameterizedTypeReference<String> typeRef;
    
    @BeforeEach
    void setUp() {
        // Reset the meter registry before each test to ensure isolation
        meterRegistry = new SimpleMeterRegistry();
        metricsProvider = new MetricsCollectingCacheProvider(delegate, meterRegistry, CACHE_LEVEL);
        
        keys = Set.of("item:1", "item:2", "item:3");
        typeRef = new ParameterizedTypeReference<String>() {};
    }
    
    @Test
    @DisplayName("should record hit/miss and latency metrics")
    void shouldRecordMetrics() {
        // Given
        Map<String, String> foundItems = Map.of("item:1", "A", "item:3", "C");
        
        // Use ArgumentCaptor to verify exact parameters
        ArgumentCaptor<Set<String>> keysCaptor = ArgumentCaptor.forClass(Set.class);
        ArgumentCaptor<ParameterizedTypeReference<String>> typeRefCaptor = 
                ArgumentCaptor.forClass(ParameterizedTypeReference.class);
        
        when(delegate.getAll(keysCaptor.capture(), typeRefCaptor.capture())).thenReturn(foundItems);
        
        // When
        Map<String, String> result = metricsProvider.getAll(keys, typeRef);
        
        // Then
        assertThat(result).isEqualTo(foundItems);
        
        // Verify captured arguments
        assertThat(keysCaptor.getValue()).isEqualTo(keys);
        assertThat(typeRefCaptor.getValue()).isEqualTo(typeRef);
        
        // Verify metrics based on calculation instead of hard-coded values
        int hits = foundItems.size();
        int misses = keys.size() - hits;
        
        assertOperationsCounterIncremented("hit", (double) hits);
        assertOperationsCounterIncremented("miss", (double) misses);
        assertTimerRecorded("get_all", BATCH_KEY_PREFIX, 1);
    }
    
    @Test
    @DisplayName("should record only hits when all keys are found")
    void shouldRecordOnlyHitsWhenAllKeysFound() {
        // Given - all keys found (100% hit rate)
        Map<String, String> allFound = Map.of("item:1", "A", "item:2", "B", "item:3", "C");
        when(delegate.getAll(keys, typeRef)).thenReturn(allFound);
        
        // When
        Map<String, String> result = metricsProvider.getAll(keys, typeRef);
        
        // Then
        assertThat(result).isEqualTo(allFound);
        assertOperationsCounterIncremented("hit", (double) keys.size());
        assertTimerRecorded("get_all", BATCH_KEY_PREFIX, 1);
    }
    
    @Test
    @DisplayName("should record only misses when no keys are found")
    void shouldRecordOnlyMissesWhenNoKeysFound() {
        // Given - no keys found (100% miss rate)
        Map<String, String> emptyResult = Map.of();
        when(delegate.getAll(keys, typeRef)).thenReturn(emptyResult);
        
        // When
        Map<String, String> result = metricsProvider.getAll(keys, typeRef);
        
        // Then
        assertThat(result).isEmpty();
        assertOperationsCounterIncremented("miss", (double) keys.size());
        assertTimerRecorded("get_all", BATCH_KEY_PREFIX, 1);
    }
    
    @Test
    @DisplayName("should handle empty key set gracefully")
    void shouldHandleEmptyKeySetGracefully() {
        // Given
        Set<String> emptyKeys = Set.of();
        Map<String, String> emptyResult = Map.of();
        
        when(delegate.getAll(emptyKeys, typeRef)).thenReturn(emptyResult);
        
        // When
        Map<String, String> result = metricsProvider.getAll(emptyKeys, typeRef);
        
        // Then
        assertThat(result).isEmpty();
        
        // Verify no metrics were recorded since there were no hits or misses
        assertThat(meterRegistry.find(METRIC_OPERATIONS).tag("result", "hit").counter()).isNull();
        assertThat(meterRegistry.find(METRIC_OPERATIONS).tag("result", "miss").counter()).isNull();
        
        // But timer should still be recorded
        assertTimerRecorded("get_all", BATCH_KEY_PREFIX, 1);
    }
    
    @Test
    @DisplayName("should record error metrics when delegate throws exception")
    void shouldRecordErrorMetricsOnException() {
        // Given
        RuntimeException exception = new RuntimeException("Cache unavailable");
        when(delegate.getAll(keys, typeRef)).thenThrow(exception);
        
        // When/Then
        assertThatThrownBy(() -> metricsProvider.getAll(keys, typeRef))
                .isSameAs(exception);
        
        // Verify error metrics
        assertErrorCounterIncremented("get_all", BATCH_KEY_PREFIX, "RuntimeException");
        assertTimerRecorded("get_all", BATCH_KEY_PREFIX, 1);
    }
    
    @Test
    @DisplayName("should correctly pass all tags to metrics")
    void shouldPassAllTagsToMetrics() {
        // Given
        Map<String, String> foundItems = Map.of("item:1", "A");
        when(delegate.getAll(keys, typeRef)).thenReturn(foundItems);
        
        // When
        metricsProvider.getAll(keys, typeRef);
        
        // Then - verify all tags are present
        Timer timer = meterRegistry.get(METRIC_LATENCY).timer();
        assertThat(timer.getId().getTags())
                .containsExactlyInAnyOrder(
                        Tag.of("cache.level", CACHE_LEVEL),
                        Tag.of("operation", "get_all"),
                        Tag.of("key.prefix", BATCH_KEY_PREFIX));
        
        Counter hitCounter = meterRegistry.get(METRIC_OPERATIONS).tag("result", "hit").counter();
        assertThat(hitCounter.getId().getTags())
                .containsExactlyInAnyOrder(
                        Tag.of("cache.level", CACHE_LEVEL),
                        Tag.of("result", "hit"));
    }
}

    @Nested
    @DisplayName("for putAll()")
    class PutAllTests {
        @Test
        @DisplayName("should record latency on success")
        void shouldRecordLatencyOnSuccess() {
            Map<String, Object> items = Map.of("item:1", "A");
            metricsProvider.putAll(items);
            verify(delegate).putAll(items);
            assertTimerRecorded("put_all", BATCH_KEY_PREFIX, 1);
        }
    }

    @Nested
    @DisplayName("for evictAll()")
    class EvictAllTests {
        @Test
        @DisplayName("should record latency on success")
        void shouldRecordLatencyOnSuccess() {
            Set<String> keys = Set.of("item:1");
            metricsProvider.evictAll(keys);
            verify(delegate).evictAll(keys);
            assertTimerRecorded("evict_all", BATCH_KEY_PREFIX, 1);
        }

        @Test
        @DisplayName("should record error and latency metrics on failure")
        void shouldRecordErrorAndLatencyMetricsOnFailure() {
            Set<String> keys = Set.of("item:1");
            var exception = new IllegalStateException("Batch evict failed");
            doThrow(exception).when(delegate).evictAll(keys);

            assertThatThrownBy(() -> metricsProvider.evictAll(keys)).isSameAs(exception);

            assertErrorCounterIncremented("evict_all", BATCH_KEY_PREFIX, "IllegalStateException");
            assertTimerRecorded("evict_all", BATCH_KEY_PREFIX, 1);
        }
    }

    // --- Helper Methods for Assertions ---

    private void assertTimerRecorded(String operation, String keyPrefix, long expectedCount) {
        Timer timer =
                meterRegistry
                        .get(METRIC_LATENCY)
                        .tag("cache.level", CACHE_LEVEL)
                        .tag("operation", operation)
                        .tag("key.prefix", keyPrefix)
                        .timer();
        assertThat(timer.count()).isEqualTo(expectedCount);
    }

    private void assertOperationsCounterIncremented(String result, double expectedCount) {
        Counter counter =
                meterRegistry
                        .get(METRIC_OPERATIONS)
                        .tag("cache.level", CACHE_LEVEL)
                        .tag("result", result)
                        .counter();
        assertThat(counter.count()).isEqualTo(expectedCount);
    }

    private void assertErrorCounterIncremented(String operation, String keyPrefix, String exceptionType) {
        Counter counter =
                meterRegistry
                        .get(METRIC_ERRORS)
                        .tag("cache.level", CACHE_LEVEL)
                        .tag("operation", operation)
                        .tag("key.prefix", keyPrefix)
                        .tag("exception.type", exceptionType)
                        .counter();
        assertThat(counter.count()).isEqualTo(1.0);
    }
}