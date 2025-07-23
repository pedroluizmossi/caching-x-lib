package com.pedromossi.caching.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.pedromossi.caching.serializer.CacheSerializer;
import com.pedromossi.caching.serializer.SerializationException;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.data.redis.connection.RedisStringCommands;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.types.Expiration;
import org.springframework.data.redis.serializer.RedisSerializer;

@ExtendWith(MockitoExtension.class)
@DisplayName("RedisCacheAdapter")
class RedisCacheAdapterTest {

    private static final String TOPIC = "test-topic";
    private static final Duration TTL = Duration.ofMinutes(30);
    private static final String KEY = "user:1";
    private static final String VALUE = "John Doe";
    private static final byte[] SERIALIZED_VALUE = "{\"name\":\"John Doe\"}".getBytes();
    private static final ParameterizedTypeReference<String> TYPE_REF = new ParameterizedTypeReference<>() {};

    @Mock private RedisTemplate<String, byte[]> redisTemplate;
    @Mock private ValueOperations<String, byte[]> valueOperations;
    @Mock private CacheSerializer cacheSerializer;

    private RedisCacheAdapter redisCacheAdapter;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        redisCacheAdapter = new RedisCacheAdapter(redisTemplate, TOPIC, TTL, cacheSerializer);
    }

    @Nested
    @DisplayName("Single Key Operations")
    class SingleKey {
        @Test
        @DisplayName("get() should retrieve and deserialize value on hit")
        void get_shouldRetrieveAndDeserialize() {
            when(valueOperations.get(KEY)).thenReturn(SERIALIZED_VALUE);
            when(cacheSerializer.deserialize(SERIALIZED_VALUE, TYPE_REF)).thenReturn(VALUE);
            assertThat(redisCacheAdapter.get(KEY, TYPE_REF)).isEqualTo(VALUE);
        }

        @Test
        @DisplayName("put() should serialize and store value with TTL")
        void put_shouldSerializeAndStore() {
            when(cacheSerializer.serialize(VALUE)).thenReturn(SERIALIZED_VALUE);
            redisCacheAdapter.put(KEY, VALUE);
            verify(valueOperations).set(KEY, SERIALIZED_VALUE, TTL);
        }

        @Test
        @DisplayName("evict() should delete key and publish invalidation")
        void evict_shouldDeleteAndPublish() {
            redisCacheAdapter.evict(KEY);
            verify(redisTemplate).delete(KEY);
            verify(redisTemplate).convertAndSend(TOPIC, KEY);
        }
    }

    @Nested
    @DisplayName("Batch Operations")
    class Batch {
        @Test
        @DisplayName("getAll() should fetch and deserialize multiple values")
        void getAll_shouldFetchAndDeserialize() {
            Set<String> keys = new LinkedHashSet<>(Arrays.asList("k1", "k2"));
            byte[] data1 = "v1".getBytes();
            byte[] data2 = "v2".getBytes();
            when(valueOperations.multiGet(any(List.class))).thenReturn(List.of(data1, data2));
            when(cacheSerializer.deserialize(data1, TYPE_REF)).thenReturn("V1");
            when(cacheSerializer.deserialize(data2, TYPE_REF)).thenReturn("V2");

            Map<String, String> result = redisCacheAdapter.getAll(keys, TYPE_REF);

            assertThat(result).containsEntry("k1", "V1").containsEntry("k2", "V2");
        }

        @Test
        @DisplayName("putAll() should serialize and store multiple items via pipeline")
        void putAll_shouldSerializeAndStore() {
            Map<String, Object> items = Map.of("k1", "v1", "k2", 123);
            when(cacheSerializer.serialize(any())).thenReturn(SERIALIZED_VALUE);
            redisCacheAdapter.putAll(items);
            verify(redisTemplate).executePipelined(any(RedisCallback.class));
            verify(cacheSerializer, times(2)).serialize(any());
        }

        @Test
        @DisplayName("evictAll() should delete keys and publish individual messages")
        void evictAll_shouldDeleteAndPublish() {
            Set<String> keys = Set.of("k1", "k2");
            redisCacheAdapter.evictAll(keys);
            verify(redisTemplate).delete(keys);
            verify(redisTemplate, times(2)).convertAndSend(eq(TOPIC), anyString());
        }
    }

    @Nested
    @DisplayName("Error and Edge Case Handling")
    class ErrorAndEdgeCases {
        @Test
        @DisplayName("should handle null or empty inputs gracefully")
        void shouldHandleNullOrEmptyInputs() {
            assertThat(redisCacheAdapter.getAll(null, TYPE_REF)).isEmpty();
            assertThat(redisCacheAdapter.getAll(Set.of(), TYPE_REF)).isEmpty();
            redisCacheAdapter.putAll(null);
            redisCacheAdapter.putAll(Map.of());
            redisCacheAdapter.evictAll(null);
            redisCacheAdapter.evictAll(Set.of());
            verifyNoInteractions(valueOperations, cacheSerializer);
            verify(redisTemplate, never()).delete(anyCollection());
            verify(redisTemplate, never()).executePipelined(any(RedisCallback.class));
        }

        @Test
        @DisplayName("get() should return null on Redis or Deserialization failure")
        void get_shouldReturnNullOnFailure() {
            when(valueOperations.get("redis-fail")).thenThrow(new RuntimeException("Redis error"));
            assertThat(redisCacheAdapter.get("redis-fail", TYPE_REF)).isNull();

            when(valueOperations.get("serializer-fail")).thenReturn(SERIALIZED_VALUE);
            when(cacheSerializer.deserialize(SERIALIZED_VALUE, TYPE_REF)).thenThrow(new SerializationException("err", null));
            assertThat(redisCacheAdapter.get("serializer-fail", TYPE_REF)).isNull();
        }

        @Test
        @DisplayName("write operations should not propagate exceptions")
        void writeOperations_shouldNotPropagateExceptions() {
            when(cacheSerializer.serialize(any())).thenThrow(new SerializationException("err", null));
            redisCacheAdapter.put(KEY, VALUE); // Should not throw

            doThrow(new RuntimeException("Redis error")).when(redisTemplate).delete(anyString());
            doThrow(new RuntimeException("Redis error")).when(redisTemplate).convertAndSend(anyString(), anyString());
            redisCacheAdapter.evict(KEY); // Should not throw

            assertThat(redisCacheAdapter.get(KEY, TYPE_REF)).isNull();
        }

        @Test
        @DisplayName("getAll() should handle individual deserialization errors gracefully")
        void getAll_shouldHandlePartialFailures() {
            Set<String> keys = new LinkedHashSet<>(Arrays.asList("k1", "k2"));
            byte[] data1 = "v1".getBytes();
            byte[] data2 = "v2-invalid".getBytes();
            when(valueOperations.multiGet(any(List.class))).thenReturn(List.of(data1, data2));
            when(cacheSerializer.deserialize(data1, TYPE_REF)).thenReturn("V1");
            when(cacheSerializer.deserialize(data2, TYPE_REF)).thenThrow(new SerializationException("err", null));

            Map<String, String> result = redisCacheAdapter.getAll(keys, TYPE_REF);

            assertThat(result).hasSize(1).containsEntry("k1", "V1");
        }

        @Test
        @DisplayName("evictAll() should attempt all publications even if Redis delete fails")
        void evictAll_shouldAttemptAllPublicationsOnDeleteFailure() {
            Set<String> keys = Set.of("k1", "k2");
            doThrow(new RuntimeException("Redis DEL failed")).when(redisTemplate).delete(keys);
            redisCacheAdapter.evictAll(keys);
            verify(redisTemplate, times(2)).convertAndSend(eq(TOPIC), anyString());
        }

        @Test
        @DisplayName("putAll() should use key serializer and skip null keyBytes")
        void putAll_shouldUseKeySerializerAndSkipNullKeyBytes() {
            Map<String, Object> items = Map.of("k1", "v1", "k2", "v2");
            byte[] serializedValue = "value".getBytes();

            // Mock serializer
            when(cacheSerializer.serialize(any())).thenReturn(serializedValue);

            // Mock key serializer
            RedisSerializer keySerializer = mock(RedisSerializer.class);
            when(redisTemplate.getKeySerializer()).thenReturn(keySerializer);
            when(keySerializer.serialize("k1")).thenReturn("k1".getBytes());
            when(keySerializer.serialize("k2")).thenReturn(null); // Simulate null for k2

            // Mock connection and stringCommands
            var connection = mock(org.springframework.data.redis.connection.RedisConnection.class);
            var stringCommands = mock(RedisStringCommands.class);
            when(connection.stringCommands()).thenReturn(stringCommands);

            when(redisTemplate.executePipelined(any(RedisCallback.class))).thenAnswer(invocation -> {
                RedisCallback<?> callback = invocation.getArgument(0);
                callback.doInRedis(connection);
                return null;
            });

            redisCacheAdapter.putAll(items);

            // Verify SET called only for k1
            verify(stringCommands).set(
                    eq("k1".getBytes()),
                    eq(serializedValue),
                    any(Expiration.class),
                    eq(RedisStringCommands.SetOption.UPSERT)
            );
            // k2 should be skipped (no call with null keyBytes)
            verify(stringCommands, never()).set(
                    eq((byte[]) null),
                    any(),
                    any(),
                    any()
            );
        }
    }
}