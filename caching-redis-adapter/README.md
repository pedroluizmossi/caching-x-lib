# Caching-Redis-Adapter Module

Distributed caching for L2 (shared) cache layer with automatic invalidation across application instances.

## Features

- **Distributed Caching**: Shared cache across multiple application instances
- **Automatic Invalidation**: Redis pub/sub for cache coherence
- **TTL Support**: Configurable time-to-live for all entries
- **JSON Serialization**: Automatic object serialization/deserialization

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
