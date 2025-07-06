package com.pedromossi.caching.caffeine;


import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CaffeineCacheAdapterTest {

    private CaffeineCacheAdapter cacheAdapter;

    @BeforeEach
    void setUp() {
        // Inicializa o cache com uma especificação simples para os testes
        cacheAdapter = new CaffeineCacheAdapter("maximumSize=100,expireAfterWrite=1m");
    }

    @Test
    void shouldGetValueAfterPut() {
        // Given
        String key = "user:1";
        String value = "John Doe";

        // When
        cacheAdapter.put(key, value);
        String cachedValue = cacheAdapter.get(key, String.class);

        // Then
        assertThat(cachedValue).isNotNull().isEqualTo(value);
    }

    @Test
    void shouldReturnNullForNonExistentKey() {
        // When
        String cachedValue = cacheAdapter.get("non-existent-key", String.class);

        // Then
        assertThat(cachedValue).isNull();
    }

    @Test
    void shouldReturnNullAfterEvict() {
        // Given
        String key = "user:2";
        String value = "Jane Smith";
        cacheAdapter.put(key, value);

        // When
        cacheAdapter.evict(key);
        String cachedValue = cacheAdapter.get(key, String.class);

        // Then
        assertThat(cachedValue).isNull();
    }

    @Test
    void shouldReturnNullIfTypeIsIncorrect() {
        // Given
        String key = "user:3";
        Integer value = 123; // Salva um inteiro
        cacheAdapter.put(key, value);

        // When
        // Tenta recuperar como String
        String cachedValue = cacheAdapter.get(key, String.class);

        // Then
        assertThat(cachedValue).isNull();
    }
}