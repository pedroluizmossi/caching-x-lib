# Caching-Redis-Adapter Module

This module provides a resilient and scalable **L2 (distributed) cache** implementation using **Redis**. It is designed for sharing cached data across multiple application instances and ensuring data consistency.

## Core Concepts

-   **L2 Cache**: Acts as a shared, distributed cache layer accessible by all application instances.
-   **Distributed Invalidation**: Uses Redis Pub/Sub to broadcast invalidation messages, ensuring that when data is changed, all L1 caches across your cluster are cleared consistently.
-   **Secure Serialization**: Employs a secure-by-default JSON serialization strategy to protect against vulnerabilities.
-   **Time-to-Live (TTL)**: All entries are stored with a configurable TTL, ensuring automatic cleanup of stale data.

## Dependency

To use this adapter, add the following dependency to your `pom.xml`. It is included by default with the `caching-spring-boot-starter`.

```xml
<dependency>
    <groupId>com.pedromossi</groupId>
    <artifactId>caching-redis-adapter</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

## Configuration

When using the `caching-spring-boot-starter`, configure the L2 cache via `application.yml`:

```yaml
caching:
  l2:
    enabled: true
    # Default Time-to-Live for all entries in Redis.
    ttl: PT1H # ISO-8601 format (1 hour)
    # The Redis topic used for broadcasting invalidation messages.
    invalidation-topic: "app:cache:invalidation"

spring:
  data:
    redis:
      host: localhost
      port: 6379
```

## Serialization and Security

A major design focus of this library is **security**. This adapter uses a pluggable `CacheSerializer` pattern, with `JacksonCacheSerializer` as the default.

Our `JacksonCacheSerializer` is configured to be **secure by default**:
1.  **No Default Typing**: We explicitly **do not** enable Jackson's `activateDefaultTyping`. This is a critical security measure that prevents Remote Code Execution (RCE) attacks where a malicious payload in Redis could trick the JVM into instantiating dangerous classes.
2.  **Type-Safe Deserialization**: Instead of trusting metadata in the payload, we rely on the `ParameterizedTypeReference` provided by the `CacheService` at runtime. This ensures we only deserialize into the exact, safe type that the application code expects.

**What this means for you:** You get the benefit of storing complex objects as JSON in Redis without compromising your application's security.

## Distributed Invalidation Flow

1.  An application instance calls `cacheService.invalidate("user:123")`.
2.  The `RedisCacheAdapter` **deletes** the key `user:123` from Redis.
3.  Simultaneously, it **publishes** the key `user:123` to the configured `invalidation-topic`.
4.  All other application instances, which are subscribed to this topic, receive the message.
5.  Upon receiving the message, each instance immediately **evicts** `user:123` from its local **L1 (Caffeine) cache**.

This ensures that a write operation in one instance results in cache consistency across the entire distributed system.
