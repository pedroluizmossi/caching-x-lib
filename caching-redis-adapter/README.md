# Caching-Redis-Adapter Module

The Redis adapter provides distributed caching capabilities for L2 (shared) cache layer with automatic invalidation across application instances.

## Overview

This module implements the `CacheProvider` interface using Redis, providing:

- **Distributed Caching**: Shared cache across multiple application instances
- **Automatic Invalidation**: Redis pub/sub for cache coherence
- **Persistence**: Data survives application restarts
- **Scalability**: Horizontal scaling with Redis clusters

## Features

- **TTL Support**: Configurable time-to-live for all entries
- **Pub/Sub Invalidation**: Distributed cache invalidation events
- **Serialization**: JSON serialization for complex objects
- **Error Resilience**: Graceful degradation when Redis is unavailable
- **Connection Pooling**: Efficient Redis connection management

## Architecture

```
┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐
│  Application A  │    │  Application B  │    │  Application C  │
│     (L1 + L2)   │    │     (L1 + L2)   │    │     (L1 + L2)   │
└─────────────────┘    └─────────────────┘    └─────────────────┘
         │                       │                       │
         └───────────────────────┼───────────────────────┘
                                 │
                    ┌─────────────────┐
                    │      Redis      │
                    │   ┌─────────┐   │
                    │   │  Cache  │   │
                    │   └─────────┘   │
                    │   ┌─────────┐   │
                    │   │ Pub/Sub │   │
                    │   └─────────┘   │
                    └─────────────────┘
```

## Configuration

### Basic Configuration
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

### Advanced Configuration
```yaml
spring:
  redis:
    host: redis-cluster.example.com
    port: 6379
    password: ${REDIS_PASSWORD}
    timeout: 2000ms
    lettuce:
      pool:
        max-active: 8
        max-idle: 8
        min-idle: 0

caching:
  l2:
    enabled: true
    ttl: PT1H
    invalidationTopic: "myapp:cache:invalidation"
```

## Implementation Details

### RedisCacheAdapter

```java
public class RedisCacheAdapter implements CacheProvider {
    private final RedisTemplate<String, Object> redisTemplate;
    private final String invalidationTopic;
    private final Duration ttl;
}
```

**Key Features:**
- **JSON Serialization**: Uses Jackson for object serialization
- **TTL Management**: Automatic expiration of cache entries
- **Pub/Sub Integration**: Publishes invalidation events
- **Error Handling**: Resilient to Redis connection issues

### Invalidation Flow

1. **Local Invalidation**: `evict(key)` called on any instance
2. **Redis Removal**: Key deleted from Redis cache
3. **Event Publication**: Invalidation message published to topic
4. **Distributed Notification**: All instances receive the message
5. **L1 Cleanup**: Each instance evicts the key from local L1 cache

## Usage Examples

### Direct Usage
```java
@Bean
public CacheProvider l2Cache(RedisTemplate<String, Object> redisTemplate) {
    return new RedisCacheAdapter(
        redisTemplate, 
        "cache:invalidation", 
        Duration.ofHours(1)
    );
}
```

### Custom RedisTemplate
```java
@Bean
public RedisTemplate<String, Object> cacheRedisTemplate(
        RedisConnectionFactory connectionFactory) {
    RedisTemplate<String, Object> template = new RedisTemplate<>();
    template.setConnectionFactory(connectionFactory);
    template.setKeySerializer(new StringRedisSerializer());
    template.setValueSerializer(new GenericJackson2JsonRedisSerializer());
    return template;
}
```

### Monitoring Invalidation Events
```java
@Component
public class CacheMonitor {
    
    @EventListener
    public void handleInvalidation(String key) {
        log.info("Cache invalidated: {}", key);
        // Custom monitoring logic
    }
}
```

## Performance Considerations

### Latency
- **Network Overhead**: Redis operations involve network calls
- **Serialization Cost**: JSON serialization/deserialization overhead
- **Connection Pooling**: Reuse connections for better performance

### Scalability
- **Redis Cluster**: Scale horizontally with Redis clustering
- **Connection Limits**: Monitor Redis connection usage
- **Memory Management**: Set appropriate Redis memory policies

## Error Handling

The adapter implements comprehensive error handling:

```java
public <T> T get(String key, Class<T> type) {
    try {
        Object value = redisTemplate.opsForValue().get(key);
        // ... processing
    } catch (Exception e) {
        log.error("Error retrieving from Redis for key: {}", key, e);
        return null; // Graceful degradation
    }
}
```

**Error Scenarios:**
- **Redis Unavailable**: Returns null, doesn't break application
- **Serialization Errors**: Logged and handled gracefully
- **Network Timeouts**: Configurable timeout handling
- **Connection Pool Exhaustion**: Falls back to source loading

## Best Practices

1. **TTL Configuration**: Set appropriate TTL values for your data
2. **Connection Pooling**: Configure Redis connection pools properly
3. **Monitoring**: Monitor Redis memory usage and performance
4. **Invalidation Topics**: Use descriptive topic names per application
5. **Error Handling**: Always handle Redis failures gracefully
6. **Key Patterns**: Use consistent key naming conventions
7. **Serialization**: Ensure your objects are JSON-serializable

## Dependencies

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
```
