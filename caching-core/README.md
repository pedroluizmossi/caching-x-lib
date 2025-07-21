# Caching-Core Module

This module is the heart of the Caching-X library. It defines the core interfaces, the central multi-level cache implementation, and the annotation-driven caching framework.

## Core Concepts

1.  **`CacheProvider` Interface**: The fundamental contract for any cache implementation (e.g., L1-Caffeine, L2-Redis). It defines basic `get`, `put`, and `evict` operations.
2.  **`CacheService` Interface**: A high-level abstraction for application developers. It orchestrates interactions between cache layers and provides the `getOrLoad` "cache-aside" pattern.
3.  **`MultiLevelCacheService`**: The primary implementation of `CacheService`. It intelligently manages L1 and L2 caches, including features like automatic cache promotion and null value caching.
4.  **`@CacheX` and `CacheXAspect`**: The AOP-based framework for declarative caching, allowing developers to add caching to methods with a single annotation.

## Dependency

This module is typically a transitive dependency. However, if you need to interact with the core interfaces directly, you can add it to your `pom.xml`:

```xml
<dependency>
    <groupId>com.pedromossi</groupId>
    <artifactId>caching-core</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

## Core Interfaces

### `CacheProvider`

A contract for a single cache layer.

```java
public interface CacheProvider {
    <T> T get(String key, ParameterizedTypeReference<T> typeRef);
    void put(String key, Object value);
    void evict(String key);
    // ... batch operations also included
}
```

### `CacheService`

The primary service your application will interact with.

```java
public interface CacheService {
    <T> T getOrLoad(String key, ParameterizedTypeReference<T> typeRef, Supplier<T> loader);
    void invalidate(String key);
    // ... batch operations also included
}
```

## Declarative Caching with `@CacheX`

The `@CacheX` annotation provides a clean, declarative way to add caching to any Spring bean's methods. It is the recommended approach for most use cases.

### Usage

```java
@Service
public class UserService {
    
    // Cache the result of this method.
    @CacheX(key = "'user:' + #userId")
    public User findById(Long userId) {
        // This code only runs on a cache miss.
        return userRepository.findById(userId).orElse(null);
    }
    
    // Evict the cache after the method completes successfully.
    @CacheX(key = "'user:' + #user.id", operation = CacheX.Operation.EVICT)
    public void updateUser(User user) {
        userRepository.save(user);
    }
}
```

### Spring Expression Language (SpEL) for Keys

The `key` attribute supports powerful SpEL expressions to generate dynamic keys from method arguments.

| Example                                               | Description                                         |
| ----------------------------------------------------- | --------------------------------------------------- |
| `"'user:' + #userId"`                                 | Simple parameter reference.                         |
| `"'order:' + #order.id + ':status'"`                  | Accessing properties of an object parameter.        |
| `"'search:' + #query.hashCode() + ':' + #page"`       | Using method calls and multiple parameters.         |
| `"'config:' + T(java.time.LocalDate).now().toString()"`| Using static methods.                               |
| `"'global:config'"`                                   | A static key, useful for caching global data.       |

## Advanced Core Features

The core module includes decorators that add powerful, cross-cutting concerns to any `CacheProvider`. These are auto-configured by the `caching-spring-boot-starter`.

-   **Resilience**: `CircuitBreakerCacheProvider` wraps a cache provider (typically L2) with a Resilience4j circuit breaker, protecting your application from failures.
-   **Metrics**: `MetricsCollectingCacheProvider` wraps any provider to collect granular Micrometer metrics on hits, misses, latency, and errors.

> For detailed configuration and usage of these features, please see the **[caching-spring-boot-starter/README.md](caching-spring-boot-starter/README.md)**.
