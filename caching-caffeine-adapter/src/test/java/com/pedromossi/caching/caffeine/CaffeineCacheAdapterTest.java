package com.pedromossi.caching.caffeine;


import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;

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
        ParameterizedTypeReference<String> typeRef = new ParameterizedTypeReference<String>() {};

        // When
        cacheAdapter.put(key, value);
        String cachedValue = cacheAdapter.get(key, typeRef);

        // Then
        assertThat(cachedValue).isNotNull().isEqualTo(value);
    }

    @Test
    void shouldReturnNullForNonExistentKey() {
        // Given
        ParameterizedTypeReference<String> typeRef = new ParameterizedTypeReference<String>() {};

        // When
        String cachedValue = cacheAdapter.get("non-existent-key", typeRef);

        // Then
        assertThat(cachedValue).isNull();
    }

    @Test
    void shouldReturnNullAfterEvict() {
        // Given
        String key = "user:2";
        String value = "Jane Smith";
        ParameterizedTypeReference<String> typeRef = new ParameterizedTypeReference<String>() {};
        cacheAdapter.put(key, value);

        // When
        cacheAdapter.evict(key);
        String cachedValue = cacheAdapter.get(key, typeRef);

        // Then
        assertThat(cachedValue).isNull();
    }

    @Test
    void shouldReturnNullIfTypeIsIncorrect() {
        // Given
        String key = "user:3";
        Integer value = 123; // Salva um inteiro
        ParameterizedTypeReference<String> stringTypeRef = new ParameterizedTypeReference<String>() {};
        cacheAdapter.put(key, value);

        // When
        // Tenta recuperar como String
        String cachedValue = cacheAdapter.get(key, stringTypeRef);

        // Then
        assertThat(cachedValue).isNull();
    }

    @Test
    void shouldHandleIntegerValues() {
        // Given
        String key = "number:1";
        Integer value = 42;
        ParameterizedTypeReference<Integer> typeRef = new ParameterizedTypeReference<Integer>() {};

        // When
        cacheAdapter.put(key, value);
        Integer cachedValue = cacheAdapter.get(key, typeRef);

        // Then
        assertThat(cachedValue).isNotNull().isEqualTo(value);
    }

    @Test
    void shouldHandleBooleanValues() {
        // Given
        String key = "flag:1";
        Boolean value = true;
        ParameterizedTypeReference<Boolean> typeRef = new ParameterizedTypeReference<Boolean>() {};

        // When
        cacheAdapter.put(key, value);
        Boolean cachedValue = cacheAdapter.get(key, typeRef);

        // Then
        assertThat(cachedValue).isNotNull().isEqualTo(value);
    }

    @Test
    void shouldOverwriteExistingValue() {
        // Given
        String key = "user:4";
        String initialValue = "Initial Value";
        String updatedValue = "Updated Value";
        ParameterizedTypeReference<String> typeRef = new ParameterizedTypeReference<String>() {};

        // When
        cacheAdapter.put(key, initialValue);
        String cachedInitial = cacheAdapter.get(key, typeRef);

        cacheAdapter.put(key, updatedValue);
        String cachedUpdated = cacheAdapter.get(key, typeRef);

        // Then
        assertThat(cachedInitial).isEqualTo(initialValue);
        assertThat(cachedUpdated).isEqualTo(updatedValue);
    }
}