# Caching-Spring-Boot-Starter Module

This starter is the simplest and **recommended way** to integrate the Caching-X library into a Spring Boot application. It provides comprehensive auto-configuration for all features, including multi-level caching, resilience, and metrics.

## Features

-   **Zero-Effort Setup**: Add the dependency and configure your `application.yml`.
-   **Auto-Configured Beans**: Automatically creates and wires `CacheService`, `CacheProvider`s, and `CacheXAspect`.
-   **Conditional Loading**: Intelligently enables features like L2 cache, metrics, and circuit breakers based on your classpath and properties.
-   **Type-Safe Properties**: All settings are configurable via `caching.*` properties.
-   **Secure by Default**: Configures a secure environment out of the box, especially for Redis serialization.

## Dependency

Add the starter to your `pom.xml`:

```xml
<dependency>
    <groupId>com.pedromossi</groupId>
    <artifactId>caching-spring-boot-starter</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

## Quick Configuration

Add the following to your `application.yml` to enable a full L1 + L2 cache setup.

```yaml
# Configure Caching-X layers
caching:
  # L1 (in-memory) cache using a Caffeine spec string.
  l1:
    spec: "maximumSize=1000,expireAfterWrite=5m,recordStats"
  # L2 (distributed) cache using Redis.
  l2:
    ttl: PT30M # Default TTL for all entries.
    invalidation-topic: "my-app:cache:invalidation"

# Redis connection details (used by L2 cache)
spring:
  data:
    redis:
      host: localhost
      port: 6379
```

## How to Use

The starter fully configures both the declarative and programmatic APIs.

-   **Declarative (`@CacheX`)**: The recommended approach for clean, simple caching.
-   **Programmatic (`CacheService`)**: For complex scenarios requiring fine-grained control.

> For detailed examples of both APIs, please see the main project **[README.md](../README.md#quick-start-in-3-steps)**.

## How It Works: Auto-Configuration

The starter simplifies a complex setup by performing these actions automatically:

1.  **Creates Cache Providers**: It instantiates `CaffeineCacheAdapter` (L1) and `RedisCacheAdapter` (L2) if they are enabled in properties.
2.  **Sets Up Invalidation**: It configures a Redis Pub/Sub listener on the `invalidation-topic` to ensure L1 caches are kept consistent across all instances.
3.  **Applies Decorators**:
  -   If `spring-boot-starter-actuator` is present, it wraps both cache providers with `MetricsCollectingCacheProvider` to enable granular metrics.
  -   If `resilience4j-spring-boot3` is present and `caching.l2.circuit-breaker.enabled=true`, it wraps the L2 provider with `CircuitBreakerCacheProvider`.
4.  **Configures the Core Service**: It creates the central `MultiLevelCacheService` bean, injecting the (optionally decorated) L1 and L2 providers.
5.  **Enables AOP**: It registers the `CacheXAspect` bean, which enables the `@CacheX` annotation to work seamlessly.

## Security by Default: Preventing RCE

This starter configures the Redis adapter to be secure against Remote Code Execution (RCE) vulnerabilities.

-   We provide a dedicated, secure `ObjectMapper` for caching that **disables default typing**.
-   This prevents malicious payloads in Redis from instantiating dangerous "gadget classes" on the classpath.
-   Type safety is guaranteed by the `ParameterizedTypeReference` you provide in your code, not by trusting metadata from the cache.

## Resilience: Circuit Breaker for Redis

Protect your application from a slow or unavailable Redis. When L2 cache operations fail or become too slow, the circuit breaker will "open," immediately failing fast and allowing your application to continue functioning by relying on the L1 cache and the original data source.

**To enable, add `resilience4j-spring-boot3` and configure:**

```yaml
caching:
  l2:
    circuit-breaker:
      enabled: true
      failure-rate-threshold: 50.0  # Open circuit if 50% of calls fail.
      slow-call-duration-threshold: PT1S # A call longer than 1s is slow.
```

## Monitoring and Observability

The starter provides a world-class observability experience. Add `spring-boot-starter-actuator` to enable:

1.  **Granular Cache Metrics**: Detailed metrics for hits, misses, latency, and errors for both L1 and L2 caches.
2.  **Native Caffeine Stats**: If you add `recordStats` to your L1 spec, you get deep insights into Caffeine's internal state (evictions, size, etc.).
3.  **`/actuator/cachex` Endpoint**: An interactive endpoint to inspect and evict cache keys in real-time.
  -   `GET /actuator/cachex/{key}`: See if a key exists in L1 or L2.
  -   `DELETE /actuator/cachex/{key}`: Evict a key from all layers.
4.  **Circuit Breaker Metrics**: If enabled, see the state, failure rates, and latencies of the circuit breaker at `/actuator/circuitbreakers`.