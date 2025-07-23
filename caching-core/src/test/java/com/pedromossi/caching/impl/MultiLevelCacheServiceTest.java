package com.pedromossi.caching.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.pedromossi.caching.CacheProvider;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
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
@DisplayName("MultiLevelCacheService")
class MultiLevelCacheServiceTest {

    private static final String KEY = "test:key";
    private static final String VALUE = "test-value";
    private static final ParameterizedTypeReference<String> TYPE_REF = new ParameterizedTypeReference<>() {};

    @Mock private CacheProvider l1Cache;
    @Mock private CacheProvider l2Cache;
    @Mock private Supplier<String> loader;

    private ExecutorService testExecutor;
    private MultiLevelCacheService cacheService;

    @BeforeEach
    void setUp() {
        testExecutor = Executors.newCachedThreadPool();
        cacheService = new MultiLevelCacheService(Optional.of(l1Cache), Optional.of(l2Cache), testExecutor);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        testExecutor.shutdownNow();
        testExecutor.awaitTermination(1, TimeUnit.SECONDS);
    }

    @Nested
    @DisplayName("Single Key Operations (getOrLoad)")
    class GetOrLoad {

        @Test
        @DisplayName("should return from L1 on hit")
        void shouldReturnFromL1OnHit() {
            when(l1Cache.get(KEY, TYPE_REF)).thenReturn(VALUE);
            assertThat(cacheService.getOrLoad(KEY, TYPE_REF, loader)).isEqualTo(VALUE);
            verifyNoInteractions(l2Cache, loader);
        }

        @Test
        @DisplayName("should return from L2 and promote to L1 on L1 miss")
        void shouldReturnFromL2AndPromote() {
            when(l2Cache.get(KEY, TYPE_REF)).thenReturn(VALUE);
            assertThat(cacheService.getOrLoad(KEY, TYPE_REF, loader)).isEqualTo(VALUE);
            verifyNoInteractions(loader);
            await().untilAsserted(() -> verify(l1Cache).put(KEY, VALUE));
        }

        @Test
        @DisplayName("should load from source and populate all caches on miss")
        void shouldLoadFromSourceAndPopulate() {
            when(loader.get()).thenReturn(VALUE);
            assertThat(cacheService.getOrLoad(KEY, TYPE_REF, loader)).isEqualTo(VALUE);
            await().untilAsserted(() -> {
                verify(l1Cache).put(any(), eq(VALUE));
                verify(l2Cache).put(any(), eq(VALUE));
            });
        }

        @Test
        @DisplayName("should handle and cache null values, covering NullValue.toString()")
        void shouldHandleAndCacheNullValues() {
            when(loader.get()).thenReturn(null);
            assertThat(cacheService.getOrLoad(KEY, TYPE_REF, loader)).isNull();

            var captor = ArgumentCaptor.forClass(Object.class);
            await().untilAsserted(() -> verify(l1Cache).put(eq(KEY), captor.capture()));
            assertThat(captor.getValue()).isNotNull();
            // This assertion covers the NullValue.toString() method
            assertThat(captor.getValue().toString()).isEqualTo("NullValue");
        }

        @Test
        @DisplayName("should delegate correctly when called with Class type")
        void shouldDelegateCorrectlyWhenCalledWithClassType() {
            when(l1Cache.get(eq(KEY), any(ParameterizedTypeReference.class))).thenReturn(VALUE);
            String result = cacheService.getOrLoad(KEY, String.class, loader);
            assertThat(result).isEqualTo(VALUE);
            verify(l1Cache).get(eq(KEY), any(ParameterizedTypeReference.class));
        }
    }

    @Nested
    @DisplayName("Error Handling")
    class ErrorHandling {
        @Test
        @DisplayName("should gracefully fallback when cache providers fail on read")
        void shouldFallbackOnReadErrors() {
            when(l1Cache.get(KEY, TYPE_REF)).thenThrow(new RuntimeException("L1 error"));
            when(l2Cache.get(KEY, TYPE_REF)).thenThrow(new RuntimeException("L2 error"));
            when(loader.get()).thenReturn(VALUE);

            assertThat(cacheService.getOrLoad(KEY, TYPE_REF, loader)).isEqualTo(VALUE);
            verify(loader).get();
        }

        @Test
        @DisplayName("should log and continue on L1 promotion error")
        void shouldHandleL1PromotionError() {
            when(l2Cache.get(KEY, TYPE_REF)).thenReturn(VALUE);
            doThrow(new RuntimeException("L1 Put Error")).when(l1Cache).put(KEY, VALUE);

            assertThat(cacheService.getOrLoad(KEY, TYPE_REF, loader)).isEqualTo(VALUE);
            await().untilAsserted(() -> verify(l1Cache).put(KEY, VALUE));
        }

        @Test
        @DisplayName("should log and continue on async storage errors")
        void shouldHandleAsyncStorageErrors() {
            when(loader.get()).thenReturn(VALUE);
            doThrow(new RuntimeException("L1 Put Error")).when(l1Cache).put(any(), any());
            doThrow(new RuntimeException("L2 Put Error")).when(l2Cache).put(any(), any());

            assertThat(cacheService.getOrLoad(KEY, TYPE_REF, loader)).isEqualTo(VALUE);
            await().untilAsserted(() -> {
                verify(l1Cache).put(any(), any());
                verify(l2Cache).put(any(), any());
            });
        }

        @Test
        @DisplayName("should continue invalidation if one cache provider fails")
        void shouldContinueInvalidationOnError() {
            doThrow(new RuntimeException("L2 Evict Error")).when(l2Cache).evict(KEY);
            doThrow(new RuntimeException("L1 Evict Error")).when(l1Cache).evict(KEY);

            cacheService.invalidate(KEY);

            await().untilAsserted(() -> {
                verify(l2Cache).evict(KEY);
                verify(l1Cache).evict(KEY);
            });
        }
    }

    @Nested
    @DisplayName("Batch Operations (getOrLoadAll)")
    class BatchOperations {
        @Test
        @DisplayName("should fetch from all levels and use batch loader")
        void shouldFetchFromAllLevels() {
            Set<String> keys = Set.of("k1", "k2", "k3");
            when(l1Cache.getAll(keys, TYPE_REF)).thenReturn(Map.of("k1", "v1_L1"));
            when(l2Cache.getAll(Set.of("k2", "k3"), TYPE_REF)).thenReturn(Map.of("k2", "v2_L2"));

            Function<Set<String>, Map<String, String>> batchLoader = missingKeys -> {
                assertThat(missingKeys).containsExactly("k3");
                return Map.of("k3", "v3_Source");
            };

            Map<String, String> result = cacheService.getOrLoadAll(keys, TYPE_REF, batchLoader);

            assertThat(result).containsExactlyInAnyOrderEntriesOf(
                    Map.of("k1", "v1_L1", "k2", "v2_L2", "k3", "v3_Source"));
        }

        @Test
        @DisplayName("should return immediately on full L1 hit")
        void shouldReturnImmediatelyOnFullL1Hit() {
            Set<String> keys = Set.of("k1", "k2");
            when(l1Cache.getAll(keys, TYPE_REF)).thenReturn(Map.of("k1", "v1", "k2", "v2"));

            Map<String, String> result = cacheService.getOrLoadAll(keys, TYPE_REF, mock(Function.class));

            assertThat(result).hasSize(2);
            verify(l1Cache).getAll(keys, TYPE_REF);
            verifyNoInteractions(l2Cache);
        }

        @Test
        @DisplayName("should only promote and not load on full L2 hit")
        void shouldOnlyPromoteOnFullL2Hit() {
            Set<String> keys = Set.of("k1", "k2");
            when(l1Cache.getAll(keys, TYPE_REF)).thenReturn(Map.of());
            when(l2Cache.getAll(keys, TYPE_REF)).thenReturn(Map.of("k1", "v1_L2", "k2", "v2_L2"));

            Map<String, String> result = cacheService.getOrLoadAll(keys, TYPE_REF, mock(Function.class));

            assertThat(result).hasSize(2);
            await().untilAsserted(() -> verify(l1Cache).putAll(Map.of("k1", "v1_L2", "k2", "v2_L2")));
        }
    }

    @Nested
    @DisplayName("Concurrency and Interruption")
    class Concurrency {
        private ExecutorService concurrentExecutor;

        @BeforeEach
        void setupExecutor() {
            concurrentExecutor = Executors.newFixedThreadPool(2);
        }

        @AfterEach
        void shutdownExecutor() throws InterruptedException {
            concurrentExecutor.shutdownNow();
            concurrentExecutor.awaitTermination(1, TimeUnit.SECONDS);
        }

        @Test
        @DisplayName("should execute loader only once for concurrent requests")
        void shouldExecuteLoaderOnlyOnce() throws Exception {
            CountDownLatch loaderGate = new CountDownLatch(1);
            AtomicInteger loaderInvocations = new AtomicInteger(0);
            when(loader.get()).thenAnswer(inv -> {
                loaderInvocations.incrementAndGet();
                loaderGate.await(2, TimeUnit.SECONDS);
                return VALUE;
            });

            Future<String> future1 = concurrentExecutor.submit(() -> cacheService.getOrLoad(KEY, TYPE_REF, loader));
            Future<String> future2 = concurrentExecutor.submit(() -> cacheService.getOrLoad(KEY, TYPE_REF, loader));

            Thread.sleep(100);
            loaderGate.countDown();

            assertThat(future1.get()).isEqualTo(VALUE);
            assertThat(future2.get()).isEqualTo(VALUE);
            assertThat(loaderInvocations.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("should handle interruption and preserve thread's interrupted status")
        void shouldHandleInterruption() throws InterruptedException {
            CountDownLatch loaderStarted = new CountDownLatch(1);
            when(loader.get()).thenAnswer(invocation -> {
                loaderStarted.countDown();
                Thread.sleep(5000); // Block until interrupted
                return null;
            });

            Thread testThread = new Thread(() -> {
                assertThatThrownBy(() -> cacheService.getOrLoad(KEY, TYPE_REF, loader))
                        .isInstanceOf(CacheLoadingException.class)
                        .hasCauseInstanceOf(InterruptedException.class);

                assertThat(Thread.currentThread().isInterrupted()).isTrue();
            });

            testThread.start();
            loaderStarted.await(2, TimeUnit.SECONDS);
            testThread.interrupt();
            testThread.join(2000);
        }
    }
}