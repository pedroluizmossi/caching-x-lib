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
- **Null Value Caching**: Proper handling of null values to prevent cache stampeding
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
5. **caching-reporter**: Aggregated test coverage reports (Jacoco)

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
    
    // Simple class type
    public User getUser(Long userId) {
        return cacheService.getOrLoad(
            "user:" + userId,
            User.class,
            () -> userRepository.findById(userId).orElse(null)
        );
    }
    
    // Generic collections using ParameterizedTypeReference
    public List<User> getUsersByRole(String role) {
        return cacheService.getOrLoad(
            "users:role:" + role,
            new ParameterizedTypeReference<List<User>>() {},
            () -> userRepository.findByRole(role)
        );
    }
    
    // Complex generic types
    public Map<String, List<User>> getUsersGroupedByDepartment() {
        return cacheService.getOrLoad(
            "users:grouped:department",
            new ParameterizedTypeReference<Map<String, List<User>>>() {},
            () -> userRepository.findAll()
                    .stream()
                    .collect(Collectors.groupingBy(User::getDepartment))
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
|----------|-------------|--------|
| `caching.enabled` | Enable/disable the caching library | `true` |
| `caching.l1.enabled` | Enable/disable L1 (Caffeine) cache | `true` |
| `caching.l1.spec` | Caffeine cache specification | `"maximumSize=500,expireAfterWrite=10m"` |
| `caching.l2.enabled` | Enable/disable L2 (Redis) cache | `true` |
| `caching.l2.ttl` | Redis cache TTL | `PT1H` (1 hour) |
| `caching.l2.invalidationTopic` | Redis pub/sub topic for invalidation | `"cache:invalidation"` |
| `caching.async.core-pool-size` | Base number of threads for async operations | `2` |
| `caching.async.max-pool-size` | Maximum number of threads for async operations | `50` |
| `caching.async.queue-capacity` | Task queue capacity for async operations | `1000` |

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

## Asynchronous Operations

To improve performance and reduce latency, write operations (`put`) and invalidation operations (`evict`) are executed asynchronously using a dedicated thread pool. This means your application doesn't need to wait for Redis communication to complete before continuing processing.

### Configuring the Thread Pool

You can customize the `ExecutorService` used for asynchronous operations through the following properties in your `application.yml`:

```yaml
caching:
  async:
    core-pool-size: 2      # Base number of threads
    max-pool-size: 50      # Maximum number of threads  
    queue-capacity: 1000  # Task queue capacity
```

### Benefits

- **Reduced Latency**: Cache operations don't block your main application flow
- **Better Throughput**: Multiple cache operations can be processed concurrently
- **Resilience**: Application continues working even if Redis operations are slow
- **Resource Management**: Configurable thread pool prevents resource exhaustion

### Thread Pool Configuration

The default configuration provides:
- **Core Pool Size**: 2 threads always available
- **Max Pool Size**: Up to 50 threads during peak load
- **Queue Capacity**: 10,000 pending operations can be queued

Adjust these values based on your application's cache usage patterns and system resources.

## Cache Behavior

### Cache Hit Flow

1. **L1 Hit**: Data found in local Caffeine cache (fastest)
2. **L2 Hit**: Data found in Redis, promoted to L1
3. **Cache Miss**: Data loaded from source, stored in both L1 and L2

### Null Value Handling

The caching system properly handles null values using a **Null Value Object pattern**:

#### Problem Solved
Previously, when a data source returned `null`, it wasn't cached. This caused:
- **Cache Stampeding**: Multiple requests for the same null data hitting the database
- **Performance Issues**: No benefit from caching for legitimate null values
- **Inconsistent Behavior**: Different treatment for null vs non-null values

#### Solution: Null Sentinel Objects
The system now uses an internal sentinel object to represent null values in cache:

```java
// Example: User biography is optional and can be null
public String getUserBiography(Long userId) {
    return cacheService.getOrLoad(
        "user:bio:" + userId,
        String.class,
        () -> userRepository.findBiography(userId) // May return null
    );
}
```

**First call**: Database returns `null` → Sentinel stored in cache → Returns `null` to client
**Second call**: Sentinel found in cache → Returns `null` to client (no database hit!)

#### Benefits
- **Performance**: Null values are served from cache, avoiding database calls
- **Consistency**: All values (null and non-null) follow the same caching pattern
- **Transparency**: Clients receive actual null values, never seeing internal sentinels
- **Serialization Safe**: Sentinel objects work properly with distributed caches

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

## Type Safety and Generic Support

The CacheService provides two methods for type-safe caching to handle different scenarios:

### Simple Types with Class<T>

For simple, non-generic types, use the `Class<T>` parameter:

```java
// Basic types
String name = cacheService.getOrLoad("user:name", String.class, () -> getName());
Integer count = cacheService.getOrLoad("user:count", Integer.class, () -> getCount());
User user = cacheService.getOrLoad("user:123", User.class, () -> loadUser(123));
```

### Generic Types with ParameterizedTypeReference<T>

For generic collections and complex types, use `ParameterizedTypeReference<T>`:

```java
// Collections
List<String> names = cacheService.getOrLoad(
    "user:names", 
    new ParameterizedTypeReference<List<String>>() {},
    () -> loadNames()
);

// Maps
Map<String, User> userMap = cacheService.getOrLoad(
    "users:map",
    new ParameterizedTypeReference<Map<String, User>>() {},
    () -> loadUserMap()
);

// Nested generics
List<Map<String, Object>> data = cacheService.getOrLoad(
    "complex:data",
    new ParameterizedTypeReference<List<Map<String, Object>>>() {},
    () -> loadComplexData()
);
```

### When to Use Each Approach

| Use Case | Method | Example |
|----------|--------|---------|
| Simple classes | `Class<T>` | `User.class`, `String.class` |
| Generic collections | `ParameterizedTypeReference<T>` | `List<User>`, `Set<String>` |
| Maps | `ParameterizedTypeReference<T>` | `Map<String, User>` |
| Nested generics | `ParameterizedTypeReference<T>` | `List<Map<String, Object>>` |
| Custom generic classes | `ParameterizedTypeReference<T>` | `Response<List<User>>` |

### Type Erasure Considerations

Due to Java's type erasure, generic type information is lost at runtime for `Class<T>`. This is why `ParameterizedTypeReference<T>` is required for generic types - it preserves the full type information needed for proper serialization/deserialization in Redis cache.
