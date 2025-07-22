package com.pedromossi.caching.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.pedromossi.caching.CacheProvider;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
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
@DisplayName("MultiLevelCacheService Tests")
class MultiLevelCacheServiceTest {

    private static final String KEY = "test:key";
    private static final String VALUE = "test-value";
    private static final ParameterizedTypeReference<String> TYPE_REF = new ParameterizedTypeReference<>() {};

    @Mock private CacheProvider l1Cache;
    @Mock private CacheProvider l2Cache;
    @Mock private Supplier<String> loader;

    private ExecutorService executorService;
    private MultiLevelCacheService cacheService;

    @BeforeEach
    void setUp() {
        executorService = Executors.newSingleThreadExecutor();
        cacheService = new MultiLevelCacheService(Optional.of(l1Cache), Optional.of(l2Cache), executorService);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        executorService.shutdown();
        executorService.awaitTermination(1, TimeUnit.SECONDS);
    }

    @Nested
    @DisplayName("for getOrLoad()")
    class GetOrLoadTests {

        @Test
        @DisplayName("should return from L1 on cache hit")
        void shouldReturnFromL1OnHit() {
            when(l1Cache.get(eq(KEY), any(ParameterizedTypeReference.class))).thenReturn(VALUE);
            String result = cacheService.getOrLoad(KEY, String.class, loader);
            assertThat(result).isEqualTo(VALUE);
            verifyNoInteractions(l2Cache, loader);
        }

        @Test
        @DisplayName("should return from L2 and promote to L1 on L1 miss")
        void shouldReturnFromL2AndPromote() {
            when(l1Cache.get(eq(KEY), any(ParameterizedTypeReference.class))).thenReturn(null);
            when(l2Cache.get(eq(KEY), any(ParameterizedTypeReference.class))).thenReturn(VALUE);

            String result = cacheService.getOrLoad(KEY, String.class, loader);

            assertThat(result).isEqualTo(VALUE);
            verifyNoInteractions(loader);
            await().untilAsserted(() -> verify(l1Cache).put(KEY, VALUE));
        }

        @Test
        @DisplayName("should load from source and populate all caches on miss")
        void shouldLoadFromSourceAndPopulateCaches() {
            when(l1Cache.get(eq(KEY), any(ParameterizedTypeReference.class))).thenReturn(null);
            when(l2Cache.get(eq(KEY), any(ParameterizedTypeReference.class))).thenReturn(null);
            when(loader.get()).thenReturn(VALUE);

            String result = cacheService.getOrLoad(KEY, String.class, loader);

            assertThat(result).isEqualTo(VALUE);
            await().untilAsserted(() -> {
                verify(l1Cache).put(KEY, VALUE);
                verify(l2Cache).put(KEY, VALUE);
            });
        }

        @Test
        @DisplayName("should correctly handle full lifecycle of null values")
        void shouldHandleNullValueLifecycle() {
            // 1. Loader returns null, which should be cached as a sentinel
            when(l1Cache.get(eq(KEY), any(ParameterizedTypeReference.class))).thenReturn(null);
            when(l2Cache.get(eq(KEY), any(ParameterizedTypeReference.class))).thenReturn(null);
            when(loader.get()).thenReturn(null);

            String result = cacheService.getOrLoad(KEY, String.class, loader);
            assertThat(result).isNull();

            // 2. Verify sentinel was stored
            var captor = ArgumentCaptor.forClass(Object.class);
            await().untilAsserted(() -> verify(l1Cache).put(eq(KEY), captor.capture()));
            Object nullSentinel = captor.getValue();
            assertThat(nullSentinel).isNotNull();

            // 3. Verify L1 hit on sentinel returns null without calling loader
            when(l1Cache.get(eq(KEY), any(ParameterizedTypeReference.class))).thenReturn(nullSentinel);
            result = cacheService.getOrLoad(KEY, String.class, loader);
            assertThat(result).isNull();
            verify(loader, times(1)).get(); // Should not be called again
        }

        @Test
        @DisplayName("should gracefully handle cache errors and fallback")
        void shouldHandleCacheErrors() {
            when(l1Cache.get(eq(KEY), any(ParameterizedTypeReference.class))).thenThrow(new RuntimeException("L1 Error"));
            when(l2Cache.get(eq(KEY), any(ParameterizedTypeReference.class))).thenThrow(new RuntimeException("L2 Error"));
            when(loader.get()).thenReturn(VALUE);

            String result = cacheService.getOrLoad(KEY, String.class, loader);

            assertThat(result).isEqualTo(VALUE);
            await().untilAsserted(() -> {
                verify(l1Cache).put(KEY, VALUE);
                verify(l2Cache).put(KEY, VALUE);
            });
        }
    }

    @Nested
    @DisplayName("for getOrLoadAll()")
    class GetOrLoadAllTests {

        @Test
        @DisplayName("should fetch from all levels and populate caches")
        void shouldFetchFromAllLevels() {
            var typeRef = new ParameterizedTypeReference<String>() {};
            Set<String> keys = Set.of("k1", "k2", "k3");
            when(l1Cache.getAll(keys, typeRef)).thenReturn(Map.of("k1", "v1_L1"));
            when(l2Cache.getAll(Set.of("k2", "k3"), typeRef)).thenReturn(Map.of("k2", "v2_L2"));
            Function<Set<String>, Map<String, String>> batchLoader =
                    missingKeys -> {
                        assertThat(missingKeys).containsExactly("k3");
                        return Map.of("k3", "v3_Source");
                    };

            Map<String, String> result = cacheService.getOrLoadAll(keys, typeRef, batchLoader);

            assertThat(result).containsExactlyInAnyOrderEntriesOf(
                    Map.of("k1", "v1_L1", "k2", "v2_L2", "k3", "v3_Source"));

            await().untilAsserted(() -> {
                verify(l1Cache).putAll(Map.of("k2", "v2_L2", "k3", "v3_Source"));
                verify(l2Cache).putAll(Map.of("k3", "v3_Source"));
            });
        }
    }

    @Nested
    @DisplayName("for invalidate()")
    class InvalidationTests {

        @Test
        @DisplayName("should evict key from all caches")
        void shouldInvalidateKey() {
            cacheService.invalidate(KEY);
            await().untilAsserted(() -> {
                verify(l1Cache).evict(KEY);
                verify(l2Cache).evict(KEY);
            });
        }

        @Test
        @DisplayName("should evict all keys from all caches")
        void shouldInvalidateAllKeys() {
            Set<String> keys = Set.of("k1", "k2");
            cacheService.invalidateAll(keys);
            await().untilAsserted(() -> {
                verify(l1Cache).evictAll(keys);
                verify(l2Cache).evictAll(keys);
            });
        }

        @Test
        @DisplayName("should continue invalidation if one cache layer fails")
        void shouldContinueInvalidationOnError() {
            doThrow(new RuntimeException("L2 Evict Error")).when(l2Cache).evict(KEY);
            cacheService.invalidate(KEY);
            await().untilAsserted(() -> {
                verify(l2Cache).evict(KEY);
                verify(l1Cache).evict(KEY); // L1 should still be called
            });
        }
    }

    @Nested
    @DisplayName("with different configurations")
    class ConfigurationTests {
        @Test
        @DisplayName("should work with L1 cache only")
        void shouldWorkWithL1Only() {
            cacheService = new MultiLevelCacheService(Optional.of(l1Cache), Optional.empty(), executorService);
            when(l1Cache.get(eq(KEY), any(ParameterizedTypeReference.class))).thenReturn(null);
            when(loader.get()).thenReturn(VALUE);

            cacheService.getOrLoad(KEY, String.class, loader);

            verify(l1Cache).get(eq(KEY), any(ParameterizedTypeReference.class));
            verifyNoInteractions(l2Cache);
            await().untilAsserted(() -> verify(l1Cache).put(KEY, VALUE));
        }

        @Test
        @DisplayName("should work with L2 cache only")
        void shouldWorkWithL2Only() {
            cacheService = new MultiLevelCacheService(Optional.empty(), Optional.of(l2Cache), executorService);
            when(l2Cache.get(eq(KEY), any(ParameterizedTypeReference.class))).thenReturn(null);
            when(loader.get()).thenReturn(VALUE);

            cacheService.getOrLoad(KEY, String.class, loader);

            verify(l2Cache).get(eq(KEY), any(ParameterizedTypeReference.class));
            verifyNoInteractions(l1Cache);
            await().untilAsserted(() -> verify(l2Cache).put(KEY, VALUE));
        }

        @Test
        @DisplayName("should work with no caches (passthrough to loader)")
        void shouldWorkWithNoCaches() {
            cacheService = new MultiLevelCacheService(Optional.empty(), Optional.empty(), executorService);
            lenient().when(loader.get()).thenReturn(VALUE);

            String result = cacheService.getOrLoad(KEY, String.class, loader);

            assertThat(result).isEqualTo(VALUE);
            verify(loader).get();
            verifyNoInteractions(l1Cache, l2Cache);
        }
    }
}