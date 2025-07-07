package com.pedromossi.caching.starter;

import com.pedromossi.caching.CacheProvider;
import com.pedromossi.caching.CacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.core.ParameterizedTypeReference;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

@SpringBootTest(
        classes = CachingAutoConfiguration.class,
        properties = {
                "caching.l1.spec=maximumSize=50,expireAfterWrite=5s",
                "caching.l2.ttl=PT10S",
                "caching.l2.invalidation-topic=integration:test:topic",
                "caching.async.core-pool-size=2",
                "caching.async.max-pool-size=4",
                "caching.async.queue-capacity=100"
        }
)
public class CachingCustomConfigurationIT extends IntegrationTest {

    @Autowired
    private CacheService cacheService;

    @Autowired
    private CachingProperties cachingProperties;

    @MockBean
    @Qualifier("l1CacheProvider")
    private CacheProvider l1CacheProvider;

    @MockBean
    @Qualifier("l2CacheProvider")
    private CacheProvider l2CacheProvider;

    @BeforeEach
    void cleanupCaches() {
        reset(l1CacheProvider, l2CacheProvider);
    }

    @Test
    void shouldUseCustomConfiguration() {
        // Verify that custom properties are loaded correctly
        assertThat(cachingProperties.getL1().getSpec()).isEqualTo("maximumSize=50,expireAfterWrite=5s");
        assertThat(cachingProperties.getL2().getTtl()).isEqualTo(Duration.ofSeconds(10));
        assertThat(cachingProperties.getL2().getInvalidationTopic()).isEqualTo("integration:test:topic");
        assertThat(cachingProperties.getAsync().getCorePoolSize()).isEqualTo(2);
        assertThat(cachingProperties.getAsync().getMaxPoolSize()).isEqualTo(4);
        assertThat(cachingProperties.getAsync().getQueueCapacity()).isEqualTo(100);
    }

    @Test
    void shouldPromoteFromL2ToL1WhenL1Miss() {
        // Given
        String key = "promotion-test-key";
        String value = "Value from L2 - " + UUID.randomUUID();
        Supplier<String> loader = () -> "should-not-be-called";
        ParameterizedTypeReference<String> typeRef = new ParameterizedTypeReference<String>() {};

        // Configure mock behavior: L1 miss, L2 hit
        when(l1CacheProvider.get(eq(key), eq(typeRef))).thenReturn(null);
        when(l2CacheProvider.get(eq(key), eq(typeRef))).thenReturn(value);

        // When
        String result = cacheService.getOrLoad(key, typeRef, loader);

        // Then
        assertThat(result).isEqualTo(value);

        // Verify L1 was checked first, then L2
        verify(l1CacheProvider, times(1)).get(eq(key), eq(typeRef));
        verify(l2CacheProvider, times(1)).get(eq(key), eq(typeRef));

        // Wait for async promotion to L1
        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> {
            verify(l1CacheProvider, times(1)).put(key, value);
        });
    }

    @Test
    void shouldHandleAsyncOperationsCorrectly() {
        // Given
        String keyPrefix = "async-test-";
        int numberOfOperations = 5;
        AtomicInteger completedOperations = new AtomicInteger(0);
        ParameterizedTypeReference<String> typeRef = new ParameterizedTypeReference<String>() {};

        // Configure mock behavior
        for (int i = 0; i < numberOfOperations; i++) {
            String key = keyPrefix + i;
            when(l1CacheProvider.get(eq(key), eq(typeRef))).thenReturn(null);
            when(l2CacheProvider.get(eq(key), eq(typeRef))).thenReturn(null);
        }

        // When - Execute multiple async operations
        CompletableFuture<Void> allOperations = CompletableFuture.allOf(
                IntStream.range(0, numberOfOperations)
                        .mapToObj(i -> CompletableFuture.runAsync(() -> {
                            String key = keyPrefix + i;
                            String value = "Async Value " + i + " - " + UUID.randomUUID();
                            String result = cacheService.getOrLoad(key, typeRef, () -> {
                                completedOperations.incrementAndGet();
                                return value;
                            });
                            assertThat(result).isEqualTo(value);
                        }))
                        .toArray(CompletableFuture[]::new)
        );

        // Wait for completion
        allOperations.join();

        // Then
        assertThat(completedOperations.get()).isEqualTo(numberOfOperations);

        // Wait for async storage operations
        await().atMost(3, TimeUnit.SECONDS).untilAsserted(() -> {
            verify(l1CacheProvider, times(numberOfOperations)).put(anyString(), any());
            verify(l2CacheProvider, times(numberOfOperations)).put(anyString(), any());
        });
    }

    @Test
    void shouldHandleRapidInvalidationAndReload() {
        // Given
        String key = "rapid-invalidation-key";
        String initialValue = "Initial Value - " + UUID.randomUUID();
        String reloadedValue = "Reloaded Value - " + UUID.randomUUID();

        AtomicInteger loaderCallCount = new AtomicInteger(0);
        ParameterizedTypeReference<String> typeRef = new ParameterizedTypeReference<String>() {};

        // Configure mock behavior - both caches return null initially
        when(l1CacheProvider.get(eq(key), eq(typeRef))).thenReturn(null);
        when(l2CacheProvider.get(eq(key), eq(typeRef))).thenReturn(null);

        // When - Load, invalidate, and reload rapidly
        String result1 = cacheService.getOrLoad(key, typeRef, () -> {
            loaderCallCount.incrementAndGet();
            return initialValue;
        });

        // Wait for initial load to complete
        await().atMost(1, TimeUnit.SECONDS).untilAsserted(() -> {
            verify(l1CacheProvider, times(1)).put(key, initialValue);
        });

        // Invalidate
        cacheService.invalidate(key);

        // Wait for invalidation
        await().atMost(1, TimeUnit.SECONDS).untilAsserted(() -> {
            verify(l1CacheProvider, times(1)).evict(key);
            verify(l2CacheProvider, times(1)).evict(key);
        });

        // Reset mocks to ensure fresh cache miss on reload
        reset(l1CacheProvider, l2CacheProvider);
        when(l1CacheProvider.get(eq(key), eq(typeRef))).thenReturn(null);
        when(l2CacheProvider.get(eq(key), eq(typeRef))).thenReturn(null);

        // Reload
        String result2 = cacheService.getOrLoad(key, typeRef, () -> {
            loaderCallCount.incrementAndGet();
            return reloadedValue;
        });

        // Then
        assertThat(result1).isEqualTo(initialValue);
        assertThat(result2).isEqualTo(reloadedValue);
        assertThat(loaderCallCount.get()).isEqualTo(2);

        // Wait for reload storage
        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> {
            verify(l1CacheProvider, times(1)).put(key, reloadedValue);
            verify(l2CacheProvider, times(1)).put(key, reloadedValue);
        });
    }

    @Test
    void shouldHandleCacheProviderExceptions() {
        // Given
        String key = "exception-handling-key";
        String value = "Exception Test Value - " + UUID.randomUUID();
        ParameterizedTypeReference<String> typeRef = new ParameterizedTypeReference<String>() {};

        // Configure mock behavior - L1 throws exception, L2 works normally
        when(l1CacheProvider.get(eq(key), eq(typeRef))).thenThrow(new RuntimeException("L1 Cache Error"));
        when(l2CacheProvider.get(eq(key), eq(typeRef))).thenReturn(null);
        doThrow(new RuntimeException("L1 Put Error")).when(l1CacheProvider).put(key, value);

        // When - Should not fail despite L1 exceptions
        String result = cacheService.getOrLoad(key, typeRef, () -> value);

        // Then
        assertThat(result).isEqualTo(value);

        // Wait for async operations (L2 should still work)
        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> {
            verify(l2CacheProvider, times(1)).put(key, value);
        });
    }

    @Test
    void shouldRespectCustomAsyncConfiguration() {
        // Given - Test that we can handle multiple concurrent operations within the configured pool size
        String keyPrefix = "pool-test-";
        int operationCount = 10; // More than core pool size (2) to test queue handling
        AtomicInteger successfulOperations = new AtomicInteger(0);
        ParameterizedTypeReference<String> typeRef = new ParameterizedTypeReference<String>() {};

        // Configure mock behavior
        for (int i = 0; i < operationCount; i++) {
            String key = keyPrefix + i;
            when(l1CacheProvider.get(eq(key), eq(typeRef))).thenReturn(null);
            when(l2CacheProvider.get(eq(key), eq(typeRef))).thenReturn(null);
        }

        // When - Execute operations that will test the thread pool
        CompletableFuture<Void> allOperations = CompletableFuture.allOf(
                IntStream.range(0, operationCount)
                        .mapToObj(i -> CompletableFuture.supplyAsync(() -> {
                            String key = keyPrefix + i;
                            String value = "Pool Test Value " + i;
                            return cacheService.getOrLoad(key, typeRef, () -> {
                                successfulOperations.incrementAndGet();
                                return value;
                            });
                        }))
                        .toArray(CompletableFuture[]::new)
        );

        // Wait for completion
        allOperations.join();

        // Then
        assertThat(successfulOperations.get()).isEqualTo(operationCount);

        // All operations should complete successfully within reasonable time
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            verify(l1CacheProvider, times(operationCount)).put(anyString(), any());
            verify(l2CacheProvider, times(operationCount)).put(anyString(), any());
        });
    }
}
