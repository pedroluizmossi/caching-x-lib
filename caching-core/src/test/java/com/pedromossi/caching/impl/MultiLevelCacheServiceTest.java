package com.pedromossi.caching.impl;

import com.pedromossi.caching.CacheProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.ParameterizedTypeReference;

import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Comprehensive unit tests for the {@link MultiLevelCacheService} class.
 *
 * <p>This test suite validates the multi-level caching behavior, ensuring that
 * the service correctly implements the cache-aside pattern with L1 and L2 cache
 * layers. The tests cover all major caching scenarios including cache hits,
 * misses, promotions, and null value handling.</p>
 *
 * <p><strong>Test Coverage Areas:</strong></p>
 * <ul>
 *   <li><strong>Cache Hit Scenarios:</strong> L1 hits, L2 hits with promotion</li>
 *   <li><strong>Cache Miss Scenarios:</strong> Complete misses with data loading</li>
 *   <li><strong>Null Value Handling:</strong> Sentinel pattern for null caching</li>
 *   <li><strong>Cache Invalidation:</strong> Multi-layer cache eviction</li>
 *   <li><strong>Asynchronous Operations:</strong> Background cache promotion and storage</li>
 * </ul>
 *
 * <p><strong>Testing Strategy:</strong></p>
 * <ul>
 *   <li>Uses Mockito for mocking cache providers and data loaders</li>
 *   <li>Employs single-threaded executor for deterministic async testing</li>
 *   <li>Validates cache operation sequences and interaction patterns</li>
 *   <li>Tests both successful operations and edge cases</li>
 * </ul>
 *
 * <p><strong>Test Environment:</strong></p>
 * <ul>
 *   <li>MockitoExtension for automatic mock initialization</li>
 *   <li>Mocked L1 and L2 cache providers</li>
 *   <li>Controlled executor service for async operation testing</li>
 *   <li>Thread.sleep() for async operation completion (test-specific)</li>
 * </ul>
 *
 * @since 1.1.0
 * @see MultiLevelCacheService
 * @see CacheProvider
 */
@ExtendWith(MockitoExtension.class)
class MultiLevelCacheServiceTest {

    /** Mock L1 cache provider for local caching simulation */
    @Mock
    private CacheProvider l1Cache;

    /** Mock L2 cache provider for distributed caching simulation */
    @Mock
    private CacheProvider l2Cache;

    /** Mock data loader for simulating data source operations */
    @Mock
    private Supplier<String> stringLoader;

    /** Executor service for controlling asynchronous cache operations in tests */
    private ExecutorService executorService;

    /** The cache service instance under test */
    private MultiLevelCacheService cacheService;

    /** Type reference constant used for cache operations */
    private static final ParameterizedTypeReference<Object> OBJECT_TYPE_REF = new ParameterizedTypeReference<>() {};

    /**
     * Sets up the test environment before each test execution.
     *
     * <p>Initializes the multi-level cache service with mocked cache providers
     * and a single-threaded executor. The single-threaded executor ensures that
     * asynchronous operations complete in a predictable order for testing.</p>
     *
     * <p><strong>Test Configuration:</strong></p>
     * <ul>
     *   <li>Both L1 and L2 caches are enabled with mock implementations</li>
     *   <li>Single-threaded executor for deterministic async behavior</li>
     *   <li>Fresh mock state for each test method</li>
     * </ul>
     */
    @BeforeEach
    void setUp() {
        // Use a direct executor for testing to make async operations synchronous
        executorService = Executors.newSingleThreadExecutor();
        // Initialize the service with both caches and executor
        cacheService = new MultiLevelCacheService(Optional.of(l1Cache), Optional.of(l2Cache), executorService);
    }

    /**
     * Tests that values are returned directly from L1 cache when available.
     *
     * <p>This test validates the fastest cache path where data is available
     * in the local L1 cache. The expected behavior is:</p>
     * <ol>
     *   <li>L1 cache is checked and returns the value immediately</li>
     *   <li>L2 cache and data loader are never invoked</li>
     *   <li>No asynchronous operations are triggered</li>
     * </ol>
     *
     * <p><strong>Performance Expectation:</strong> This represents the optimal
     * cache performance scenario with sub-millisecond response times.</p>
     */
    @Test
    void getOrLoad_shouldReturnFromL1Cache_whenL1Hit() {
        // Given: O valor existe no L1
        String key = "test:key";
        String expectedValue = "value_from_l1";
        ParameterizedTypeReference<String> typeRef = new ParameterizedTypeReference<>() {};

        when(l1Cache.get(eq(key), eq(OBJECT_TYPE_REF))).thenReturn(expectedValue);

        // When
        String actualValue = cacheService.getOrLoad(key, typeRef, stringLoader);

        // Then
        assertThat(actualValue).isEqualTo(expectedValue);
        // Verifica que apenas o L1 foi consultado
        verify(l1Cache).get(eq(key), eq(OBJECT_TYPE_REF));
        verifyNoInteractions(l2Cache, stringLoader); // L2 e o loader não devem ser chamados
    }

    /**
     * Tests L2 cache hits with automatic promotion to L1 cache.
     *
     * <p>This test validates the cache promotion behavior when data is found
     * in L2 but not L1. The expected sequence is:</p>
     * <ol>
     *   <li>L1 cache miss (returns null)</li>
     *   <li>L2 cache hit (returns the value)</li>
     *   <li>Value is returned to caller immediately</li>
     *   <li>Value is asynchronously promoted to L1 for future performance</li>
     *   <li>Data loader is never invoked</li>
     * </ol>
     *
     * <p><strong>Performance Impact:</strong> Future requests for the same key
     * will benefit from L1 cache speed due to the promotion.</p>
     *
     * @throws InterruptedException if the test thread is interrupted while waiting
     *                             for async promotion to complete
     */
    @Test
    void getOrLoad_shouldReturnFromL2AndPromoteToL1_whenL2Hit() throws InterruptedException {
        // Given: O valor não existe no L1, mas existe no L2
        String key = "test:key";
        String expectedValue = "value_from_l2";
        ParameterizedTypeReference<String> typeRef = new ParameterizedTypeReference<>() {};

        when(l1Cache.get(eq(key), eq(OBJECT_TYPE_REF))).thenReturn(null);
        when(l2Cache.get(eq(key), eq(OBJECT_TYPE_REF))).thenReturn(expectedValue);

        // When
        String actualValue = cacheService.getOrLoad(key, typeRef, stringLoader);

        // Then
        assertThat(actualValue).isEqualTo(expectedValue);
        // Verifica a sequência de chamadas
        verify(l1Cache).get(eq(key), eq(OBJECT_TYPE_REF));
        verify(l2Cache).get(eq(key), eq(OBJECT_TYPE_REF));
        verifyNoInteractions(stringLoader); // O loader não deve ser chamado

        // Wait for async promotion to complete
        Thread.sleep(100);
        verify(l1Cache).put(key, expectedValue); // Verifica a promoção para L1
    }

    /**
     * Tests complete cache miss scenario with data loading and cache population.
     *
     * <p>This test validates the behavior when data is not found in any cache
     * layer and must be loaded from the original data source. The expected flow is:</p>
     * <ol>
     *   <li>L1 cache miss (returns null)</li>
     *   <li>L2 cache miss (returns null)</li>
     *   <li>Data loader is invoked to fetch from source</li>
     *   <li>Loaded value is returned to caller immediately</li>
     *   <li>Value is asynchronously stored in both L1 and L2 caches</li>
     * </ol>
     *
     * <p><strong>Cache Warming:</strong> This operation populates both cache
     * layers to optimize future requests for the same data.</p>
     *
     * @throws InterruptedException if the test thread is interrupted while waiting
     *                             for async cache storage to complete
     */
    @Test
    void getOrLoad_shouldLoadFromSourceAndStoreInCaches_whenCompleteMiss() throws InterruptedException {
        // Given: O valor não existe em nenhum cache
        String key = "test:key";
        String expectedValue = "value_from_source";
        ParameterizedTypeReference<String> typeRef = new ParameterizedTypeReference<>() {};

        when(l1Cache.get(eq(key), eq(OBJECT_TYPE_REF))).thenReturn(null);
        when(l2Cache.get(eq(key), eq(OBJECT_TYPE_REF))).thenReturn(null);
        when(stringLoader.get()).thenReturn(expectedValue);

        // When
        String actualValue = cacheService.getOrLoad(key, typeRef, stringLoader);

        // Then
        assertThat(actualValue).isEqualTo(expectedValue);
        // Verifica a sequência
        verify(l1Cache).get(eq(key), eq(OBJECT_TYPE_REF));
        verify(l2Cache).get(eq(key), eq(OBJECT_TYPE_REF));
        verify(stringLoader).get(); // O loader DEVE ser chamado

        // Wait for async storage to complete
        Thread.sleep(100);
        verify(l2Cache).put(key, expectedValue); // Armazena no L2
        verify(l1Cache).put(key, expectedValue); // Armazena no L1
    }

    /**
     * Tests null value handling when the data loader returns null.
     *
     * <p>This test validates the sentinel pattern implementation for null values.
     * When a data loader returns null, the cache service should:</p>
     * <ol>
     *   <li>Return null to the caller as expected</li>
     *   <li>Store a sentinel object in cache layers (not the actual null)</li>
     *   <li>Allow future cache hits to distinguish between "not found" and "found but null"</li>
     * </ol>
     *
     * <p><strong>Null Caching Rationale:</strong> Without sentinel objects,
     * null values cannot be cached, leading to repeated expensive data source
     * calls for legitimately null results.</p>
     *
     * @throws InterruptedException if the test thread is interrupted while waiting
     *                             for async cache storage to complete
     */
    @Test
    void getOrLoad_shouldStoreNullValueInCache_whenLoaderReturnsNull() throws InterruptedException {
        // Given: O valor não existe em nenhum cache e o loader retorna null
        String key = "test:key";
        ParameterizedTypeReference<String> typeRef = new ParameterizedTypeReference<>() {};

        when(l1Cache.get(eq(key), eq(OBJECT_TYPE_REF))).thenReturn(null);
        when(l2Cache.get(eq(key), eq(OBJECT_TYPE_REF))).thenReturn(null);
        when(stringLoader.get()).thenReturn(null);

        // When
        String actualValue = cacheService.getOrLoad(key, typeRef, stringLoader);

        // Then
        assertThat(actualValue).isNull();

        // Wait for async storage to complete
        Thread.sleep(100);
        // Verifica que o valor null foi armazenado nos caches (como sentinel)
        verify(l1Cache).put(eq(key), any()); // Should store the NullValue sentinel
        verify(l2Cache).put(eq(key), any()); // Should store the NullValue sentinel
    }

    /**
     * Tests that cached null values are properly returned from L1 cache.
     *
     * <p>This test validates the complete null value caching cycle:</p>
     * <ol>
     *   <li>First request loads null and caches it as sentinel</li>
     *   <li>Second request hits L1 cache with the sentinel</li>
     *   <li>Sentinel is unwrapped back to null for the caller</li>
     *   <li>No additional data source calls are made</li>
     * </ol>
     *
     * <p><strong>Performance Benefit:</strong> Prevents repeated execution of
     * expensive data loader operations for null results.</p>
     *
     * @throws InterruptedException if the test thread is interrupted while waiting
     *                             for async cache operations to complete
     */
    @Test
    void getOrLoad_shouldReturnNullFromL1Cache_whenNullValueCached() throws InterruptedException {
        // Given: O L1 cache contém um valor null (representado pelo sentinel)
        String key = "test:key";
        ParameterizedTypeReference<String> typeRef = new ParameterizedTypeReference<>() {};

        // First, simulate caching a null value by calling the service with a null loader
        when(l1Cache.get(eq(key), eq(OBJECT_TYPE_REF))).thenReturn(null);
        when(l2Cache.get(eq(key), eq(OBJECT_TYPE_REF))).thenReturn(null);
        when(stringLoader.get()).thenReturn(null);

        // Call once to cache the null value
        String firstResult = cacheService.getOrLoad(key, typeRef, stringLoader);
        assertThat(firstResult).isNull();

        // Wait for async storage to complete
        Thread.sleep(100);

        // Capture what was stored in cache (the NullValue sentinel)
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(l1Cache).put(eq(key), captor.capture());
        Object cachedSentinel = captor.getValue();

        // Reset mocks and simulate L1 hit with the actual sentinel
        reset(l1Cache, l2Cache, stringLoader);
        when(l1Cache.get(eq(key), eq(OBJECT_TYPE_REF))).thenReturn(cachedSentinel);

        // When: Second call should hit L1 cache
        String actualValue = cacheService.getOrLoad(key, typeRef, stringLoader);

        // Then
        assertThat(actualValue).isNull();
        verify(l1Cache).get(eq(key), eq(OBJECT_TYPE_REF));
        verifyNoInteractions(l2Cache, stringLoader); // Não deve consultar L2 nem o loader
    }

    /**
     * Tests null value retrieval from L2 cache with promotion to L1.
     *
     * <p>This test validates null value handling in the L2 cache hit scenario:</p>
     * <ol>
     *   <li>Null value is initially cached in both layers</li>
     *   <li>L1 is cleared but L2 retains the null sentinel</li>
     *   <li>Request hits L2 cache and returns null to caller</li>
     *   <li>Null sentinel is promoted back to L1 asynchronously</li>
     * </ol>
     *
     * <p><strong>Consistency:</strong> Ensures null value handling works
     * consistently across all cache layers and promotion scenarios.</p>
     *
     * @throws InterruptedException if the test thread is interrupted while waiting
     *                             for async promotion to complete
     */
    @Test
    void getOrLoad_shouldReturnNullFromL2Cache_andPromoteToL1() throws InterruptedException {
        // Given: First cache a null value to get the actual sentinel
        String key = "test:key";
        ParameterizedTypeReference<String> typeRef = new ParameterizedTypeReference<>() {};

        // First call to cache null value
        when(l1Cache.get(eq(key), eq(OBJECT_TYPE_REF))).thenReturn(null);
        when(l2Cache.get(eq(key), eq(OBJECT_TYPE_REF))).thenReturn(null);
        when(stringLoader.get()).thenReturn(null);

        String firstResult = cacheService.getOrLoad(key, typeRef, stringLoader);
        assertThat(firstResult).isNull();

        // Wait for caching to complete and capture the sentinel
        Thread.sleep(100);
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(l2Cache).put(eq(key), captor.capture());
        Object nullSentinel = captor.getValue();

        // Reset mocks and simulate L2 hit scenario
        reset(l1Cache, l2Cache, stringLoader);
        when(l1Cache.get(eq(key), eq(OBJECT_TYPE_REF))).thenReturn(null);
        when(l2Cache.get(eq(key), eq(OBJECT_TYPE_REF))).thenReturn(nullSentinel);

        // When: Second call should hit L2 cache
        String actualValue = cacheService.getOrLoad(key, typeRef, stringLoader);

        // Then
        assertThat(actualValue).isNull();
        verify(l1Cache).get(eq(key), eq(OBJECT_TYPE_REF));
        verify(l2Cache).get(eq(key), eq(OBJECT_TYPE_REF));
        verifyNoInteractions(stringLoader); // O loader não deve ser chamado

        // Wait for async promotion to complete
        Thread.sleep(100);
        verify(l1Cache).put(key, nullSentinel); // Promove o sentinel para L1
    }

    /**
     * Tests comprehensive null value caching lifecycle.
     *
     * <p>This integration test validates the complete null value handling
     * across multiple cache operations:</p>
     * <ol>
     *   <li>Initial load returns null and caches sentinel</li>
     *   <li>Subsequent request hits cache and returns null efficiently</li>
     *   <li>No additional data source calls are made</li>
     * </ol>
     *
     * <p><strong>Real-world Scenario:</strong> Simulates common scenarios like
     * user bio fields, optional configuration values, or computed results
     * that may legitimately be null.</p>
     *
     * @throws InterruptedException if the test thread is interrupted while waiting
     *                             for async cache operations to complete
     */
    @Test
    void getOrLoad_shouldHandleNullValuesCachedPreviously() throws InterruptedException {
        // Given: Primeiro, carregamos um valor null que será cached
        String key = "bio:456";
        ParameterizedTypeReference<String> typeRef = new ParameterizedTypeReference<>() {};

        when(l1Cache.get(eq(key), eq(OBJECT_TYPE_REF))).thenReturn(null);
        when(l2Cache.get(eq(key), eq(OBJECT_TYPE_REF))).thenReturn(null);
        when(stringLoader.get()).thenReturn(null);

        // First call - loads null and caches it
        String firstResult = cacheService.getOrLoad(key, typeRef, stringLoader);
        assertThat(firstResult).isNull();

        // Wait for caching to complete and capture the sentinel
        Thread.sleep(100);
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(l1Cache).put(eq(key), captor.capture());
        Object cachedSentinel = captor.getValue();

        // Reset mocks and simulate the cached null value
        reset(l1Cache, l2Cache, stringLoader);
        when(l1Cache.get(eq(key), eq(OBJECT_TYPE_REF))).thenReturn(cachedSentinel);

        // When: Second call should hit the cache
        String secondResult = cacheService.getOrLoad(key, typeRef, stringLoader);

        // Then: Should return null from cache without calling loader
        assertThat(secondResult).isNull();
        verify(l1Cache).get(eq(key), eq(OBJECT_TYPE_REF));
        verifyNoInteractions(l2Cache, stringLoader); // Should not call loader again
    }

    /**
     * Creates a mock object that simulates the NullValue sentinel behavior.
     * Since we can't access the private NullValue class directly in tests,
     * we create a similar object for testing purposes.
     *
     * <p><strong>Legacy Method:</strong> This method is no longer used in the
     * updated tests above, but kept for reference. The new tests use the actual
     * sentinel objects captured from cache operations for more realistic testing.</p>
     *
     * <p><strong>Testing Strategy Evolution:</strong> Modern tests capture real
     * sentinel objects through ArgumentCaptor to ensure accurate behavior
     * validation without depending on implementation details.</p>
     *
     * @return a mock object that simulates null sentinel behavior
     * @deprecated This approach has been superseded by capturing actual sentinel
     *             objects from cache operations
     */
    private Object createMockNullSentinel() {
        return new Object() {
            @Override
            public String toString() {
                return "MockNullSentinel";
            }
        };
    }

    /**
     * Tests cache invalidation across both L1 and L2 cache layers.
     *
     * <p>This test validates the distributed cache invalidation behavior:</p>
     * <ol>
     *   <li>Invalidation request is processed asynchronously</li>
     *   <li>Both L1 and L2 caches receive eviction calls</li>
     *   <li>Cache consistency is maintained across layers</li>
     * </ol>
     *
     * <p><strong>Distributed Behavior:</strong> In production, L2 cache
     * invalidation may also trigger pub/sub events to notify other
     * application instances, ensuring system-wide cache consistency.</p>
     *
     * <p><strong>Error Resilience:</strong> The implementation ensures that
     * failure in one cache layer doesn't prevent invalidation of other layers.</p>
     *
     * @throws InterruptedException if the test thread is interrupted while waiting
     *                             for async invalidation to complete
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
        // A correção garante que ambos os caches, L1 e L2, sejam invalidados.
        verify(l1Cache).evict(key);
        verify(l2Cache).evict(key);
    }
}