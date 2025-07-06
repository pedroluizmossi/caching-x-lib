# Caching-Caffeine-Adapter Module

The Caffeine adapter provides high-performance in-memory caching for L1 (local) cache layer using the Caffeine library.

## Overview

This module implements the `CacheProvider` interface using [Caffeine](https://github.com/ben-manes/caffeine), a high-performance Java caching library that provides:

- **Near optimal hit rates** using advanced eviction policies
- **Low memory overhead** with efficient data structures
- **Thread-safe operations** with minimal contention
- **Flexible configuration** via specification strings

## Features

- **Size-based Eviction**: Limit cache size by number of entries
- **Time-based Expiration**: TTL and TTI (time-to-idle) support
- **Statistics**: Optional performance metrics collection
- **Thread Safety**: Concurrent access without external synchronization

## Configuration

### Caffeine Specification Strings

The adapter accepts Caffeine specification strings for configuration:

```yaml
caching:
  l1:
    spec: "maximumSize=1000,expireAfterWrite=5m"
```

### Common Patterns

| Pattern | Description | Example |
|---------|-------------|---------|
| Size-based | Evict entries when size limit reached | `maximumSize=1000` |
| Write-based TTL | Expire after fixed time from write | `expireAfterWrite=10m` |
| Access-based TTI | Expire after idle time | `expireAfterAccess=30s` |
| Combined | Multiple policies together | `maximumSize=500,expireAfterWrite=5m` |
| With Stats | Enable performance monitoring | `maximumSize=1000,recordStats` |

### Time Units

- `s` or `sec` - seconds
- `m` or `min` - minutes  
- `h` or `hour` - hours
- `d` or `day` - days

## Implementation Details

### CaffeineCacheAdapter

```java
public class CaffeineCacheAdapter implements CacheProvider {
    private final Cache<String, Object> cache;
    
    public CaffeineCacheAdapter(String spec) {
        this.cache = Caffeine.from(spec).build();
    }
}
```

**Key Features:**
- **Type Safety**: Uses generic type checking for cache retrieval
- **Null Handling**: Returns null for missing or incompatible types
- **Error Resilience**: Graceful handling of cache operations
- **Logging**: Debug information for cache operations

### Performance Characteristics

- **Get Operations**: O(1) average case
- **Put Operations**: O(1) average case with occasional cleanup
- **Memory Usage**: Minimal overhead per entry
- **Concurrency**: High throughput under concurrent access

## Usage Examples

### Basic Configuration
```java
@Bean
public CacheProvider l1Cache() {
    return new CaffeineCacheAdapter("maximumSize=1000,expireAfterWrite=10m");
}
```

### Advanced Configuration
```java
// High-frequency cache with size and time limits
String spec = "maximumSize=5000,expireAfterWrite=5m,expireAfterAccess=2m,recordStats";
CacheProvider cache = new CaffeineCacheAdapter(spec);
```

### Direct Usage
```java
CacheProvider l1Cache = new CaffeineCacheAdapter("maximumSize=500");

// Store value
l1Cache.put("user:123", user);

// Retrieve value
User user = l1Cache.get("user:123", User.class);

// Remove value
l1Cache.evict("user:123");
```

## Best Practices

1. **Size Limits**: Always set maximum size to prevent OOM
2. **TTL Configuration**: Use appropriate expiration times for your data
3. **Statistics**: Enable stats in development for tuning
4. **Monitoring**: Watch hit rates and eviction patterns
5. **Key Design**: Use consistent, hierarchical key patterns

## Dependencies

```xml
<dependency>
    <groupId>com.github.ben-manes.caffeine</groupId>
    <artifactId>caffeine</artifactId>
</dependency>
```
