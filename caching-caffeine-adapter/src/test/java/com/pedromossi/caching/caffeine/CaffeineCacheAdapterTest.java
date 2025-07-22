package com.pedromossi.caching.caffeine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the {@link CaffeineCacheAdapter} class.
 *
 * <p>This test class verifies the correct behavior of the Caffeine cache adapter implementation,
 * including basic and batch cache operations, type safety, and edge cases. The tests ensure that
 * the adapter properly handles different data types, cache eviction, and type checking mechanisms.
 * </p>
 *
 * <p>Test coverage includes:</p>
 * <ul>
 *   <li>Basic cache operations (put/get)</li>
 *   <li>Batch cache operations (putAll/getAll/evictAll)</li>
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

    /** The cache adapter instance under test. */
    private CaffeineCacheAdapter cacheAdapter;

    /**
     * Sets up the test environment before each test method execution.
     *
     * <p>Initializes a new {@link CaffeineCacheAdapter} instance with a simple configuration suitable
     * for testing. The configuration includes:
     * </p>
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
     * <p>This test verifies the basic put/get functionality of the cache adapter. It stores a string
     * value and then retrieves it using the correct type reference, ensuring that the cached value
     * matches the original value.
     * </p>
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
     * <p>This test ensures that the cache adapter properly handles requests for non-existent keys by
     * returning null instead of throwing exceptions or returning unexpected values.
     * </p>
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
     * <p>This test verifies the eviction functionality by storing a value, explicitly evicting it,
     * and then confirming that subsequent retrieval attempts return null.
     * </p>
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
     * <p>This test ensures that the cache adapter's type safety mechanism works correctly. When a
     * value of one type is stored but retrieved using a different type reference, the adapter should
     * return null to prevent ClassCastException.
     * </p>
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
     * <p>This test verifies that the cache adapter can store and retrieve Integer values correctly,
     * ensuring that numeric types are properly handled by the caching mechanism.
     * </p>
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
     * <p>This test verifies that the cache adapter can store and retrieve Boolean values correctly,
     * ensuring that boolean types are properly handled by the caching mechanism.
     * </p>
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
     * <p>This test verifies the cache's ability to update existing entries. It stores an initial
     * value, retrieves it to confirm storage, then overwrites it with a new value and verifies the
     * update was successful.
     * </p>
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

    // --- Batch Operation Tests ---

    @Test
    @DisplayName("shouldPutAllItems")
    void shouldPutAllItems() {
        // Given
        Map<String, Object> items =
                Map.of(
                        "product:1", "Laptop", "product:2", "Mouse", "config:timeout", 5000);
        var stringTypeRef = new ParameterizedTypeReference<String>() {};
        var intTypeRef = new ParameterizedTypeReference<Integer>() {};

        // When
        cacheAdapter.putAll(items);

        // Then
        assertThat(cacheAdapter.get("product:1", stringTypeRef)).isEqualTo("Laptop");
        assertThat(cacheAdapter.get("product:2", stringTypeRef)).isEqualTo("Mouse");
        assertThat(cacheAdapter.get("config:timeout", intTypeRef)).isEqualTo(5000);
    }

    @Test
    @DisplayName("shouldGetAllPresentValuesAndFilterTypeMismatches")
    void shouldGetAllPresentValuesAndFilterTypeMismatches() {
        // Given
        cacheAdapter.put("user:1", "Alice");
        cacheAdapter.put("user:2", "Bob");
        cacheAdapter.put("user:count", 2); // This one has a different type
        Set<String> keysToFetch = Set.of("user:1", "user:2", "user:count", "user:nonexistent");
        var stringTypeRef = new ParameterizedTypeReference<String>() {};

        // When
        Map<String, String> foundItems = cacheAdapter.getAll(keysToFetch, stringTypeRef);

        // Then
        assertThat(foundItems)
                .hasSize(2)
                .containsEntry("user:1", "Alice")
                .containsEntry("user:2", "Bob")
                .doesNotContainKey("user:count") // Filtered due to type mismatch
                .doesNotContainKey("user:nonexistent"); // Not present
    }

    @Test
    @DisplayName("shouldEvictAllSpecifiedKeys")
    void shouldEvictAllSpecifiedKeys() {
        // Given
        cacheAdapter.put("item:1", "A");
        cacheAdapter.put("item:2", "B");
        cacheAdapter.put("item:3", "C"); // This one will remain
        Set<String> keysToEvict = Set.of("item:1", "item:2", "item:nonexistent");
        var typeRef = new ParameterizedTypeReference<String>() {};

        // When
        cacheAdapter.evictAll(keysToEvict);

        // Then
        assertThat(cacheAdapter.get("item:1", typeRef)).isNull();
        assertThat(cacheAdapter.get("item:2", typeRef)).isNull();
        assertThat(cacheAdapter.get("item:3", typeRef)).isEqualTo("C");
    }

    @Test
    @DisplayName("getAll should handle complex generic types")
    void getAllShouldHandleComplexGenericTypes() {
        // Given
        var list1 = List.of("a", "b");
        var list2 = List.of("c", "d");
        cacheAdapter.put("list:1", list1);
        cacheAdapter.put("list:2", list2);
        Set<String> keys = Set.of("list:1", "list:2");
        var typeRef = new ParameterizedTypeReference<List<String>>() {};

        // When
        Map<String, List<String>> result = cacheAdapter.getAll(keys, typeRef);

        // Then
        assertThat(result)
                .hasSize(2)
                .containsEntry("list:1", list1)
                .containsEntry("list:2", list2);
    }
}