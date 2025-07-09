# Caching-X Library

A high-performance Java caching library providing multi-level caching with L1 (local) and L2 (distributed) cache layers.

## Overview

Caching-X implements an intelligent multi-level caching strategy:

- **L1 Cache (Caffeine)**: Ultra-fast local in-memory cache
- **L2 Cache (Redis)**: Distributed cache for data sharing across instances
- **Automatic Cache Promotion**: L2 hits are promoted to L1 for optimal performance
- **Distributed Invalidation**: Cache consistency across all application instances

## Quick Start

### 1. Add Dependency

```xml
<dependency>
    <groupId>com.pedromossi</groupId>
    <artifactId>caching-spring-boot-starter</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### 2. Configure Application

```yaml
caching:
  enabled: true
  l1:
    spec: "maximumSize=1000,expireAfterWrite=5m"
  l2:
    ttl: PT30M
```

### 3. Use in Your Code

```java
@Service
public class UserService {
    
    @Autowired
    private CacheService cacheService;
    
    public User getUser(Long userId) {
        return cacheService.getOrLoad(
            "user:" + userId,
            User.class,
            () -> userRepository.findById(userId).orElse(null)
        );
    }
    
    public void updateUser(User user) {
        userRepository.save(user);
        cacheService.invalidate("user:" + user.getId());
    }
}
```

## Key Features

- **Type Safety**: Full generic type support with `ParameterizedTypeReference<T>`
- **Null Value Caching**: Prevents cache stampeding for legitimate null results
- **Asynchronous Operations**: Non-blocking cache writes and invalidations
- **Error Resilience**: Graceful degradation when cache layers are unavailable
- **Spring Boot Integration**: Auto-configuration with sensible defaults

## Type Safety Examples

```java
// Simple types
User user = cacheService.getOrLoad("user:123", User.class, loader);

// Generic collections
List<User> users = cacheService.getOrLoad(
    "users:active", 
    new ParameterizedTypeReference<List<User>>() {},
    loader
);

// Complex generic types
Map<String, List<Order>> orders = cacheService.getOrLoad(
    "orders:grouped",
    new ParameterizedTypeReference<Map<String, List<Order>>>() {},
    loader
);
```

## Architecture

```
Application Request
       ↓
   CacheService
       ↓
┌─────────────────┐
│ L1 Cache (Fast) │ → Hit: Return immediately
└─────────────────┘
       ↓ Miss
┌─────────────────┐
│L2 Cache (Shared)│ → Hit: Promote to L1, return
└─────────────────┘
       ↓ Miss
┌─────────────────┐
│   Data Source   │ → Load, cache in both layers
└─────────────────┘
```

## Configuration Options

| Property | Description | Default |
|----------|-------------|--------|
| `caching.enabled` | Enable/disable caching | `true` |
| `caching.l1.spec` | Caffeine cache specification | `"maximumSize=500,expireAfterWrite=10m"` |
| `caching.l2.ttl` | Redis cache time-to-live | `PT1H` |
| `caching.async.core-pool-size` | Async thread pool size | `2` |

## Modules

- **[caching-core](caching-core/README.md)**: Core interfaces and multi-level implementation
- **[caching-caffeine-adapter](caching-caffeine-adapter/README.md)**: L1 cache adapter
- **[caching-redis-adapter](caching-redis-adapter/README.md)**: L2 cache adapter
- **[caching-spring-boot-starter](caching-spring-boot-starter/README.md)**: Auto-configuration

## Requirements

- Java 21+
- Spring Boot 3.1+
- Redis (for L2 cache)

For detailed configuration and advanced usage, see the individual module documentation.
