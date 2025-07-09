# Caching-Caffeine-Adapter Module

High-performance in-memory caching for L1 (local) cache layer using Caffeine library.

## Features

- **Size-based Eviction**: Limit cache by number of entries
- **Time-based Expiration**: TTL and TTI support
- **Thread Safety**: Concurrent access without external synchronization
- **Statistics**: Optional performance metrics collection

## Configuration

Uses Caffeine specification strings:

```yaml
caching:
  l1:
    spec: "maximumSize=1000,expireAfterWrite=5m"
```

### Common Patterns

| Pattern | Description | Example |
|---------|-------------|---------|
| Size-based | Evict when size limit reached | `maximumSize=1000` |
| Write-based TTL | Expire after fixed time from write | `expireAfterWrite=10m` |
| Access-based TTI | Expire after idle time | `expireAfterAccess=30s` |
| Combined | Multiple policies | `maximumSize=500,expireAfterWrite=5m` |
| With Stats | Enable monitoring | `maximumSize=1000,recordStats` |

### Time Units
- `s` or `sec` - seconds
- `m` or `min` - minutes  
- `h` or `hour` - hours
- `d` or `day` - days

## Usage

```java
// Direct usage
CacheProvider l1Cache = new CaffeineCacheAdapter("maximumSize=500");
l1Cache.put("user:123", user);
User user = l1Cache.get("user:123", new ParameterizedTypeReference<User>(){});
```

## Best Practices

1. **Size Limits**: Always set maximum size to prevent OOM
2. **TTL Configuration**: Use appropriate expiration times
3. **Statistics**: Enable stats in development for tuning
4. **Key Design**: Use consistent, hierarchical key patterns
