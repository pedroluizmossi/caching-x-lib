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

import java.util.List;
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
                "caching.l1.spec=maximumSize=200,expireAfterWrite=30s",
                "caching.l2.ttl=PT1M",
                "caching.async.core-pool-size=4",
                "caching.async.max-pool-size=8"
        }
)
public class CachingConcurrencyIT extends IntegrationTest {

    @Autowired
    private CacheService cacheService;

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
    void shouldHandleConcurrentLoads() {
        // Given
        String keyPrefix = "concurrent-key-";
        int numberOfThreads = 10;
        AtomicInteger loaderCallCount = new AtomicInteger(0);
        ParameterizedTypeReference<String> typeRef = new ParameterizedTypeReference<String>() {};

        // Configure mock behavior - return null first time, then the value
        for (int i = 0; i < numberOfThreads; i++) {
            String key = keyPrefix + i;
            String value = "Value-" + i + "-" + UUID.randomUUID();
            when(l1CacheProvider.get(eq(key), eq(typeRef))).thenReturn(null).thenReturn(value);
            when(l2CacheProvider.get(eq(key), eq(typeRef))).thenReturn(null);
        }

        // When - Execute concurrent loads
        List<CompletableFuture<String>> futures = IntStream.range(0, numberOfThreads)
                .mapToObj(i -> CompletableFuture.supplyAsync(() -> {
                    String key = keyPrefix + i;
                    String expectedValue = "Value-" + i + "-" + UUID.randomUUID();
                    Supplier<String> loader = () -> {
                        loaderCallCount.incrementAndGet();
                        return expectedValue;
                    };
                    return cacheService.getOrLoad(key, typeRef, loader);
                }))
                .toList();

        // Wait for all to complete
        List<String> results = futures.stream()
                .map(CompletableFuture::join)
                .toList();

        // Then
        assertThat(results).hasSize(numberOfThreads);
        assertThat(loaderCallCount.get()).isEqualTo(numberOfThreads);

        // Wait for async operations
        await().atMost(3, TimeUnit.SECONDS).untilAsserted(() -> {
            verify(l1CacheProvider, times(numberOfThreads)).put(anyString(), any());
            verify(l2CacheProvider, times(numberOfThreads)).put(anyString(), any());
        });
    }

    @Test
    void shouldHandleConcurrentInvalidations() {
        // Given
        String keyPrefix = "invalidate-key-";
        int numberOfKeys = 5;
        ParameterizedTypeReference<String> typeRef = new ParameterizedTypeReference<String>() {};

        // Pre-populate cache
        for (int i = 0; i < numberOfKeys; i++) {
            String key = keyPrefix + i;
            String value = "Value-" + i;

            cacheService.getOrLoad(key, typeRef, () -> value);
        }

        // Wait for initial cache population
        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> {
            verify(l1CacheProvider, times(numberOfKeys)).put(anyString(), any());
            verify(l2CacheProvider, times(numberOfKeys)).put(anyString(), any());
        });

        // When - Execute concurrent invalidations
        List<CompletableFuture<Void>> invalidationFutures = IntStream.range(0, numberOfKeys)
                .mapToObj(i -> CompletableFuture.runAsync(() -> {
                    String key = keyPrefix + i;
                    cacheService.invalidate(key);
                }))
                .toList();

        // Wait for all invalidations to complete
        CompletableFuture.allOf(invalidationFutures.toArray(new CompletableFuture[0])).join();

        // Then - Verify all keys were invalidated
        await().atMost(3, TimeUnit.SECONDS).untilAsserted(() -> {
            verify(l1CacheProvider, times(numberOfKeys)).evict(anyString());
            verify(l2CacheProvider, times(numberOfKeys)).evict(anyString());
        });
    }

    @Test
    void shouldHandleMixedOperationsConcurrently() {
        // Given
        String readKey = "read-key";
        String writeKey = "write-key";
        String invalidateKey = "invalidate-key";

        String readValue = "Read Value - " + UUID.randomUUID();
        String writeValue = "Write Value - " + UUID.randomUUID();
        String invalidateValue = "Invalidate Value - " + UUID.randomUUID();
        ParameterizedTypeReference<String> typeRef = new ParameterizedTypeReference<String>() {};

        // Configure mock behavior
        when(l1CacheProvider.get(eq(readKey), eq(typeRef))).thenReturn(null).thenReturn(readValue);
        when(l2CacheProvider.get(eq(readKey), eq(typeRef))).thenReturn(null);

        when(l1CacheProvider.get(eq(writeKey), eq(typeRef))).thenReturn(null).thenReturn(writeValue);
        when(l2CacheProvider.get(eq(writeKey), eq(typeRef))).thenReturn(null);

        when(l1CacheProvider.get(eq(invalidateKey), eq(typeRef)))
                .thenReturn(null).thenReturn(invalidateValue).thenReturn(null);
        when(l2CacheProvider.get(eq(invalidateKey), eq(typeRef))).thenReturn(null);

        // When - Execute mixed operations concurrently
        CompletableFuture<String> readFuture = CompletableFuture.supplyAsync(() ->
                cacheService.getOrLoad(readKey, typeRef, () -> readValue));

        CompletableFuture<String> writeFuture = CompletableFuture.supplyAsync(() ->
                cacheService.getOrLoad(writeKey, typeRef, () -> writeValue));

        CompletableFuture<Void> invalidateFuture = CompletableFuture.runAsync(() -> {
            // First load the value
            cacheService.getOrLoad(invalidateKey, typeRef, () -> invalidateValue);
            // Then invalidate it
            cacheService.invalidate(invalidateKey);
        });

        // Wait for all operations to complete
        CompletableFuture.allOf(readFuture, writeFuture, invalidateFuture).join();

        // Then
        assertThat(readFuture.join()).isEqualTo(readValue);
        assertThat(writeFuture.join()).isEqualTo(writeValue);

        // Wait for async operations
        await().atMost(3, TimeUnit.SECONDS).untilAsserted(() -> {
            verify(l1CacheProvider, atLeast(2)).put(anyString(), any());
            verify(l2CacheProvider, atLeast(2)).put(anyString(), any());
            verify(l1CacheProvider, times(1)).evict(invalidateKey);
            verify(l2CacheProvider, times(1)).evict(invalidateKey);
        });
    }

    @Test
    void shouldHandleNullValuesInConcurrentScenario() {
        // Given
        String keyPrefix = "null-value-key-";
        int numberOfThreads = 5;
        AtomicInteger loaderCallCount = new AtomicInteger(0);
        ParameterizedTypeReference<String> typeRef = new ParameterizedTypeReference<String>() {};

        // Configure mock behavior to return null values (cache miss)
        for (int i = 0; i < numberOfThreads; i++) {
            String key = keyPrefix + i;
            when(l1CacheProvider.get(eq(key), any())).thenReturn(null);
            when(l2CacheProvider.get(eq(key), any())).thenReturn(null);
        }

        // When - Execute concurrent loads that return null
        List<CompletableFuture<String>> futures = IntStream.range(0, numberOfThreads)
                .mapToObj(i -> CompletableFuture.supplyAsync(() -> {
                    String key = keyPrefix + i;
                    Supplier<String> loader = () -> {
                        loaderCallCount.incrementAndGet();
                        return null; // Simulate data not found
                    };
                    return cacheService.getOrLoad(key, typeRef, loader);
                }))
                .toList();

        // Wait for all to complete
        List<String> results = futures.stream()
                .map(CompletableFuture::join)
                .toList();

        // Then
        assertThat(results).hasSize(numberOfThreads);
        assertThat(results).allMatch(result -> result == null);
        assertThat(loaderCallCount.get()).isEqualTo(numberOfThreads);

        // Wait for async operations to complete
        await().atMost(1, TimeUnit.SECONDS).untilAsserted(() -> {
            // Verify that null values are cached using the sentinel object for each key
            for (int i = 0; i < numberOfThreads; i++) {
                String key = keyPrefix + i;
                verify(l1CacheProvider, atLeastOnce()).put(eq(key), any());
                verify(l2CacheProvider, atLeastOnce()).put(eq(key), any());
            }
        });
    }
}
