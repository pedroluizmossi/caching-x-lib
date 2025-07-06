package com.pedromossi.caching.redis;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RedisCacheAdapterTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ValueOperations<String, Object> valueOperations;

    private RedisCacheAdapter redisCacheAdapter;

    private final String invalidationTopic = "test-topic";
    private final Duration ttl = Duration.ofMinutes(30);

    @BeforeEach
    void setUp() {
        // Configura o mock do RedisTemplate para retornar o mock do ValueOperations
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        redisCacheAdapter = new RedisCacheAdapter(redisTemplate, invalidationTopic, ttl);
    }

    @Test
    void shouldCallRedisGet() {
        // Given
        String key = "product:1";
        String expectedValue = "Notebook";
        when(valueOperations.get(key)).thenReturn(expectedValue);

        // When
        String actualValue = redisCacheAdapter.get(key, String.class);

        // Then
        assertThat(actualValue).isEqualTo(expectedValue);
        verify(valueOperations).get(key); // Verifica se o método get foi chamado
    }

    @Test
    void shouldCallRedisSetOnPut() {
        // Given
        String key = "product:2";
        String value = "Mouse";

        // When
        redisCacheAdapter.put(key, value);

        // Then
        // Verifica se o método set foi chamado com a chave, valor e TTL corretos
        verify(valueOperations).set(key, value, ttl);
    }

    @Test
    void shouldCallDeleteAndConvertAndSendOnEvict() {
        // Given
        String key = "product:3";

        // When
        redisCacheAdapter.evict(key);

        // Then
        // Verifica se o delete foi chamado
        verify(redisTemplate).delete(key);
        // Verifica se a mensagem de invalidação foi publicada no tópico correto
        verify(redisTemplate).convertAndSend(invalidationTopic, key);
    }

    @Test
    void getShouldReturnNullAndLogWhenRedisFails() {
        // Given
        String key = "product:4";
        // Simula uma exceção sendo lançada pelo Redis
        when(valueOperations.get(key)).thenThrow(new RuntimeException("Redis connection failed"));

        // When
        String value = redisCacheAdapter.get(key, String.class);

        // Then
        assertThat(value).isNull();
        // Em um cenário real, você poderia também verificar se o log de erro foi chamado
        // usando uma appender de teste de log.
    }
}