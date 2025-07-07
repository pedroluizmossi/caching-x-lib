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
    <T> T getOrLoad(String key, ParameterizedTypeReference<T> typeRef, Supplier<T> loader);
    void invalidate(String key);
}
```

**Methods:**
- `getOrLoad(key, type, loader)`: Gets data from cache or loads from source using simple Class type
- `getOrLoad(key, typeRef, loader)`: Gets data from cache or loads from source using ParameterizedTypeReference for generic types
- `invalidate(key)`: Invalidates a key across all cache levels

**Type Support:**
- **Class<T>**: Use for simple, non-generic types (String.class, User.class, Integer.class)
- **ParameterizedTypeReference<T>**: Use for generic types (List<User>, Map<String, Object>, etc.)

The `ParameterizedTypeReference<T>` is essential for generic types due to Java's type erasure, ensuring proper serialization/deserialization in distributed cache scenarios.

### Implementations

#### MultiLevelCacheService
The core implementation that orchestrates L1 and L2 cache layers.

**Features:**
- **Cache Hierarchy**: L1 (fast, local) → L2 (distributed) → Source
- **Promotion**: L2 hits are promoted to L1 automatically
- **Fallback**: Graceful degradation if cache layers fail
- **Read-through**: Automatic data loading on cache misses
- **Null Value Caching**: Proper handling of null values to prevent cache stampeding

**Cache Flow:**
1. Check L1 cache first (fastest)
2. If miss, check L2 cache
3. If L2 hit, promote to L1 and return
4. If complete miss, execute loader function
5. Store result in both L1 and L2 (including null values)

## Null Value Handling

The caching system properly handles null values using a **Null Value Object pattern**:

### Problem Solved
Previously, when a data source returned `null`, it wasn't cached. This caused:
- **Cache Stampeding**: Multiple requests for the same null data hitting the database
- **Performance Issues**: No benefit from caching for legitimate null values
- **Inconsistent Behavior**: Different treatment for null vs non-null values

### Solution: Null Sentinel Objects
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

### Benefits
- **Performance**: Null values are served from cache, avoiding database calls
- **Consistency**: All values (null and non-null) follow the same caching pattern
- **Transparency**: Clients receive actual null values, never seeing internal sentinels
- **Serialization Safe**: Sentinel objects work properly with distributed caches

### Implementation Details
- **Internal Only**: The sentinel pattern is completely internal to the caching system
- **Type Safe**: Proper unwrapping ensures type safety for clients
- **Serializable**: Works correctly with Redis and other distributed cache systems
- **Thread Safe**: Concurrent access is handled properly

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

### Null Value Scenarios
```java
// These examples now properly cache null values
public String getUserBiography(Long userId) {
    // If biography is null, it will be cached and served from cache on subsequent calls
    return cacheService.getOrLoad(
        "user:bio:" + userId,
        String.class,
        () -> userRepository.findBiography(userId) // May return null
    );
}

public Optional<Address> getUserAddress(Long userId) {
    // Optional.empty() is also properly cached
    return cacheService.getOrLoad(
        "user:address:" + userId,
        new ParameterizedTypeReference<Optional<Address>>() {},
        () -> userRepository.findUserAddress(userId) // May return Optional.empty()
    );
}
```

### Invalidation
```java
public void updateUser(User user) {
    userRepository.save(user);
    cacheService.invalidate("user:" + user.getId());
    // Also invalidate related nullable fields
    cacheService.invalidate("user:bio:" + user.getId());
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
- Null value handling is transparent and error-free
