# Caching-X: High-Performance Multi-Level Caching for Java

**Caching-X** is a powerful, type-safe, and resilient multi-level caching library for modern Java applications. It simplifies the complexity of managing local (L1) and distributed (L2) caches, providing a seamless and high-performance solution out of the box.

## Why Caching-X?

Managing multiple cache layers can be complex. Caching-X abstracts away the boilerplate, providing an intelligent, production-ready caching strategy that just works.

- **Effortless Performance**: Get the speed of an in-memory cache (L1) and the scalability of a distributed cache (L2) without the implementation overhead.
- **Built for Resilience**: Your application remains stable even if a cache layer fails.
- **Developer-Friendly API**: A clean, intuitive API that leverages modern Java features and full type safety.
- **Annotation-Based Caching**: Use `@CacheX` annotations for declarative caching with AOP support.
- **Seamless Integration**: Auto-configured for Spring Boot, making setup a breeze.

## Key Features

- **Multi-Level Caching**: Combines a lightning-fast L1 cache (Caffeine) with a distributed L2 cache (Redis).
- **Annotation Support**: Declarative caching using `@CacheX` annotation with Spring AOP.
- **Intelligent Cache Promotion**: Automatically promotes L2 cache hits to L1, ensuring frequently accessed data is served at maximum speed.
- **Distributed Invalidation**: Keeps data consistent across all application instances with Redis Pub/Sub.
- **Full Type Safety**: Generic-aware API using `ParameterizedTypeReference` to prevent `ClassCastException`.
- **Null Value Caching**: Prevents cache stampedes by safely caching `null` results from your data source.
- **Asynchronous Operations**: All cache writes and invalidations are non-blocking, keeping your application responsive.
- **Error Resilience**: Gracefully handles cache layer failures without crashing your application.

## Architecture

Caching-X follows a clear and optimized data lookup strategy.

### Read Flow (getOrLoad)
![image](https://github.com/user-attachments/assets/09560d96-6761-4e3d-afd6-21b34ab3d910)
<details>
<summary>Mermaid Code</summary>
       
```
sequenceDiagram
    participant App as Application
    participant CacheService
    participant L1 as L1 Cache (Caffeine)
    participant L2 as L2 Cache (Redis)
    participant DB as Data Source

    App->>+CacheService: getOrLoad("key")
    CacheService->>L1: get("key")

    alt L1 Hit
        L1-->>CacheService: Found! (Hit)
        CacheService-->>-App: Return value from L1
    else L1 Miss
        L1-->>CacheService: Not Found (Miss)
        CacheService->>L2: get("key")

        alt L2 Hit
            L2-->>CacheService: Found! (Hit)
            Note right of CacheService: Promote to L1 in background
            CacheService-->>App: Return value from L2
            CacheService-xL1: put("key", value)
        else L2 Miss
            L2-->>CacheService: Not Found (Miss)
            CacheService->>DB: Load from data source
            DB-->>CacheService: Return value
            Note right of CacheService: Store in L1 & L2 in background
            CacheService-->>App: Return value from source
            CacheService-xL2: put("key", value)
            CacheService-xL1: put("key", value)
        end
    end
```
</details>

### Invalidation Flow (invalidate)

![image](https://github.com/user-attachments/assets/935bd40f-2f86-4eea-9f61-1c9066b653b2)

<details>
<summary>Mermaid Code</summary>
       
```
sequenceDiagram
    participant App as Application
    participant CacheService
    participant L1 as L1 Cache (Local)
    participant L2 as L2 Cache (Redis)
    participant PubSub as Redis Pub/Sub
    participant OtherApp as Other Instance

    App->>+CacheService: invalidate("key")
    Note right of CacheService: Asynchronous operation
    CacheService-xL2: evict("key")
    L2-xPubSub: Publish invalidation("key")
    CacheService-xL1: evict("key")
    deactivate CacheService
    
    OtherApp->>PubSub: Listens to topic
    PubSub-->>OtherApp: Receives message("key")
    OtherApp->>OtherApp: Invalidates local L1 for "key"
```
</details>

## Quick Start

### 1. Add Dependency

Add the Caching-X starter to your `pom.xml`:

```xml
<dependency>
    <groupId>com.pedromossi</groupId>
    <artifactId>caching-spring-boot-starter</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### 2. Configure Your Application

Set up your cache layers in `application.yml`:

```yaml
# Enable Spring Cache and Redis
spring:
  cache:
    type: redis
  redis:
    host: localhost
    port: 6379

# Configure Caching-X
caching:
  enabled: true
  l1:
    enabled: true
    spec: "maximumSize=1000,expireAfterWrite=5m" # Caffeine Spec
  l2:
    enabled: true
    ttl: PT30M # ISO-8601 duration format
```

### 3. Use in Your Code

#### Option A: Programmatic API

Inject `CacheService` and start caching:

```java
@Service
public class ProductService {
    
    @Autowired
    private CacheService cacheService;
    
    public Product getProduct(String productId) {
        // Get from cache or load from repository if missed
        return cacheService.getOrLoad(
            "product:" + productId,
            Product.class,
            () -> productRepository.findById(productId).orElse(null)
        );
    }
    
    public void updateProduct(Product product) {
        productRepository.save(product);
        // Invalidate cache across all instances
        cacheService.invalidate("product:" + product.getId());
    }
}
```

#### Option B: Annotation-Based Caching

Use the `@CacheX` annotation for declarative caching:

```java
@Service
public class ProductService {
    
    @CacheX(key = "'product:' + #productId")
    public Product getProduct(String productId) {
        // This method will be cached automatically
        return productRepository.findById(productId).orElse(null);
    }
    
    @CacheX(key = "'product:' + #product.id", operation = CacheX.Operation.EVICT)
    public void updateProduct(Product product) {
        productRepository.save(product);
        // Cache will be invalidated after method execution
    }
    
    @CacheX(key = "'products:featured'")
    public List<Product> getFeaturedProducts() {
        return productRepository.findFeatured();
    }
}
```

## Annotation-Based Caching with @CacheX

The `@CacheX` annotation provides a declarative way to add caching to your methods using Spring AOP.

### Basic Usage

```java
@CacheX(key = "'user:' + #userId")
public User findUserById(Long userId) {
    return userRepository.findById(userId).orElse(null);
}
```

### Cache Operations

#### GET Operation (Default)
Caches the method result and returns cached values on subsequent calls:

```java
@CacheX(key = "'product:' + #id")  // operation = GET is default
public Product getProduct(Long id) {
    return productRepository.findById(id).orElse(null);
}
```

#### EVICT Operation
Invalidates the cache after successful method execution:

```java
@CacheX(key = "'product:' + #product.id", operation = CacheX.Operation.EVICT)
public void updateProduct(Product product) {
    productRepository.save(product);
}
```

### SpEL Expression Support

Cache keys support Spring Expression Language (SpEL) for dynamic key generation:

```java
// Method parameters
@CacheX(key = "'user:' + #userId + ':profile'")
public UserProfile getUserProfile(Long userId) { ... }

// Object properties
@CacheX(key = "'order:' + #order.id + ':items'")
public List<OrderItem> getOrderItems(Order order) { ... }

// Complex expressions
@CacheX(key = "'search:' + #query.hashCode() + ':' + #page")
public SearchResult search(SearchQuery query, int page) { ... }

// Static expressions
@CacheX(key = "'global:settings'")
public Settings getGlobalSettings() { ... }
```

### Type Safety

The annotation-based caching preserves full type safety using the method's return type:

```java
@CacheX(key = "'users:active'")
public List<User> getActiveUsers() {
    // Return type List<User> is preserved in cache
    return userRepository.findActive();
}

@CacheX(key = "'config:' + #key")
public Optional<String> getConfigValue(String key) {
    // Optional<String> type is preserved
    return configRepository.findByKey(key);
}
```

### Best Practices

1. **Key Design**: Use consistent, hierarchical key patterns
   ```java
   @CacheX(key = "'entity:type:' + #id")  // Good
   @CacheX(key = "#id + '_cache'")        // Avoid
   ```

2. **Parameter Validation**: Ensure parameters are not null when used in keys
   ```java
   @CacheX(key = "'user:' + (#userId != null ? #userId : 'unknown')")
   public User getUser(Long userId) { ... }
   ```

3. **Eviction Patterns**: Use consistent keys for cache and eviction
   ```java
   @CacheX(key = "'product:' + #id")
   public Product getProduct(Long id) { ... }
   
   @CacheX(key = "'product:' + #product.id", operation = CacheX.Operation.EVICT)
   public void updateProduct(Product product) { ... }
   ```

4. **Null Handling**: The cache handles null return values automatically
   ```java
   @CacheX(key = "'user:' + #id")
   public User findUser(Long id) {
       return userRepository.findById(id).orElse(null); // null is cached too
   }
   ```

### Comparison with Programmatic API

| Feature | Annotation `@CacheX` | Programmatic `CacheService` |
|---------|---------------------|------------------------------|
| **Syntax** | Declarative, clean | Imperative, explicit |
| **AOP Integration** | Automatic | Manual |
| **Key Expression** | SpEL support | String concatenation |
| **Type Safety** | Automatic | Manual type reference |
| **Method Wrapping** | Transparent | Explicit loader function |
| **Cache-aside Pattern** | Built-in | Manual implementation |

Choose annotations for clean, declarative caching, and the programmatic API for complex caching logic or dynamic key generation.

## Advanced Usage: Type-Safe Generics

Safely cache complex generic types using `ParameterizedTypeReference`.

```java
// Cache a list of products
List<Product> products = cacheService.getOrLoad(
    "products:featured", 
    new ParameterizedTypeReference<List<Product>>() {},
    () -> productRepository.findFeatured()
);

// Cache a map of configurations
Map<String, Config> settings = cacheService.getOrLoad(
    "settings:global",
    new ParameterizedTypeReference<Map<String, Config>>() {},
    () -> settingsRepository.loadAll()
);
```

## Configuration

All settings are available under the `caching` prefix.

| Property                       | Description                               | Default                                |
|--------------------------------|-------------------------------------------|----------------------------------------|
| `caching.enabled`              | Globally enables or disables the library. | `true`                                 |
| `caching.l1.enabled`           | Enables the L1 (Caffeine) cache.          | `true`                                 |
| `caching.l1.spec`              | Caffeine specification string.            | `"maximumSize=500,expireAfterWrite=10m"` |
| `caching.l2.enabled`           | Enables the L2 (Redis) cache.             | `true`                                 |
| `caching.l2.ttl`               | Default TTL for Redis entries (ISO-8601). | `PT1H` (1 hour)                        |
| `caching.l2.invalidation-topic`| Redis topic for invalidation messages.    | `"cache:invalidation"`                 |
| `caching.async.core-pool-size` | Core thread pool size for async tasks.    | `2`                                    |

For a complete list of options, see the `CachingProperties` class in the starter module.

## Modules

The project is divided into several modules:

- **[caching-core](caching-core/README.md)**: Contains the core interfaces (`CacheService`, `CacheProvider`) and the `MultiLevelCacheService` implementation.
- **[caching-caffeine-adapter](caching-caffeine-adapter/README.md)**: L1 cache adapter powered by Caffeine.
- **[caching-redis-adapter](caching-redis-adapter/README.md)**: L2 cache adapter powered by Redis.
- **[caching-spring-boot-starter](caching-spring-boot-starter/README.md)**: Provides auto-configuration and dependency management for Spring Boot applications.

## Contributing

Contributions are welcome! If you'd like to help, please feel free to:
- Open an issue to report a bug or suggest a feature.
- Fork the repository and submit a pull request.
- Improve the documentation.
