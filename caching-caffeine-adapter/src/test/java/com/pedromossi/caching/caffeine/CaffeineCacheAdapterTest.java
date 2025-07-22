package com.pedromossi.caching.caffeine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;

/**
 * Unit tests for the {@link CaffeineCacheAdapter} class, verifying basic and batch operations,
 * type safety, and edge cases.
 */
@DisplayName("CaffeineCacheAdapter")
class CaffeineCacheAdapterTest {

    private CaffeineCacheAdapter cacheAdapter;

    @BeforeEach
    void setUp() {
        cacheAdapter = new CaffeineCacheAdapter("maximumSize=100,expireAfterWrite=1m");
    }

    @Nested
    @DisplayName("Single Key Operations")
    class SingleKeyOperations {

        @Test
        @DisplayName("get() should return value when key exists")
        void get_shouldReturnValue_whenKeyExists() {
            cacheAdapter.put("user:1", "John Doe");
            String result = cacheAdapter.get("user:1", new ParameterizedTypeReference<>() {});
            assertThat(result).isEqualTo("John Doe");
        }

        @Test
        @DisplayName("get() should return null when key does not exist")
        void get_shouldReturnNull_whenKeyDoesNotExist() {
            String result = cacheAdapter.get("non-existent", new ParameterizedTypeReference<>() {});
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("get() should return null when requested type is incorrect")
        void get_shouldReturnNull_whenRequestedTypeIsIncorrect() {
            cacheAdapter.put("user:3", 123); // Stored as Integer
            String result = cacheAdapter.get("user:3", new ParameterizedTypeReference<String>() {});
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("get() should return null when using unsupported type reference")
        void get_shouldReturnNull_whenUsingUnsupportedTypeReference() {
            cacheAdapter.put("test:key", "test-value");

            // Criar um tipo de referência personalizado que não é uma classe nem um tipo parametrizado
            ParameterizedTypeReference<?> unsupportedTypeRef = new ParameterizedTypeReference<Object>() {
                @Override
                public java.lang.reflect.Type getType() {
                    // Usar um tipo personalizado que não é Class nem ParameterizedType
                    return new java.lang.reflect.WildcardType() {
                        @Override
                        public java.lang.reflect.Type[] getUpperBounds() {
                            return new java.lang.reflect.Type[0];
                        }

                        @Override
                        public java.lang.reflect.Type[] getLowerBounds() {
                            return new java.lang.reflect.Type[0];
                        }
                    };
                }
            };

            // O método deve retornar null para um tipo não suportado
            Object result = cacheAdapter.get("test:key", unsupportedTypeRef);
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("put() should overwrite an existing value")
        void put_shouldOverwriteExistingValue() {
            String key = "user:4";
            cacheAdapter.put(key, "Initial Value");
            cacheAdapter.put(key, "Updated Value");
            String result = cacheAdapter.get(key, new ParameterizedTypeReference<>() {});
            assertThat(result).isEqualTo("Updated Value");
        }

        @Test
        @DisplayName("evict() should remove a value from the cache")
        void evict_shouldRemoveValueFromCache() {
            String key = "user:2";
            cacheAdapter.put(key, "Jane Smith");
            cacheAdapter.evict(key);
            String result = cacheAdapter.get(key, new ParameterizedTypeReference<>() {});
            assertThat(result).isNull();
        }
    }

    @Nested
    @DisplayName("Batch Operations")
    class BatchOperations {

        @Test
        @DisplayName("putAll() should store all items from a map")
        void putAll_shouldStoreAllItems() {
            Map<String, Object> items = Map.of("p:1", "Laptop", "p:2", "Mouse", "cfg:t", 5000);
            cacheAdapter.putAll(items);
            assertThat(cacheAdapter.get("p:1", new ParameterizedTypeReference<String>() {})).isEqualTo("Laptop");
            assertThat(cacheAdapter.get("cfg:t", new ParameterizedTypeReference<Integer>() {})).isEqualTo(5000);
        }

        @Test
        @DisplayName("getAll() should return found items and filter by type")
        void getAll_shouldReturnFoundItemsAndFilterByTypes() {
            cacheAdapter.put("user:1", "Alice");
            cacheAdapter.put("user:2", "Bob");
            cacheAdapter.put("user:count", 2); // Type mismatch

            Set<String> keys = Set.of("user:1", "user:2", "user:count", "user:nonexistent");
            Map<String, String> result = cacheAdapter.getAll(keys, new ParameterizedTypeReference<>() {});

            assertThat(result)
                    .hasSize(2)
                    .containsEntry("user:1", "Alice")
                    .containsEntry("user:2", "Bob");
        }

        @Test
        @DisplayName("evictAll() should remove all specified keys")
        void evictAll_shouldRemoveSpecifiedKeys() {
            cacheAdapter.put("item:1", "A");
            cacheAdapter.put("item:2", "B");
            cacheAdapter.put("item:3", "C"); // Should remain

            cacheAdapter.evictAll(Set.of("item:1", "item:2", "item:nonexistent"));

            assertThat(cacheAdapter.get("item:1", new ParameterizedTypeReference<String>() {})).isNull();
            assertThat(cacheAdapter.get("item:2", new ParameterizedTypeReference<String>() {})).isNull();
            assertThat(cacheAdapter.get("item:3", new ParameterizedTypeReference<String>() {})).isEqualTo("C");
        }
    }

    @Nested
    @DisplayName("Advanced Features")
    class AdvancedFeatures {

        @Test
        @DisplayName("getNativeCache() should return the underlying Caffeine cache")
        void getNativeCache_shouldReturnUnderlyingCache() {
            assertThat(cacheAdapter.getNativeCache()).isNotNull();
            cacheAdapter.put("testKey", "testValue");
            assertThat(cacheAdapter.getNativeCache().getIfPresent("testKey")).isEqualTo("testValue");
        }
    }

    @Nested
    @DisplayName("Type Handling")
    class TypeHandling {

        @Test
        @DisplayName("should handle different simple types like Integer and Boolean")
        void shouldHandleDifferentSimpleTypes() {
            // Integer
            cacheAdapter.put("number:1", 42);
            Integer numResult = cacheAdapter.get("number:1", new ParameterizedTypeReference<>() {});
            assertThat(numResult).isEqualTo(42);

            // Boolean
            cacheAdapter.put("flag:1", true);
            Boolean boolResult = cacheAdapter.get("flag:1", new ParameterizedTypeReference<>() {});
            assertThat(boolResult).isTrue();
        }

        @Test
        @DisplayName("should handle complex generic types like List<String>")
        void shouldHandleComplexGenericTypes() {
            var list1 = List.of("a", "b");
            cacheAdapter.put("list:1", list1);

            var typeRef = new ParameterizedTypeReference<List<String>>() {};
            List<String> result = cacheAdapter.get("list:1", typeRef);

            assertThat(result).isEqualTo(list1);
        }

        @Test
        @DisplayName("getAll() should handle complex generic types")
        void getAll_shouldHandleComplexGenericTypes() {
            var list1 = List.of("a", "b");
            var list2 = List.of("c", "d");
            cacheAdapter.put("list:1", list1);
            cacheAdapter.put("list:2", list2);

            var typeRef = new ParameterizedTypeReference<List<String>>() {};
            Map<String, List<String>> result = cacheAdapter.getAll(Set.of("list:1", "list:2"), typeRef);

            assertThat(result)
                    .hasSize(2)
                    .containsEntry("list:1", list1)
                    .containsEntry("list:2", list2);
        }

        @Test
        @DisplayName("getAll() should return empty map when using unsupported type reference")
        void getAll_shouldReturnEmptyMap_whenUsingUnsupportedTypeReference() {
            // Adicionar alguns valores ao cache
            cacheAdapter.put("key1", "value1");
            cacheAdapter.put("key2", "value2");

            // Criar um tipo de referência personalizado que não é uma classe nem um tipo parametrizado
            ParameterizedTypeReference<?> unsupportedTypeRef = new ParameterizedTypeReference<Object>() {
                @Override
                public java.lang.reflect.Type getType() {
                    // Usar um tipo personalizado que não é Class nem ParameterizedType
                    return new java.lang.reflect.WildcardType() {
                        @Override
                        public java.lang.reflect.Type[] getUpperBounds() {
                            return new java.lang.reflect.Type[0];
                        }

                        @Override
                        public java.lang.reflect.Type[] getLowerBounds() {
                            return new java.lang.reflect.Type[0];
                        }
                    };
                }
            };

            // O método deve retornar um mapa vazio para um tipo não suportado
            Map<?, ?> result = cacheAdapter.getAll(Set.of("key1", "key2"), unsupportedTypeRef);
            assertThat(result).isEmpty();
        }
    }
}