# Caching-Caffeine-Adapter Module

This module provides a high-performance **L1 (local) cache** implementation using the [Caffeine](https://github.com/ben-manes/caffeine) library. It is designed for sub-millisecond in-memory access to your most frequently used data.

## Core Concepts

-   **L1 Cache**: Serves as the fastest cache layer, residing in the application's local memory.
-   **High Performance**: Leverages Caffeine's near-optimal eviction policies and concurrent design.
-   **Time & Size-Based Eviction**: Supports TTL (Time-to-Live), TTI (Time-to-Idle), and maximum size limits to prevent memory exhaustion.
-   **Seamless Integration**: Auto-configured by the `caching-spring-boot-starter`.

## Dependency

To use this adapter, add the following dependency to your `pom.xml`:

```xml
<dependency>
    <groupId>com.pedromossi</groupId>
    <artifactId>caching-caffeine-adapter</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

## Configuration

When using the `caching-spring-boot-starter`, you can configure the L1 cache via your `application.yml` using a Caffeine specification string.

```yaml
caching:
  l1:
    enabled: true
    # Configure Caffeine's behavior in a single line.
    spec: "maximumSize=1000,expireAfterWrite=5m,recordStats"
```

### Common Spec Patterns

| Pattern                  | Description                                        | Example                                     |
| ------------------------ | -------------------------------------------------- | ------------------------------------------- |
| **Size-Based**           | Evicts entries when the cache exceeds a set size.  | `maximumSize=1000`                          |
| **Write-Based TTL**      | Expires entries a fixed duration after they are written. | `expireAfterWrite=10m`                      |
| **Access-Based TTI**     | Expires entries if they are not accessed for a duration. | `expireAfterAccess=30s`                     |
| **Enable Statistics**    | Enables metrics collection for monitoring.         | `recordStats`                               |
| **Combined**             | Combine multiple policies with commas.             | `maximumSize=500,expireAfterWrite=5m`       |

**Note on Time Units:**
-   `s`: seconds
-   `m`: minutes
-   `h`: hours
-   `d`: days

## Best Practices

1.  **Always Set a `maximumSize`**: This is critical to prevent `OutOfMemoryError` by putting a hard limit on the cache's memory footprint.
2.  **Choose the Right Expiration**: Use `expireAfterWrite` for data that becomes stale after a period, and `expireAfterAccess` for session-like data that should be kept only while active.
3.  **Enable `recordStats` in Production**: The performance overhead is negligible, and the metrics provided (hits, misses, evictions) are invaluable for tuning and monitoring cache effectiveness.
