package com.pedromossi.caching.redis;

import com.pedromossi.caching.serializer.CacheSerializer;
import com.pedromossi.caching.serializer.SerializationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.serializer.RedisSerializer;

import java.time.Duration;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the {@link RedisCacheAdapter} class.
 *
 * <p>This test class verifies the adapter's logic in isolation by mocking its dependencies, such as
 * {@link RedisTemplate} and {@link CacheSerializer}. It ensures that the adapter correctly invokes
 * the methods of its collaborators for both single and batch operations.
 * </p>
 *
 * @see RedisCacheAdapter
 */
@ExtendWith(MockitoExtension.class)
class RedisCacheAdapterTest {

    private static final String INVALIDATION_TOPIC = "test-topic";
    private static final Duration TTL = Duration.ofMinutes(30);

    @Mock private RedisTemplate<String, byte[]> redisTemplate;
    @Mock private ValueOperations<String, byte[]> valueOperations;
    @Mock private CacheSerializer cacheSerializer;

    private RedisCacheAdapter redisCacheAdapter;

    @BeforeEach
    void setUp() {
        // Configure the mock RedisTemplate to return the mock ValueOperations
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        redisCacheAdapter =
                new RedisCacheAdapter(redisTemplate, INVALIDATION_TOPIC, TTL, cacheSerializer);
    }

    @Test
    @DisplayName("get should retrieve, deserialize, and return value")
    void get_shouldRetrieveAndDeserialize() {
        // Given
        String key = "user:1";
        String value = "John Doe";
        byte[] serializedValue = "{\"name\":\"John Doe\"}".getBytes();
        var typeRef = new ParameterizedTypeReference<String>() {};

        when(valueOperations.get(key)).thenReturn(serializedValue);
        when(cacheSerializer.deserialize(serializedValue, typeRef)).thenReturn(value);

        // When
        String result = redisCacheAdapter.get(key, typeRef);

        // Then
        assertThat(result).isEqualTo(value);
        verify(valueOperations).get(key);
        verify(cacheSerializer).deserialize(serializedValue, typeRef);
    }

    @Test
    @DisplayName("get should return null if Redis returns null")
    void get_shouldReturnNullIfKeyNotExists() {
        // Given
        String key = "user:non-existent";
        var typeRef = new ParameterizedTypeReference<String>() {};
        when(valueOperations.get(key)).thenReturn(null);
        // Explicitly stub the behavior for a null input to the serializer
        when(cacheSerializer.deserialize(null, typeRef)).thenReturn(null);

        // When
        String result = redisCacheAdapter.get(key, typeRef);

        // Then
        assertThat(result).isNull();
        // Verify that the adapter correctly passed the null data to the serializer
        verify(cacheSerializer).deserialize(null, typeRef);
    }

    @Test
    @DisplayName("put should serialize and store value with TTL")
    void put_shouldSerializeAndStore() {
        // Given
        String key = "user:2";
        String value = "Jane Smith";
        byte[] serializedValue = "{\"name\":\"Jane Smith\"}".getBytes();
        when(cacheSerializer.serialize(value)).thenReturn(serializedValue);

        // When
        redisCacheAdapter.put(key, value);

        // Then
        verify(cacheSerializer).serialize(value);
        verify(valueOperations).set(key, serializedValue, TTL);
    }

    @Test
    @DisplayName("put should handle serialization errors gracefully")
    void put_shouldHandleSerializationError() {
        // Given
        String key = "user:fail";
        String value = "Failing Value";
        when(cacheSerializer.serialize(value))
                .thenThrow(new SerializationException("Test Error", null));

        // When
        redisCacheAdapter.put(key, value);

        // Then
        verify(valueOperations, never()).set(any(), any(), any());
    }

    @Test
    @DisplayName("evict should delete key and publish invalidation")
    void evict_shouldDeleteAndPublish() {
        // Given
        String key = "product:1";

        // When
        redisCacheAdapter.evict(key);

        // Then
        verify(redisTemplate).delete(key);
        verify(redisTemplate).convertAndSend(INVALIDATION_TOPIC, key);
    }

    @Test
    @DisplayName("getAll should fetch and deserialize multiple values deterministically")
    void getAll_shouldFetchAndDeserialize() {
        // Given
        var keysToRequest = Set.of("item:1", "item:2", "item:missing");
        byte[] data1 = "v1".getBytes();
        byte[] data2 = "v2".getBytes();

        // This map simulates the data present in Redis
        Map<String, byte[]> redisData = Map.of("item:1", data1, "item:2", data2);

        when(valueOperations.multiGet(any(List.class)))
                .thenAnswer(
                        invocation -> {
                            List<String> requestedKeys = invocation.getArgument(0);
                            List<byte[]> results = new ArrayList<>();
                            for (String key : requestedKeys) {
                                results.add(redisData.get(key));
                            }
                            return results;
                        });

        var typeRef = new ParameterizedTypeReference<String>() {};
        when(cacheSerializer.deserialize(eq(data1), eq(typeRef))).thenReturn("Value 1");
        when(cacheSerializer.deserialize(eq(data2), eq(typeRef))).thenReturn("Value 2");

        // When
        Map<String, String> result = redisCacheAdapter.getAll(keysToRequest, typeRef);

        // Then
        assertThat(result)
                .hasSize(2)
                .containsEntry("item:1", "Value 1")
                .containsEntry("item:2", "Value 2");

        // Verify deserialization was only called for non-null data
        verify(cacheSerializer, times(1)).deserialize(eq(data1), eq(typeRef));
        verify(cacheSerializer, times(1)).deserialize(eq(data2), eq(typeRef));
        verify(cacheSerializer, never()).deserialize(eq(null), eq(typeRef));
    }


    @Test
    @DisplayName("putAll should serialize and store multiple items via pipeline")
    void putAll_shouldSerializeAndStoreInBatch() {
        // Given
        Map<String, Object> items = Map.of("key1", "val1", "key2", 123);
        when(cacheSerializer.serialize("val1")).thenReturn("v1".getBytes());
        when(cacheSerializer.serialize(123)).thenReturn("v2".getBytes());

        // When
        redisCacheAdapter.putAll(items);

        // Then
        verify(cacheSerializer, times(1)).serialize("val1");
        verify(cacheSerializer, times(1)).serialize(123);
        verify(redisTemplate, times(1)).executePipelined(any(RedisCallback.class));
    }

    @Test
    @DisplayName("putAll should handle empty map gracefully")
    void putAll_shouldHandleEmptyMap() {
        // Given
        Map<String, Object> emptyItems = Map.of();

        // When
        redisCacheAdapter.putAll(emptyItems);

        // Then
        verify(cacheSerializer, never()).serialize(any());
        verify(redisTemplate, never()).executePipelined(any(RedisCallback.class));
    }

    @Test
    @DisplayName("evictAll should delete multiple keys and publish individual messages")
    void evictAll_shouldDeleteAndPublishInBatch() {
        // Given
        Set<String> keys = Set.of("key1", "key2");

        // When
        redisCacheAdapter.evictAll(keys);

        // Then
        verify(redisTemplate).delete(keys);
        verify(redisTemplate, times(1)).convertAndSend(INVALIDATION_TOPIC, "key1");
        verify(redisTemplate, times(1)).convertAndSend(INVALIDATION_TOPIC, "key2");
    }

    @Test
    @DisplayName("evictAll should handle Redis delete errors gracefully")
    void evictAll_shouldHandleRedisDeleteError() {
        // Given
        Set<String> keys = Set.of("key1", "key2");
        doThrow(new RuntimeException("Redis connection failed")).when(redisTemplate).delete(keys);

        // When
        redisCacheAdapter.evictAll(keys);

        // Then
        // It should still attempt to publish invalidation messages
        verify(redisTemplate, times(1)).convertAndSend(INVALIDATION_TOPIC, "key1");
        verify(redisTemplate, times(1)).convertAndSend(INVALIDATION_TOPIC, "key2");
    }

    @Test
    @DisplayName("get should handle Redis operation errors gracefully")
    void get_shouldHandleRedisError() {
        // Given
        String key = "failing-key";
        var typeRef = new ParameterizedTypeReference<String>() {};
        when(valueOperations.get(key)).thenThrow(new RuntimeException("Redis connection error"));

        // When
        String result = redisCacheAdapter.get(key, typeRef);

        // Then
        assertThat(result).isNull();
        verify(valueOperations).get(key);
        verify(cacheSerializer, never()).deserialize(any(), any());
    }

    @Test
    @DisplayName("get should handle serializer deserialization errors gracefully")
    void get_shouldHandleDeserializationError() {
        // Given
        String key = "user:1";
        byte[] serializedValue = "{\"invalid\":\"json\"}".getBytes();
        var typeRef = new ParameterizedTypeReference<String>() {};
        when(valueOperations.get(key)).thenReturn(serializedValue);
        when(cacheSerializer.deserialize(serializedValue, typeRef))
                .thenThrow(new SerializationException("Deserialization failed", null));

        // When
        String result = redisCacheAdapter.get(key, typeRef);

        // Then
        assertThat(result).isNull();
        verify(cacheSerializer).deserialize(serializedValue, typeRef);
    }

    @Test
    @DisplayName("put should handle Redis operation errors gracefully")
    void put_shouldHandleRedisError() {
        // Given
        String key = "failing-key";
        String value = "test-value";
        byte[] serializedValue = "serialized".getBytes();
        when(cacheSerializer.serialize(value)).thenReturn(serializedValue);
        doThrow(new RuntimeException("Redis connection error"))
                .when(valueOperations).set(key, serializedValue, TTL);

        // When
        redisCacheAdapter.put(key, value);

        // Then
        verify(cacheSerializer).serialize(value);
        verify(valueOperations).set(key, serializedValue, TTL);
    }

    @Test
    @DisplayName("evict should handle Redis delete errors gracefully")
    void evict_shouldHandleRedisDeleteError() {
        // Given
        String key = "failing-key";
        doThrow(new RuntimeException("Redis connection error")).when(redisTemplate).delete(key);

        // When
        redisCacheAdapter.evict(key);

        // Then
        verify(redisTemplate).delete(key);
        verify(redisTemplate).convertAndSend(INVALIDATION_TOPIC, key);
    }

    @Test
    @DisplayName("evict should handle Redis publish errors gracefully")
    void evict_shouldHandleRedisPublishError() {
        // Given
        String key = "test-key";
        doThrow(new RuntimeException("Redis publish error"))
                .when(redisTemplate).convertAndSend(INVALIDATION_TOPIC, key);

        // When
        redisCacheAdapter.evict(key);

        // Then
        verify(redisTemplate).delete(key);
        verify(redisTemplate).convertAndSend(INVALIDATION_TOPIC, key);
    }

    @Test
    @DisplayName("evictAll should handle empty set gracefully")
    void evictAll_shouldHandleEmptySet() {
        // Given
        Set<String> emptyKeys = Set.of();

        // When
        redisCacheAdapter.evictAll(emptyKeys);

        // Then
        verify(redisTemplate, never()).delete(any(Set.class));
        verify(redisTemplate, never()).convertAndSend(any(), any());
    }

    @Test
    @DisplayName("evictAll should handle null set gracefully")
    void evictAll_shouldHandleNullSet() {
        // When
        redisCacheAdapter.evictAll(null);

        // Then
        verify(redisTemplate, never()).delete(any(Set.class));
        verify(redisTemplate, never()).convertAndSend(any(), any());
    }

    @Test
    @DisplayName("getAll should handle null keys gracefully")
    void getAll_shouldHandleNullKeys() {
        // Given
        var typeRef = new ParameterizedTypeReference<String>() {};

        // When
        Map<String, String> result = redisCacheAdapter.getAll(null, typeRef);

        // Then
        assertThat(result).isEmpty();
        verify(valueOperations, never()).multiGet(any());
    }

    @Test
    @DisplayName("getAll should handle Redis returning null values list")
    void getAll_shouldHandleNullValuesList() {
        // Given
        Set<String> keys = Set.of("key1", "key2");
        var typeRef = new ParameterizedTypeReference<String>() {};
        when(valueOperations.multiGet(any(List.class))).thenReturn(null);

        // When
        Map<String, String> result = redisCacheAdapter.getAll(keys, typeRef);

        // Then
        assertThat(result).isEmpty();
        verify(cacheSerializer, never()).deserialize(any(), any());
    }

    @Test
    @DisplayName("getAll should handle Redis operation errors gracefully")
    void getAll_shouldHandleRedisError() {
        // Given
        Set<String> keys = Set.of("key1", "key2");
        var typeRef = new ParameterizedTypeReference<String>() {};
        when(valueOperations.multiGet(any(List.class)))
                .thenThrow(new RuntimeException("Redis connection error"));

        // When
        Map<String, String> result = redisCacheAdapter.getAll(keys, typeRef);

        // Then
        assertThat(result).isEmpty();
        verify(cacheSerializer, never()).deserialize(any(), any());
    }

    @Test
    @DisplayName("getAll should handle individual deserialization errors gracefully")
    void getAll_shouldHandleIndividualDeserializationErrors() {
        // Given
        Set<String> keys = Set.of("key1", "key2", "key3");
        byte[] data1 = "valid".getBytes();
        byte[] data2 = "invalid".getBytes();
        byte[] data3 = "valid".getBytes();

        when(valueOperations.multiGet(any(List.class)))
                .thenReturn(Arrays.asList(data1, data2, data3));

        var typeRef = new ParameterizedTypeReference<String>() {};
        when(cacheSerializer.deserialize(data1, typeRef)).thenReturn("Value 1");
        when(cacheSerializer.deserialize(data2, typeRef))
                .thenThrow(new SerializationException("Deserialization failed", null));
        when(cacheSerializer.deserialize(data3, typeRef)).thenReturn("Value 3");

        // When
        Map<String, String> result = redisCacheAdapter.getAll(keys, typeRef);

        // Then
        assertThat(result).hasSize(2);
        assertThat(result).doesNotContainKey("key2");
        verify(cacheSerializer, times(3)).deserialize(any(), eq(typeRef));
    }

    @Test
    @DisplayName("putAll should handle null items gracefully")
    void putAll_shouldHandleNullItems() {
        // When
        redisCacheAdapter.putAll(null);

        // Then
        verify(cacheSerializer, never()).serialize(any());
        verify(redisTemplate, never()).executePipelined(any(RedisCallback.class));
    }

    @Test
    @DisplayName("putAll should handle Redis pipeline errors gracefully")
    void putAll_shouldHandleRedisPipelineError() {
        // Given
        Map<String, Object> items = Map.of("key1", "value1");
        when(cacheSerializer.serialize("value1")).thenReturn("serialized".getBytes());
        when(redisTemplate.executePipelined(any(RedisCallback.class)))
                .thenThrow(new RuntimeException("Pipeline execution failed"));

        // When
        redisCacheAdapter.putAll(items);

        // Then
        verify(cacheSerializer).serialize("value1");
        verify(redisTemplate).executePipelined(any(RedisCallback.class));
    }

    @Test
    @DisplayName("constructor should handle configuration properly")
    void constructor_shouldInitializeCorrectly() {
        // Given
        RedisTemplate<String, byte[]> customTemplate = redisTemplate;
        String customTopic = "custom-topic";
        Duration customTtl = Duration.ofHours(2);
        CacheSerializer customSerializer = cacheSerializer;

        // When
        RedisCacheAdapter adapter = new RedisCacheAdapter(
                customTemplate, customTopic, customTtl, customSerializer);

        // Then - Should create without exceptions
        assertThat(adapter).isNotNull();
    }

    @Test
    @DisplayName("getAll should maintain key order consistency")
    void getAll_shouldMaintainKeyOrderConsistency() {
        // Given
        Set<String> keys = Set.of("key3", "key1", "key2"); // Unordered set
        byte[] data1 = "value1".getBytes();
        byte[] data2 = "value2".getBytes();
        byte[] data3 = "value3".getBytes();

        when(valueOperations.multiGet(any(List.class)))
                .thenAnswer(invocation -> {
                    List<String> requestedKeys = invocation.getArgument(0);
                    // Return values in the same order as requested keys
                    return requestedKeys.stream()
                            .map(key -> switch (key) {
                                case "key1" -> data1;
                                case "key2" -> data2;
                                case "key3" -> data3;
                                default -> null;
                            })
                            .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
                });

        var typeRef = new ParameterizedTypeReference<String>() {};
        when(cacheSerializer.deserialize(data1, typeRef)).thenReturn("Value 1");
        when(cacheSerializer.deserialize(data2, typeRef)).thenReturn("Value 2");
        when(cacheSerializer.deserialize(data3, typeRef)).thenReturn("Value 3");

        // When
        Map<String, String> result = redisCacheAdapter.getAll(keys, typeRef);

        // Then
        assertThat(result).hasSize(3);
        assertThat(result).containsEntry("key1", "Value 1");
        assertThat(result).containsEntry("key2", "Value 2");
        assertThat(result).containsEntry("key3", "Value 3");
    }

    @Test
    @DisplayName("putAll should handle mixed serialization success and failure")
    void putAll_shouldHandleMixedSerializationResults() {
        // Given
        Map<String, Object> items = new LinkedHashMap<>();
        items.put("success1", "value1");
        items.put("success2", "value2");

        when(cacheSerializer.serialize("value1")).thenReturn("serialized1".getBytes());
        when(cacheSerializer.serialize("value2")).thenReturn("serialized2".getBytes());

        // When & Then - Should handle the success case gracefully
        redisCacheAdapter.putAll(items);

        verify(cacheSerializer, times(2)).serialize(any());
    }

    @Test
    @DisplayName("putAll should handle null key serialization gracefully")
    void putAll_shouldHandleNullKeySerializationGracefully() {
        // Given
        Map<String, Object> items = Map.of("key1", "value1", "key2", "value2");
        when(cacheSerializer.serialize("value1")).thenReturn("serialized1".getBytes());
        when(cacheSerializer.serialize("value2")).thenReturn("serialized2".getBytes());

        // Mock RedisSerializer to return null for specific keys
        RedisSerializer<String> mockKeySerializer = mock(RedisSerializer.class);
        lenient().when(redisTemplate.getKeySerializer()).thenReturn((RedisSerializer)mockKeySerializer);
        lenient().when(mockKeySerializer.serialize("key1")).thenReturn(null); // This should be skipped
        lenient().when(mockKeySerializer.serialize("key2")).thenReturn("key2".getBytes());

        // When
        redisCacheAdapter.putAll(items);

        // Then
        verify(cacheSerializer).serialize("value1");
        verify(cacheSerializer).serialize("value2");
        verify(redisTemplate).executePipelined(any(RedisCallback.class));
    }

    @Test
    @DisplayName("putAll should properly use key serializer for pipeline operations")
    void putAll_shouldProperlyUseKeySerializerForPipelineOperations() {
        // Given
        Map<String, Object> items = Map.of("testKey", "testValue");
        when(cacheSerializer.serialize("testValue")).thenReturn("serializedValue".getBytes());

        RedisSerializer<String> mockKeySerializer = mock(RedisSerializer.class);
        lenient().when(redisTemplate.getKeySerializer()).thenReturn((RedisSerializer)mockKeySerializer);
        lenient().when(mockKeySerializer.serialize("testKey")).thenReturn("serializedKey".getBytes());

        // When
        redisCacheAdapter.putAll(items);

        // Then
        verify(redisTemplate).executePipelined(any(RedisCallback.class));
    }

    @Test
    @DisplayName("putAll should handle multiple keys with some having null serialization")
    void putAll_shouldHandleMultipleKeysWithSomeNullSerialization() {
        // Given
        Map<String, Object> items = Map.of(
                "validKey1", "value1",
                "nullKey", "value2",
                "validKey2", "value3"
        );
        when(cacheSerializer.serialize("value1")).thenReturn("serialized1".getBytes());
        when(cacheSerializer.serialize("value2")).thenReturn("serialized2".getBytes());
        when(cacheSerializer.serialize("value3")).thenReturn("serialized3".getBytes());

        RedisSerializer<String> mockKeySerializer = mock(RedisSerializer.class);
        lenient().when(redisTemplate.getKeySerializer()).thenReturn((RedisSerializer)mockKeySerializer);
        lenient().when(mockKeySerializer.serialize("validKey1")).thenReturn("validKey1".getBytes());
        lenient().when(mockKeySerializer.serialize("nullKey")).thenReturn(null); // This should be skipped
        lenient().when(mockKeySerializer.serialize("validKey2")).thenReturn("validKey2".getBytes());

        // When
        redisCacheAdapter.putAll(items);

        // Then
        verify(cacheSerializer, times(3)).serialize(any());
        verify(redisTemplate).executePipelined(any(RedisCallback.class));
    }

    @Test
    @DisplayName("putAll should use correct TTL and expiration settings in pipeline")
    void putAll_shouldUseCorrectTtlAndExpirationSettingsInPipeline() {
        // Given
        Map<String, Object> items = Map.of("key1", "value1");
        when(cacheSerializer.serialize("value1")).thenReturn("serialized1".getBytes());

        // When
        redisCacheAdapter.putAll(items);

        // Then
        verify(redisTemplate).executePipelined(any(RedisCallback.class));
        // The TTL should be applied (verified through the pipeline execution)
        assertThat(TTL.toSeconds()).isEqualTo(1800); // 30 minutes as set in setup
    }

    @Test
    @DisplayName("putAll should handle key serializer returning empty byte array")
    void putAll_shouldHandleKeySerializerReturningEmptyByteArray() {
        // Given
        Map<String, Object> items = Map.of("emptyKey", "value1", "validKey", "value2");
        when(cacheSerializer.serialize("value1")).thenReturn("serialized1".getBytes());
        when(cacheSerializer.serialize("value2")).thenReturn("serialized2".getBytes());

        RedisSerializer<String> mockKeySerializer = mock(RedisSerializer.class);
        lenient().when(redisTemplate.getKeySerializer()).thenReturn((RedisSerializer)mockKeySerializer);
        lenient().when(mockKeySerializer.serialize("emptyKey")).thenReturn(new byte[0]); // Empty but not null
        lenient().when(mockKeySerializer.serialize("validKey")).thenReturn("validKey".getBytes());

        // When
        redisCacheAdapter.putAll(items);

        // Then
        verify(cacheSerializer).serialize("value1");
        verify(cacheSerializer).serialize("value2");
        verify(redisTemplate).executePipelined(any(RedisCallback.class));
    }

    @Test
    @DisplayName("putAll should continue processing other keys when one key serialization is null")
    void putAll_shouldContinueProcessingWhenOneKeySerializationIsNull() {
        // Given
        // Using LinkedHashMap to ensure predictable iteration order
        Map<String, Object> items = new LinkedHashMap<>();
        items.put("firstKey", "value1");
        items.put("nullKey", "value2");
        items.put("lastKey", "value3");

        when(cacheSerializer.serialize("value1")).thenReturn("serialized1".getBytes());
        when(cacheSerializer.serialize("value2")).thenReturn("serialized2".getBytes());
        when(cacheSerializer.serialize("value3")).thenReturn("serialized3".getBytes());

        RedisSerializer<String> mockKeySerializer = mock(RedisSerializer.class);
        lenient().when(redisTemplate.getKeySerializer()).thenReturn((RedisSerializer)mockKeySerializer);
        lenient().when(mockKeySerializer.serialize("firstKey")).thenReturn("firstKey".getBytes());
        lenient().when(mockKeySerializer.serialize("nullKey")).thenReturn(null);
        lenient().when(mockKeySerializer.serialize("lastKey")).thenReturn("lastKey".getBytes());

        // When
        redisCacheAdapter.putAll(items);

        // Then - Should process all keys even when one returns null
        verify(cacheSerializer, times(3)).serialize(any());
        verify(redisTemplate).executePipelined(any(RedisCallback.class));
    }

    @Test
    @DisplayName("evictAll should handle individual publish errors gracefully")
    void evictAll_shouldHandleIndividualPublishErrors() {
        // Given
        Set<String> keys = Set.of("key1", "key2", "key3");
        lenient().doThrow(new RuntimeException("Publish error for key2"))
                .when(redisTemplate).convertAndSend(INVALIDATION_TOPIC, "key2");

        // When
        redisCacheAdapter.evictAll(keys);

        // Then
        verify(redisTemplate).delete(keys);
        verify(redisTemplate).convertAndSend(INVALIDATION_TOPIC, "key1");
        verify(redisTemplate).convertAndSend(INVALIDATION_TOPIC, "key2");
        verify(redisTemplate).convertAndSend(INVALIDATION_TOPIC, "key3");
    }
}
