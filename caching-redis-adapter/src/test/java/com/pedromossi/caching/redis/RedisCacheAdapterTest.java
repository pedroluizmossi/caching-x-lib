package com.pedromossi.caching.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pedromossi.caching.serializer.CacheSerializer;
import com.pedromossi.caching.serializer.SerializationException;
import java.time.Duration;
import java.util.*;

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
}