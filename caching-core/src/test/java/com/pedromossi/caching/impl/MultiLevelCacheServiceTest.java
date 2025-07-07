package com.pedromossi.caching.impl;

import com.pedromossi.caching.CacheProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
        ParameterizedTypeReference<String> typeRef = new ParameterizedTypeReference<String>() {};

        when(l1Cache.get(eq(key), eq(typeRef))).thenReturn(expectedValue);

        // When
        String actualValue = cacheService.getOrLoad(key, typeRef, stringLoader);

        // Then
        assertThat(actualValue).isEqualTo(expectedValue);
        // Verifica que apenas o L1 foi consultado
        verify(l1Cache).get(eq(key), eq(typeRef));
        verifyNoInteractions(l2Cache, stringLoader); // L2 e o loader não devem ser chamados
    }

    @Test
    void getOrLoad_shouldReturnFromL2AndPromoteToL1_whenL2Hit() throws InterruptedException {
        // Given: O valor não existe no L1, mas existe no L2
        String key = "test:key";
        String expectedValue = "value_from_l2";
        ParameterizedTypeReference<String> typeRef = new ParameterizedTypeReference<String>() {};

        when(l1Cache.get(eq(key), eq(typeRef))).thenReturn(null);
        when(l2Cache.get(eq(key), eq(typeRef))).thenReturn(expectedValue);

        // When
        String actualValue = cacheService.getOrLoad(key, typeRef, stringLoader);

        // Then
        assertThat(actualValue).isEqualTo(expectedValue);
        // Verifica a sequência de chamadas
        verify(l1Cache).get(eq(key), eq(typeRef));
        verify(l2Cache).get(eq(key), eq(typeRef));
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
        ParameterizedTypeReference<String> typeRef = new ParameterizedTypeReference<String>() {};

        when(l1Cache.get(eq(key), eq(typeRef))).thenReturn(null);
        when(l2Cache.get(eq(key), eq(typeRef))).thenReturn(null);
        when(stringLoader.get()).thenReturn(expectedValue);

        // When
        String actualValue = cacheService.getOrLoad(key, typeRef, stringLoader);

        // Then
        assertThat(actualValue).isEqualTo(expectedValue);
        // Verifica a sequência
        verify(l1Cache).get(eq(key), eq(typeRef));
        verify(l2Cache).get(eq(key), eq(typeRef));
        verify(stringLoader).get(); // O loader DEVE ser chamado

        // Wait for async storage to complete
        Thread.sleep(100);
        verify(l2Cache).put(key, expectedValue); // Armazena no L2
        verify(l1Cache).put(key, expectedValue); // Armazena no L1
    }

    @Test
    void getOrLoad_shouldNotStoreInCache_whenLoaderReturnsNull() throws InterruptedException {
        // Given: O valor não existe em nenhum cache e o loader retorna null
        String key = "test:key";
        ParameterizedTypeReference<String> typeRef = new ParameterizedTypeReference<String>() {};

        when(l1Cache.get(eq(key), eq(typeRef))).thenReturn(null);
        when(l2Cache.get(eq(key), eq(typeRef))).thenReturn(null);
        when(stringLoader.get()).thenReturn(null);

        // When
        String actualValue = cacheService.getOrLoad(key, typeRef, stringLoader);

        // Then
        assertThat(actualValue).isNull();

        // Wait to ensure no async operations are triggered
        Thread.sleep(100);
        // Verifica que os métodos 'put' não foram chamados
        verify(l1Cache, never()).put(anyString(), any());
        verify(l2Cache, never()).put(anyString(), any());
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

    @Test
    void invalidate_shouldOnlyEvictL1_whenL2IsNotPresent() throws InterruptedException {
        // Given: Serviço configurado apenas com L1
        cacheService = new MultiLevelCacheService(Optional.of(l1Cache), Optional.empty(), executorService);
        String key = "test:key:to:invalidate";

        // When
        cacheService.invalidate(key);

        // Wait for async invalidation to complete
        Thread.sleep(100);

        // Then
        verify(l1Cache).evict(key);
        verifyNoInteractions(l2Cache);
    }
}