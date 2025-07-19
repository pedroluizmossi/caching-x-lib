# Caching-Core Module

Core interfaces, multi-level cache implementation with intelligent cache promotion, null value handling, and annotation-based caching support.

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

### @CacheX Annotation

Declarative caching annotation for method-level caching using Spring AOP.

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface CacheX {
    String key();                                    // SpEL expression for cache key
    Operation operation() default Operation.GET;     // Cache operation type
    
    enum Operation {
        GET,    // Cache method result (default)
        EVICT   // Invalidate cache after method execution
    }
}
```

### CacheXAspect

Spring AOP aspect that intercepts `@CacheX` annotated methods and applies caching logic.

**Features:**
- **SpEL Support**: Dynamically generates cache keys using Spring Expression Language
- **Type Safety**: Automatically preserves method return types for cache operations
- **Operation Handling**: Supports both GET (caching) and EVICT (invalidation) operations
- **Error Handling**: Gracefully handles exceptions during cache operations

**Usage Examples:**

```java
@Service
public class UserService {
    
    // Cache method result
    @CacheX(key = "'user:' + #userId")
    public User findById(Long userId) {
        return userRepository.findById(userId).orElse(null);
    }
    
    // Invalidate cache after method execution
    @CacheX(key = "'user:' + #user.id", operation = CacheX.Operation.EVICT)
    public void updateUser(User user) {
        userRepository.save(user);
    }
    
    // Complex key expressions
    @CacheX(key = "'users:search:' + #query.hashCode() + ':' + #page")
    public List<User> searchUsers(SearchQuery query, int page) {
        return userRepository.search(query, page);
    }
}
```

### MultiLevelCacheService Implementation

Orchestrates L1 and L2 cache layers with automatic promotion strategy:

**Cache Flow:**
1. Check L1 cache first (fastest path)
2. On L1 miss, check L2 cache
3. On L2 hit, promote to L1 and return
4. On complete miss, execute loader and cache result

**Integration with @CacheX:**
- The aspect delegates to `MultiLevelCacheService.getOrLoad()` for GET operations
- Uses `MultiLevelCacheService.invalidate()` for EVICT operations
- Maintains the same cache flow and promotion strategy

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

// Same behavior with annotations
@CacheX(key = "'user:bio:' + #userId")
public String getUserBio(Long userId) {
    return userRepository.findBio(userId); // Null values are cached automatically
}
```

**Benefits:**
- Null values are served from cache, avoiding repeated DB calls
- Transparent to clients (they receive actual null values)
- Works correctly with distributed caches
- Consistent behavior between programmatic and annotation-based usage

## Usage Examples

### Programmatic API

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

### Annotation-Based API

```java
@Service
public class ProductService {
    
    @CacheX(key = "'product:' + #id")
    public Product getProduct(Long id) {
        return productRepository.findById(id).orElse(null);
    }
    
    @CacheX(key = "'products:category:' + #categoryId")
    public List<Product> getProductsByCategory(Long categoryId) {
        return productRepository.findByCategory(categoryId);
    }
    
    @CacheX(key = "'product:' + #product.id", operation = CacheX.Operation.EVICT)
    public void updateProduct(Product product) {
        productRepository.save(product);
    }
    
    @CacheX(key = "'products:category:' + #categoryId", operation = CacheX.Operation.EVICT)
    public void evictCategoryCache(Long categoryId) {
        // Method implementation can be empty for pure cache eviction
    }
}
```

## SpEL Expression Examples

The `@CacheX` annotation supports Spring Expression Language for dynamic key generation:

```java
// Simple parameter reference
@CacheX(key = "'user:' + #userId")
public User getUser(Long userId) { ... }

// Object property access
@CacheX(key = "'order:' + #order.id + ':status'")
public OrderStatus getOrderStatus(Order order) { ... }

// Method calls on parameters
@CacheX(key = "'search:' + #query.toLowerCase()")
public List<Result> search(String query) { ... }

// Conditional expressions
@CacheX(key = "'data:' + (#useCache ? #id : 'nocache')")
public Data getData(Long id, boolean useCache) { ... }

// Multiple parameters
@CacheX(key = "'report:' + #year + ':' + #month + ':' + #type")
public Report getReport(int year, int month, String type) { ... }

// Static values
@CacheX(key = "'global:config'")
public Configuration getGlobalConfig() { ... }
```

## AOP Configuration

The `CacheXAspect` is automatically configured when using the Spring Boot starter. For manual configuration:

```java
@Configuration
@EnableAspectJAutoProxy
public class CacheConfig {
    
    @Bean
    public CacheXAspect cacheXAspect(CacheService cacheService) {
        return new CacheXAspect(cacheService);
    }
}
```

## Debugging and Logging

Enable debug logging to monitor cache operations:

```yaml
logging:
  level:
    com.pedromossi.caching.aspect.CacheXAspect: DEBUG
    com.pedromossi.caching.impl.MultiLevelCacheService: DEBUG
```

Debug logs will show:
- When the aspect intercepts method calls
- Cache operations being performed
- Cache hits and misses
- Key generation results
- Operation completion status
