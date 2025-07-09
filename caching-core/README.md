# Caching-Core Module

Core interfaces and multi-level cache implementation with intelligent cache promotion and null value handling.

## Components

### CacheProvider Interface
Base contract for all cache implementations (L1/L2).

```java
public interface CacheProvider {
    <T> T get(String key, ParameterizedTypeReference<T> typeRef);
    void put(String key, Object value);
    void evict(String key);
}
```

### CacheService Interface
High-level caching API with read-through capabilities.

```java
public interface CacheService {
    <T> T getOrLoad(String key, Class<T> type, Supplier<T> loader);
    <T> T getOrLoad(String key, ParameterizedTypeReference<T> typeRef, Supplier<T> loader);
    void invalidate(String key);
}
```

**Type Support:**
- `Class<T>`: Simple types (String.class, User.class)
- `ParameterizedTypeReference<T>`: Generic types (List<User>, Map<String, Object>)

### MultiLevelCacheService Implementation

Orchestrates L1 and L2 cache layers with automatic promotion strategy:

**Cache Flow:**
1. Check L1 cache first (fastest path)
2. On L1 miss, check L2 cache
3. On L2 hit, promote to L1 and return
4. On complete miss, execute loader and cache result

## Null Value Handling

Uses an internal sentinel pattern to cache null values, preventing cache stampeding:

```java
// First call: null from DB → cached as sentinel → returns null
// Second call: sentinel from cache → returns null (no DB hit!)
public String getUserBio(Long userId) {
    return cacheService.getOrLoad(
        "user:bio:" + userId,
        String.class,
        () -> userRepository.findBio(userId) // May return null
    );
}
```

**Benefits:**
- Null values are served from cache, avoiding repeated DB calls
- Transparent to clients (they receive actual null values)
- Works correctly with distributed caches

## Usage Examples

```java
// Simple types
User user = cacheService.getOrLoad("user:123", User.class, loader);

// Generic collections
List<String> tags = cacheService.getOrLoad(
    "user:tags:123",
    new ParameterizedTypeReference<List<String>>() {},
    loader
);

// Invalidation
cacheService.invalidate("user:123");
```
