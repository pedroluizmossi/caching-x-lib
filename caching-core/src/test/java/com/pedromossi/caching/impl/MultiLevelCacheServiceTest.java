package com.pedromossi.caching.impl;

import com.pedromossi.caching.CacheProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
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

    private MultiLevelCacheService cacheService;

    @BeforeEach
    void setUp() {
        // Inicializa o serviço com ambos os caches (L1 e L2)
        cacheService = new MultiLevelCacheService(Optional.of(l1Cache), Optional.of(l2Cache));
    }

    @Test
    void getOrLoad_shouldReturnFromL1Cache_whenL1Hit() {
        // Given: O valor existe no L1
        String key = "test:key";
        String expectedValue = "value_from_l1";
        when(l1Cache.get(key, String.class)).thenReturn(expectedValue);

        // When
        String actualValue = cacheService.getOrLoad(key, String.class, stringLoader);

        // Then
        assertThat(actualValue).isEqualTo(expectedValue);
        // Verifica que apenas o L1 foi consultado
        verify(l1Cache).get(key, String.class);
        verifyNoInteractions(l2Cache, stringLoader); // L2 e o loader não devem ser chamados
    }

    @Test
    void getOrLoad_shouldReturnFromL2AndPromoteToL1_whenL2Hit() {
        // Given: O valor não existe no L1, mas existe no L2
        String key = "test:key";
        String expectedValue = "value_from_l2";
        when(l1Cache.get(key, String.class)).thenReturn(null);
        when(l2Cache.get(key, String.class)).thenReturn(expectedValue);

        // When
        String actualValue = cacheService.getOrLoad(key, String.class, stringLoader);

        // Then
        assertThat(actualValue).isEqualTo(expectedValue);
        // Verifica a sequência de chamadas
        verify(l1Cache).get(key, String.class);
        verify(l2Cache).get(key, String.class);
        verify(l1Cache).put(key, expectedValue); // Verifica a promoção para L1
        verifyNoInteractions(stringLoader); // O loader não deve ser chamado
    }

    @Test
    void getOrLoad_shouldLoadFromSourceAndStoreInCaches_whenCompleteMiss() {
        // Given: O valor não existe em nenhum cache
        String key = "test:key";
        String expectedValue = "value_from_source";
        when(l1Cache.get(key, String.class)).thenReturn(null);
        when(l2Cache.get(key, String.class)).thenReturn(null);
        when(stringLoader.get()).thenReturn(expectedValue);

        // When
        String actualValue = cacheService.getOrLoad(key, String.class, stringLoader);

        // Then
        assertThat(actualValue).isEqualTo(expectedValue);
        // Verifica a sequência
        verify(l1Cache).get(key, String.class);
        verify(l2Cache).get(key, String.class);
        verify(stringLoader).get(); // O loader DEVE ser chamado
        verify(l2Cache).put(key, expectedValue); // Armazena no L2
        verify(l1Cache).put(key, expectedValue); // Armazena no L1
    }

    @Test
    void getOrLoad_shouldNotStoreInCache_whenLoaderReturnsNull() {
        // Given: O valor não existe em nenhum cache e o loader retorna null
        String key = "test:key";
        when(l1Cache.get(key, String.class)).thenReturn(null);
        when(l2Cache.get(key, String.class)).thenReturn(null);
        when(stringLoader.get()).thenReturn(null);

        // When
        String actualValue = cacheService.getOrLoad(key, String.class, stringLoader);

        // Then
        assertThat(actualValue).isNull();
        // Verifica que os métodos 'put' não foram chamados
        verify(l1Cache, never()).put(anyString(), any());
        verify(l2Cache, never()).put(anyString(), any());
    }

    @Test
    void invalidate_shouldEvictFromBothCaches() {
        // Given
        String key = "test:key:to:invalidate";

        // When
        cacheService.invalidate(key);

        // Then
        // A correção garante que ambos os caches, L1 e L2, sejam invalidados.
        verify(l1Cache).evict(key);
        verify(l2Cache).evict(key);
    }

    @Test
    void invalidate_shouldOnlyEvictL1_whenL2IsNotPresent() {
        // Given: Serviço configurado apenas com L1
        cacheService = new MultiLevelCacheService(Optional.of(l1Cache), Optional.empty());
        String key = "test:key:to:invalidate";

        // When
        cacheService.invalidate(key);

        // Then
        verify(l1Cache).evict(key);
        verifyNoInteractions(l2Cache);
    }
}