package com.pedromossi.caching.impl;

import com.pedromossi.caching.CacheProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.ParameterizedTypeReference;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Comprehensive unit tests for the {@link MultiLevelCacheService} class.
 * <p>
 * This test suite validates the multi-level caching behavior, ensuring that
 * the service correctly implements the cache-aside pattern with L1 and L2 cache
 * layers. The tests cover all major caching scenarios including cache hits,
 * misses, promotions, and null value handling.
 */
@ExtendWith(MockitoExtension.class)
class MultiLevelCacheServiceTest {

    @Mock
    private CacheProvider l1Cache;

    @Mock
    private CacheProvider l2Cache;

    @Mock
    private Supplier<String> stringLoader;

    private ExecutorService executorService;
    private MultiLevelCacheService cacheService;

    @BeforeEach
    void setUp() {
        // Use a direct executor for testing to make async operations synchronous
        executorService = Executors.newSingleThreadExecutor();
        cacheService = new MultiLevelCacheService(Optional.of(l1Cache), Optional.of(l2Cache), executorService);
    }

    /**
     * Tests that values are returned directly from L1 cache when available.
     */
    @Test
    void getOrLoad_shouldReturnFromL1Cache_whenL1Hit() {
        // Given
        String key = "test:key";
        String expectedValue = "value_from_l1";
        ParameterizedTypeReference<String> typeRef = new ParameterizedTypeReference<>() {
        };

        when(l1Cache.get(eq(key), eq(typeRef))).thenReturn(expectedValue);

        // When
        String actualValue = cacheService.getOrLoad(key, typeRef, stringLoader);

        // Then
        assertThat(actualValue).isEqualTo(expectedValue);
        verify(l1Cache).get(eq(key), eq(typeRef));
        verifyNoInteractions(l2Cache, stringLoader);
    }

    /**
     * Tests L2 cache hits with automatic promotion to L1 cache.
     */
    @Test
    void getOrLoad_shouldReturnFromL2AndPromoteToL1_whenL2Hit() throws InterruptedException {
        // Given
        String key = "test:key";
        String expectedValue = "value_from_l2";
        ParameterizedTypeReference<String> typeRef = new ParameterizedTypeReference<>() {
        };

        when(l1Cache.get(eq(key), eq(typeRef))).thenReturn(null);
        when(l2Cache.get(eq(key), eq(typeRef))).thenReturn(expectedValue);

        // When
        String actualValue = cacheService.getOrLoad(key, typeRef, stringLoader);

        // Then
        assertThat(actualValue).isEqualTo(expectedValue);
        verify(l1Cache).get(eq(key), eq(typeRef));
        verify(l2Cache).get(eq(key), eq(typeRef));
        verifyNoInteractions(stringLoader);

        // Wait for async promotion to complete
        Thread.sleep(100);
        verify(l1Cache).put(key, expectedValue);
    }

    /**
     * Tests complete cache miss scenario with data loading and cache population.
     */
    @Test
    void getOrLoad_shouldLoadFromSourceAndStoreInCaches_whenCompleteMiss() throws InterruptedException {
        // Given
        String key = "test:key";
        String expectedValue = "value_from_source";
        ParameterizedTypeReference<String> typeRef = new ParameterizedTypeReference<>() {
        };

        when(l1Cache.get(eq(key), eq(typeRef))).thenReturn(null);
        when(l2Cache.get(eq(key), eq(typeRef))).thenReturn(null);
        when(stringLoader.get()).thenReturn(expectedValue);

        // When
        String actualValue = cacheService.getOrLoad(key, typeRef, stringLoader);

        // Then
        assertThat(actualValue).isEqualTo(expectedValue);
        verify(l1Cache).get(eq(key), eq(typeRef));
        verify(l2Cache).get(eq(key), eq(typeRef));
        verify(stringLoader).get();

        // Wait for async storage to complete
        Thread.sleep(100);
        verify(l2Cache).put(key, expectedValue);
        verify(l1Cache).put(key, expectedValue);
    }

    /**
     * Tests null value handling when the data loader returns null.
     */
    @Test
    void getOrLoad_shouldStoreNullValueInCache_whenLoaderReturnsNull() throws InterruptedException {
        // Given
        String key = "test:key";
        ParameterizedTypeReference<String> typeRef = new ParameterizedTypeReference<>() {
        };

        when(l1Cache.get(eq(key), eq(typeRef))).thenReturn(null);
        when(l2Cache.get(eq(key), eq(typeRef))).thenReturn(null);
        when(stringLoader.get()).thenReturn(null);

        // When
        String actualValue = cacheService.getOrLoad(key, typeRef, stringLoader);

        // Then
        assertThat(actualValue).isNull();

        // Wait for async storage to complete
        Thread.sleep(100);
        // Verify that a non-null sentinel object was stored.
        verify(l1Cache).put(eq(key), any(Object.class));
        verify(l2Cache).put(eq(key), any(Object.class));
    }

    /**
     * Tests that cached null values are properly returned from L1 cache.
     */
    @Test
    void getOrLoad_shouldReturnNullFromL1Cache_whenNullValueCached() throws InterruptedException {
        // Given
        String key = "test:key";
        ParameterizedTypeReference<String> typeRef = new ParameterizedTypeReference<>() {};

        // First, simulate caching a null value
        when(l1Cache.get(eq(key), eq(typeRef))).thenReturn(null);
        when(l2Cache.get(eq(key), eq(typeRef))).thenReturn(null);
        when(stringLoader.get()).thenReturn(null);

        cacheService.getOrLoad(key, typeRef, stringLoader);

        // Wait for async storage and capture the sentinel
        Thread.sleep(100);
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(l1Cache).put(eq(key), captor.capture());
        Object cachedSentinel = captor.getValue();

        // Reset mocks and simulate L1 hit with the sentinel
        reset(l1Cache, l2Cache, stringLoader);
        // CORRECTION: Use a less specific matcher for the type reference to avoid casting issues in the mock setup.
        when(l1Cache.get(eq(key), any(ParameterizedTypeReference.class))).thenReturn(cachedSentinel);

        // When
        String actualValue = cacheService.getOrLoad(key, typeRef, stringLoader);

        // Then
        assertThat(actualValue).isNull();
        verify(l1Cache).get(eq(key), eq(typeRef));
        verifyNoInteractions(l2Cache, stringLoader);
    }

    /**
     * Tests null value retrieval from L2 cache with promotion to L1.
     */
    @Test
    @SuppressWarnings("unchecked")
    void getOrLoad_shouldReturnNullFromL2Cache_andPromoteToL1() throws InterruptedException {
        // Given
        String key = "test:key";
        ParameterizedTypeReference<String> typeRef = new ParameterizedTypeReference<>() {};

        // First call to cache null value
        when(l1Cache.get(eq(key), any(ParameterizedTypeReference.class))).thenReturn(null);
        when(l2Cache.get(eq(key), any(ParameterizedTypeReference.class))).thenReturn(null);
        when(stringLoader.get()).thenReturn(null);

        cacheService.getOrLoad(key, typeRef, stringLoader);

        // Wait for caching and capture the sentinel
        Thread.sleep(100);
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(l2Cache).put(eq(key), captor.capture());
        Object nullSentinel = captor.getValue();

        // Reset mocks and simulate L2 hit scenario
        reset(l1Cache, l2Cache, stringLoader);
        when(l1Cache.get(eq(key), eq(typeRef))).thenReturn(null);
        // CORRECTION: Use a less specific matcher for the mock to handle the sentinel return type.
        when(l2Cache.get(eq(key), any(ParameterizedTypeReference.class))).thenReturn(nullSentinel);

        // When
        String actualValue = cacheService.getOrLoad(key, typeRef, stringLoader);

        // Then
        assertThat(actualValue).isNull();
        verify(l1Cache).get(eq(key), eq(typeRef));
        verify(l2Cache).get(eq(key), eq(typeRef));
        verifyNoInteractions(stringLoader);

        // Wait for async promotion to complete
        Thread.sleep(100);
        verify(l1Cache).put(key, nullSentinel);
    }

    /**
     * Tests comprehensive null value caching lifecycle.
     */
    @Test
    void getOrLoad_shouldHandleNullValuesCachedPreviously() throws InterruptedException {
        // Given
        String key = "bio:456";
        ParameterizedTypeReference<String> typeRef = new ParameterizedTypeReference<>() {};

        when(l1Cache.get(eq(key), eq(typeRef))).thenReturn(null);
        when(l2Cache.get(eq(key), eq(typeRef))).thenReturn(null);
        when(stringLoader.get()).thenReturn(null);

        cacheService.getOrLoad(key, typeRef, stringLoader);

        Thread.sleep(100);
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(l1Cache).put(eq(key), captor.capture());
        Object cachedSentinel = captor.getValue();

        // Reset mocks for the second call
        reset(l1Cache, l2Cache, stringLoader);
        // CORRECTION: Use a less specific matcher here as well.
        when(l1Cache.get(eq(key), any(ParameterizedTypeReference.class))).thenReturn(cachedSentinel);

        // When
        String secondResult = cacheService.getOrLoad(key, typeRef, stringLoader);

        // Then
        assertThat(secondResult).isNull();
        verify(l1Cache).get(eq(key), eq(typeRef));
        verifyNoInteractions(l2Cache, stringLoader);
    }

    /**
     * Tests cache invalidation across both L1 and L2 cache layers.
     */
    @Test
    void invalidate_shouldEvictFromBothCaches() throws InterruptedException {
        // Given
        String key = "test:key:to:invalidate";

        // When
        cacheService.invalidate(key);

        // Wait for async invalidation to complete
        Thread.sleep(100);

        // Then
        verify(l1Cache).evict(key);
        verify(l2Cache).evict(key);
    }

    @Test
    void getOrLoadAll_shouldFetchFromAllLevelsAndUseLoaderForMissingKeys() {
        // Given
        var typeRef = new ParameterizedTypeReference<String>() {};
        Set<String> allKeys = Set.of("key1", "key2", "key3", "key4");
        Set<String> keysMissingFromL1 = Set.of("key2", "key3", "key4");
        Set<String> keysMissingFromL2 = Set.of("key3", "key4");

        // When l1Cache is queried with all keys, it returns only key1
        when(l1Cache.getAll(eq(allKeys), eq(typeRef))).thenReturn(Map.of("key1", "value1_L1"));

        // When l2Cache is queried with missing keys, it returns only key2
        when(l2Cache.getAll(eq(keysMissingFromL1), eq(typeRef))).thenReturn(Map.of("key2", "value2_L2"));

        // Loader returns values for key3 and key4
        Function<Set<String>, Map<String, String>> loader = missingKeys -> {
            assertThat(missingKeys).containsExactlyInAnyOrderElementsOf(keysMissingFromL2);
            return Map.of("key3", "value3_Source", "key4", "value4_Source");
        };

        // When
        Map<String, String> result = cacheService.getOrLoadAll(allKeys, typeRef, loader);

        // Then
        // 1. Verify result contains all expected values
        assertThat(result)
                .hasSize(4)
                .containsEntry("key1", "value1_L1")
                .containsEntry("key2", "value2_L2")
                .containsEntry("key3", "value3_Source")
                .containsEntry("key4", "value4_Source");

        // 2. Verify the correct cache interactions occurred
        verify(l1Cache).getAll(eq(allKeys), eq(typeRef));
        verify(l2Cache).getAll(eq(keysMissingFromL1), eq(typeRef));

        // 3. Verify cache population
        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> {
            // The implementation now combines all L1 updates into a single putAll call for efficiency
            Map<String, Object> expectedL1Updates = Map.of(
                "key2", "value2_L2",      // Promoted from L2
                "key3", "value3_Source",  // Loaded from source
                "key4", "value4_Source"   // Loaded from source
            );
            verify(l1Cache).putAll(eq(expectedL1Updates));

            // L2 cache should only receive the data that was loaded from source
            Map<String, Object> sourceDataToStore = Map.of("key3", "value3_Source", "key4", "value4_Source");
            verify(l2Cache).putAll(eq(sourceDataToStore));
        });
    }

}