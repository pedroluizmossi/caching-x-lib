# Caching-X Library

A powerful and flexible Java caching library that provides multi-level caching capabilities with adapters for Caffeine (L1) and Redis (L2) using read-through strategies.

## Overview

Caching-X is designed to provide high-performance caching solutions for Java applications, particularly those built with Spring Boot. It implements a multi-level cache architecture where:

- **L1 Cache (Caffeine)**: Fast in-memory cache for frequently accessed data
- **L2 Cache (Redis)**: Distributed cache shared across application instances
- **Read-through Strategy**: Automatic data loading from the source when cache misses occur
- **Cache Invalidation**: Distributed invalidation across all application instances

## Architecture

```
┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐
│   Application   │───▶│  CacheService   │───▶│   Data Source   │
└─────────────────┘    └─────────────────┘    └─────────────────┘
                               │
                               ▼
                    ┌─────────────────┐
                    │ MultiLevelCache │
                    └─────────────────┘
                               │
                    ┌──────────┴──────────┐
                    ▼                     ▼
            ┌──────────────┐    ┌──────────────┐
            │ L1 (Caffeine)│    │ L2 (Redis)   │
            │   - Fast     │    │ - Distributed│
            │   - Local    │    │ - Persistent │
            └──────────────┘    └──────────────┘
```

## Features

- **Multi-level Caching**: Automatic L1/L2 cache management
- **Read-through Pattern**: Transparent data loading on cache misses
- **Distributed Invalidation**: Redis pub/sub for cache coherence
- **Spring Boot Integration**: Auto-configuration and properties support
- **Type Safety**: Generic support for cached objects
- **Error Resilience**: Graceful handling of cache failures
- **Configurable**: Flexible configuration via application properties

## Modules

### Core Modules

1. **caching-core**: Core interfaces and multi-level cache implementation
2. **caching-caffeine-adapter**: Caffeine (L1) cache adapter
3. **caching-redis-adapter**: Redis (L2) cache adapter  
4. **caching-spring-boot-starter**: Spring Boot auto-configuration

## Quick Start

### 1. Add Dependency

```xml
<dependency>
    <groupId>com.pedromossi</groupId>
    <artifactId>caching-spring-boot-starter</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### 2. Configure Properties

```yaml
caching:
  enabled: true
  l1:
    enabled: true
    spec: "maximumSize=1000,expireAfterWrite=5m"
  l2:
    enabled: true
    ttl: PT30M
    invalidationTopic: "cache:invalidation"
```

### 3. Use in Your Code

```java
@Service
public class UserService {
    
    @Autowired
    private CacheService cacheService;
    
    @Autowired
    private UserRepository userRepository;
    
    public User getUser(Long userId) {
        return cacheService.getOrLoad(
            "user:" + userId,
            User.class,
            () -> userRepository.findById(userId).orElse(null)
        );
    }
    
    public void updateUser(User user) {
        userRepository.save(user);
        // Invalidate cache across all instances
        cacheService.invalidate("user:" + user.getId());
    }
}
```

## Configuration

### Application Properties

| Property | Description | Default |
|----------|-------------|---------|
| `caching.enabled` | Enable/disable the caching library | `true` |
| `caching.l1.enabled` | Enable/disable L1 (Caffeine) cache | `true` |
| `caching.l1.spec` | Caffeine cache specification | `"maximumSize=500,expireAfterWrite=10m"` |
| `caching.l2.enabled` | Enable/disable L2 (Redis) cache | `true` |
| `caching.l2.ttl` | Redis cache TTL | `PT1H` (1 hour) |
| `caching.l2.invalidationTopic` | Redis pub/sub topic for invalidation | `"cache:invalidation"` |

### Caffeine Specifications

Common Caffeine spec patterns:

```yaml
caching:
  l1:
    spec: "maximumSize=1000"                           # Size-based eviction
    spec: "expireAfterWrite=5m"                        # Time-based expiration
    spec: "maximumSize=500,expireAfterWrite=10m"       # Combined
    spec: "expireAfterAccess=30s,recordStats"          # Access-based + stats
```

## Cache Behavior

### Cache Hit Flow

1. **L1 Hit**: Data found in local Caffeine cache (fastest)
2. **L2 Hit**: Data found in Redis, promoted to L1
3. **Cache Miss**: Data loaded from source, stored in both L1 and L2

### Invalidation Flow

1. Call `cacheService.invalidate(key)`
2. Remove from local L1 cache
3. Remove from Redis L2 cache
4. Publish invalidation message to Redis topic
5. All application instances receive message and clear their L1 caches

## Error Handling

The library is designed to be resilient:

- **Cache Failures**: Application continues working if cache operations fail
- **Redis Unavailable**: L1 cache continues to work independently
- **Serialization Issues**: Errors are logged, but don't break the application flow

## Performance Considerations

- **L1 Cache**: Optimized for high-frequency access patterns
- **L2 Cache**: Reduces database load across application instances  
- **Async Operations**: Cache writes don't block read operations
- **Connection Pooling**: Redis operations use connection pooling

## Monitoring and Debugging

Enable debug logging:

```yaml
logging:
  level:
    com.pedromossi.caching: DEBUG
```

This will show:
- Cache hits/misses
- Cache operations (put/evict)
- Invalidation events
- Error conditions

## Requirements

- Java 21+
- Spring Boot 3.1+
- Redis (for L2 cache)

## License

This project is licensed under the MIT License.
