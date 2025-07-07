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

    private static final ParameterizedTypeReference<Object> OBJECT_TYPE_REF = new ParameterizedTypeReference<>() {};

    @BeforeEach
    void setUp() {
        // Use a direct executor for testing to make async operations synchronous
        executorService = Executors.newSingleThreadExecutor();
        // Initialize the service with both caches and executor
        cacheService = new MultiLevelCacheService(Optional.of(l1Cache), Optional.of(l2Cache), executorService);
    }

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
     * NOTE: This method is no longer used in the updated tests above,
     * but kept for reference. The new tests use the actual sentinel objects
     * captured from cache operations.
     */
    private Object createMockNullSentinel() {
        return new Object() {
            @Override
            public String toString() {
                return "MockNullSentinel";
            }
        };
    }

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
