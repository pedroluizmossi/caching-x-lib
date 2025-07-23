package com.pedromossi.caching.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.pedromossi.caching.CacheProvider;
import com.pedromossi.caching.CacheService;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(
        classes = CachingAutoConfiguration.class,
        properties = {
                "caching.l1.spec=maximumSize=50,expireAfterWrite=5s",
                "caching.l2.ttl=PT10S",
                "caching.l2.invalidation-topic=integration:test:topic",
                "caching.async.core-pool-size=2",
                "caching.async.max-pool-size=4",
                "caching.async.queue-capacity=100"
        })
@DisplayName("Integration Test with Custom Configuration")
class CachingCustomConfigurationIT extends IntegrationTest {

    private static final String KEY = "test-key-" + UUID.randomUUID();
    private static final String VALUE = "test-value-" + UUID.randomUUID();
    private static final ParameterizedTypeReference<String> TYPE_REF = new ParameterizedTypeReference<>() {};

    @Autowired private CacheService cacheService;
    @Autowired private CachingProperties cachingProperties;

    @MockitoBean
    @Qualifier("l1CacheProvider") private CacheProvider l1CacheProvider;
    @MockitoBean @Qualifier("l2CacheProvider") private CacheProvider l2CacheProvider;

    @BeforeEach
    void cleanupCaches() {
        reset(l1CacheProvider, l2CacheProvider);
    }

    @Nested
    @DisplayName("Properties Verification")
    class PropertiesVerification {
        @Test
        @DisplayName("should load custom properties correctly")
        void shouldLoadCustomProperties() {
            assertThat(cachingProperties.getL1().getSpec()).isEqualTo("maximumSize=50,expireAfterWrite=5s");
            assertThat(cachingProperties.getL2().getTtl()).isEqualTo(Duration.ofSeconds(10));
            assertThat(cachingProperties.getL2().getInvalidationTopic()).isEqualTo("integration:test:topic");
            assertThat(cachingProperties.getAsync().getCorePoolSize()).isEqualTo(2);
            assertThat(cachingProperties.getAsync().getMaxPoolSize()).isEqualTo(4);
            assertThat(cachingProperties.getAsync().getQueueCapacity()).isEqualTo(100);
        }
    }

    @Nested
    @DisplayName("Core Cache Flows")
    class CoreCacheFlows {
        @Test
        @DisplayName("should promote from L2 to L1 when L1 misses")
        void shouldPromoteFromL2ToL1_whenL1Misses() {
            when(l1CacheProvider.get(eq(KEY), any())).thenReturn(null);
            when(l2CacheProvider.get(eq(KEY), any())).thenReturn(VALUE);

            String result = cacheService.getOrLoad(KEY, TYPE_REF, () -> "should-not-be-called");

            assertThat(result).isEqualTo(VALUE);
            InOrder inOrder = inOrder(l1CacheProvider, l2CacheProvider);
            inOrder.verify(l1CacheProvider).get(eq(KEY), any());
            inOrder.verify(l2CacheProvider).get(eq(KEY), any());

            await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> verify(l1CacheProvider).put(KEY, VALUE));
        }

        @Test
        @DisplayName("should invalidate and reload when data changes")
        void shouldInvalidateAndReload_whenDataChanges() {
            String initialValue = "Initial-" + VALUE;
            String reloadedValue = "Reloaded-" + VALUE;
            AtomicInteger loaderCallCount = new AtomicInteger(0);
            Supplier<String> loader = () -> {
                loaderCallCount.incrementAndGet();
                return loaderCallCount.get() == 1 ? initialValue : reloadedValue;
            };

            // 1. Initial load
            cacheService.getOrLoad(KEY, TYPE_REF, loader);
            await().untilAsserted(() -> verify(l1CacheProvider).put(KEY, initialValue));

            // 2. Invalidate
            cacheService.invalidate(KEY);
            await().untilAsserted(() -> verify(l1CacheProvider).evict(KEY));

            // 3. Reload
            reset(l1CacheProvider, l2CacheProvider); // Reset mocks for clean verification
            String result = cacheService.getOrLoad(KEY, TYPE_REF, loader);

            assertThat(result).isEqualTo(reloadedValue);
            assertThat(loaderCallCount.get()).isEqualTo(2);
            await().untilAsserted(() -> verify(l1CacheProvider).put(KEY, reloadedValue));
        }
    }

    @Nested
    @DisplayName("Async and Concurrency")
    class AsyncAndConcurrency {
        @Test
        @DisplayName("should handle concurrent loads using the custom thread pool")
        void shouldHandleConcurrentLoads() {
            int operationCount = 10; // More than corePoolSize to test queuing
            List<CompletableFuture<Void>> futures =
                    IntStream.range(0, operationCount)
                            .mapToObj(
                                    i ->
                                            CompletableFuture.runAsync(
                                                    () -> {
                                                        String key = "concurrent-key-" + i;
                                                        cacheService.getOrLoad(key, TYPE_REF, () -> "value-" + i);
                                                    }))
                            .toList();

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            await()
                    .atMost(5, TimeUnit.SECONDS)
                    .untilAsserted(
                            () -> {
                                verify(l1CacheProvider, times(operationCount)).put(anyString(), anyString());
                                verify(l2CacheProvider, times(operationCount)).put(anyString(), anyString());
                            });
        }
    }

    @Nested
    @DisplayName("Resilience and Error Handling")
    class Resilience {
        @Test
        @DisplayName("should fallback to loader when cache providers fail")
        void shouldFallbackToLoader_whenCacheProvidersFail() {
            when(l1CacheProvider.get(eq(KEY), any())).thenThrow(new RuntimeException("L1 Read Error"));
            doThrow(new RuntimeException("L1 Write Error")).when(l1CacheProvider).put(anyString(), anyString());

            String result = cacheService.getOrLoad(KEY, TYPE_REF, () -> VALUE);

            assertThat(result).isEqualTo(VALUE);
            // Verify it still attempts the async write to L2 even if L1 fails
            await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> verify(l2CacheProvider).put(KEY, VALUE));
        }
    }
}