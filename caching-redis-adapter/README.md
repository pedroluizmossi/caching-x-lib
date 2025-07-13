# Caching-Redis-Adapter Module

Distributed caching for L2 (shared) cache layer with automatic invalidation across application instances.

## Features

- **Distributed Caching**: Shared cache across multiple application instances
- **Automatic Invalidation**: Redis pub/sub for cache coherence
- **TTL Support**: Configurable time-to-live for all entries
- **Type-Safe JSON Serialization**: Automatic object serialization/deserialization with ObjectMapper
- **Type Conversion**: Uses Jackson ObjectMapper for proper type conversion from Redis responses

## Configuration

```yaml
spring:
  redis:
    host: localhost
    port: 6379
    
caching:
  l2:
    enabled: true
    ttl: PT30M
    invalidationTopic: "cache:invalidation"
```

## Type Safety and Serialization

The Redis adapter uses Jackson's ObjectMapper to ensure type-safe deserialization:

- **Serialization**: Objects are stored as JSON in Redis
- **Deserialization**: Uses `ParameterizedTypeReference` for proper type conversion
- **Generic Support**: Handles complex types like `List<User>`, `Map<String, Object>`, etc.
- **Error Handling**: Graceful fallback when conversion fails

```java
// Example: The adapter properly converts Redis LinkedHashMap back to your type
User user = l2Cache.get("user:123", new ParameterizedTypeReference<User>(){});
List<Product> products = l2Cache.get("products", new ParameterizedTypeReference<List<Product>>(){});
```

## Constructor Parameters

The RedisCacheAdapter requires the following parameters:

```java
public RedisCacheAdapter(
    RedisTemplate<String, Object> redisTemplate,
    String invalidationTopic,
    Duration ttl,
    ObjectMapper objectMapper  // Required for type conversion
)
```

## Invalidation Flow

1. **Local Invalidation**: `evict(key)` called on any instance
2. **Redis Removal**: Key deleted from Redis cache
3. **Event Publication**: Invalidation message published to topic
4. **Distributed Notification**: All instances receive the message
5. **L1 Cleanup**: Each instance evicts the key from local L1 cache

## Usage

```java
// Custom RedisTemplate (if needed)
@Bean
public RedisTemplate<String, Object> cacheRedisTemplate(
        RedisConnectionFactory connectionFactory) {
    RedisTemplate<String, Object> template = new RedisTemplate<>();
    template.setConnectionFactory(connectionFactory);
    // Custom serialization configuration
    return template;
}
```

## Error Handling

The adapter implements graceful degradation:
- **Redis Unavailable**: Returns null, doesn't break application
- **Serialization Errors**: Logged and handled gracefully
- **Network Timeouts**: Configurable timeout handling

## Best Practices

1. **TTL Configuration**: Set appropriate TTL values for your data
2. **Connection Pooling**: Configure Redis connection pools properly
3. **Monitoring**: Monitor Redis memory usage and performance
4. **Error Handling**: Always handle Redis failures gracefully
