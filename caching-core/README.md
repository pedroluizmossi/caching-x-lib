# Caching-Core Module

The core module provides the fundamental interfaces and implementations for the multi-level caching system.

## Components

### Interfaces

#### CacheProvider
The base interface that defines the contract for all cache implementations.

```java
public interface CacheProvider {
    <T> T get(String key, Class<T> type);
    void put(String key, Object value);
    void evict(String key);
}
```

**Methods:**
- `get(key, type)`: Retrieves a typed value from the cache
- `put(key, value)`: Stores a value in the cache
- `evict(key)`: Removes a value from the cache

#### CacheService
The main service interface that provides the high-level caching API with read-through capabilities.

```java
public interface CacheService {
    <T> T getOrLoad(String key, Class<T> type, Supplier<T> loader);
    void invalidate(String key);
}
```

**Methods:**
- `getOrLoad(key, type, loader)`: Gets data from cache or loads from source
- `invalidate(key)`: Invalidates a key across all cache levels

### Implementations

#### MultiLevelCacheService
The core implementation that orchestrates L1 and L2 cache layers.

**Features:**
- **Cache Hierarchy**: L1 (fast, local) → L2 (distributed) → Source
- **Promotion**: L2 hits are promoted to L1 automatically
- **Fallback**: Graceful degradation if cache layers fail
- **Read-through**: Automatic data loading on cache misses

**Cache Flow:**
1. Check L1 cache first (fastest)
2. If miss, check L2 cache
3. If L2 hit, promote to L1 and return
4. If complete miss, execute loader function
5. Store result in both L1 and L2

## Usage Examples

### Basic Usage
```java
@Autowired
private CacheService cacheService;

public User getUser(Long id) {
    return cacheService.getOrLoad(
        "user:" + id,
        User.class,
        () -> userRepository.findById(id).orElse(null)
    );
}
```

### Invalidation
```java
public void updateUser(User user) {
    userRepository.save(user);
    cacheService.invalidate("user:" + user.getId());
}
```

## Configuration

The core module works with any combination of cache providers:
- L1 only (local caching)
- L2 only (distributed caching)
- Both L1 and L2 (recommended for best performance)

## Error Handling

The MultiLevelCacheService is designed to be resilient:
- Cache failures don't break the application flow
- Errors are logged but don't propagate to callers
- The system gracefully falls back to data source loading
