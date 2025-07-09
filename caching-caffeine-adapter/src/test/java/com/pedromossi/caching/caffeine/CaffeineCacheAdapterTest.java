package com.pedromossi.caching.caffeine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the {@link CaffeineCacheAdapter} class.
 *
 * <p>This test class verifies the correct behavior of the Caffeine cache adapter
 * implementation, including basic cache operations, type safety, and edge cases.
 * The tests ensure that the adapter properly handles different data types,
 * cache eviction, and type checking mechanisms.</p>
 *
 * <p>Test coverage includes:</p>
 * <ul>
 *   <li>Basic cache operations (put/get)</li>
 *   <li>Cache eviction functionality</li>
 *   <li>Type safety validation</li>
 *   <li>Handling of different data types</li>
 *   <li>Value overwriting behavior</li>
 * </ul>
 *
 * @see CaffeineCacheAdapter
 * @since 1.0.0
 */
class CaffeineCacheAdapterTest {

    /** The cache adapter instance under test */
    private CaffeineCacheAdapter cacheAdapter;

    /**
     * Sets up the test environment before each test method execution.
     *
     * <p>Initializes a new {@link CaffeineCacheAdapter} instance with a simple
     * configuration suitable for testing. The configuration includes:</p>
     * <ul>
     *   <li>Maximum size of 100 entries</li>
     *   <li>Expiration after write of 1 minute</li>
     * </ul>
     */
    @BeforeEach
    void setUp() {
        // Initialize cache with simple specification for testing
        cacheAdapter = new CaffeineCacheAdapter("maximumSize=100,expireAfterWrite=1m");
    }

    /**
     * Tests that a value can be successfully retrieved after being stored in the cache.
     *
     * <p>This test verifies the basic put/get functionality of the cache adapter.
     * It stores a string value and then retrieves it using the correct type reference,
     * ensuring that the cached value matches the original value.</p>
     */
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

    /**
     * Tests that the cache returns null for keys that don't exist.
     *
     * <p>This test ensures that the cache adapter properly handles requests
     * for non-existent keys by returning null instead of throwing exceptions
     * or returning unexpected values.</p>
     */
    @Test
    void shouldReturnNullForNonExistentKey() {
        // Given
        ParameterizedTypeReference<String> typeRef = new ParameterizedTypeReference<String>() {};

        // When
        String cachedValue = cacheAdapter.get("non-existent-key", typeRef);

        // Then
        assertThat(cachedValue).isNull();
    }

    /**
     * Tests that values become unavailable after explicit cache eviction.
     *
     * <p>This test verifies the eviction functionality by storing a value,
     * explicitly evicting it, and then confirming that subsequent retrieval
     * attempts return null.</p>
     */
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

    /**
     * Tests type safety by verifying null return when requesting wrong type.
     *
     * <p>This test ensures that the cache adapter's type safety mechanism
     * works correctly. When a value of one type is stored but retrieved
     * using a different type reference, the adapter should return null
     * to prevent ClassCastException.</p>
     */
    @Test
    void shouldReturnNullIfTypeIsIncorrect() {
        // Given
        String key = "user:3";
        Integer value = 123; // Store an integer
        ParameterizedTypeReference<String> stringTypeRef = new ParameterizedTypeReference<String>() {};
        cacheAdapter.put(key, value);

        // When
        // Attempt to retrieve as String
        String cachedValue = cacheAdapter.get(key, stringTypeRef);

        // Then
        assertThat(cachedValue).isNull();
    }

    /**
     * Tests that the cache properly handles Integer values.
     *
     * <p>This test verifies that the cache adapter can store and retrieve
     * Integer values correctly, ensuring that numeric types are properly
     * handled by the caching mechanism.</p>
     */
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

    /**
     * Tests that the cache properly handles Boolean values.
     *
     * <p>This test verifies that the cache adapter can store and retrieve
     * Boolean values correctly, ensuring that boolean types are properly
     * handled by the caching mechanism.</p>
     */
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

    /**
     * Tests that existing cache values can be overwritten with new values.
     *
     * <p>This test verifies the cache's ability to update existing entries.
     * It stores an initial value, retrieves it to confirm storage, then
     * overwrites it with a new value and verifies the update was successful.</p>
     */
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