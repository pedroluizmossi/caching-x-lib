# Caching-X: High-Performance Multi-Level Caching for Java

**Caching-X** is a powerful, type-safe, and resilient multi-level caching library for modern Java applications. It simplifies the complexity of managing local (L1) and distributed (L2) caches, providing a seamless and high-performance solution out of the box.

## Why Caching-X?

Managing multiple cache layers can be complex. Caching-X abstracts away the boilerplate, providing an intelligent, production-ready caching strategy that just works.

- **Effortless Performance**: Get the speed of an in-memory cache (L1) and the scalability of a distributed cache (L2) without the implementation overhead.
- **Built for Resilience**: Your application remains stable even if a cache layer fails.
- **Developer-Friendly API**: A clean, intuitive API that leverages modern Java features and full type safety.
- **Seamless Integration**: Auto-configured for Spring Boot, making setup a breeze.

## Key Features

- **🚀 Multi-Level Caching**: Combines a lightning-fast L1 cache (Caffeine) with a distributed L2 cache (Redis).
- **🧠 Intelligent Cache Promotion**: Automatically promotes L2 cache hits to L1, ensuring frequently accessed data is served at maximum speed.
- **🔄 Distributed Invalidation**: Keeps data consistent across all application instances with Redis Pub/Sub.
- **🛡️ Full Type Safety**: Generic-aware API using `ParameterizedTypeReference` to prevent `ClassCastException`.
- **✍️ Null Value Caching**: Prevents cache stampedes by safely caching `null` results from your data source.
- **⚡ Asynchronous Operations**: All cache writes and invalidations are non-blocking, keeping your application responsive.
- **💪 Error Resilience**: Gracefully handles cache layer failures without crashing your application.

## Architecture

Caching-X follows a clear and optimized data lookup strategy.

```
Application Request
       ↓
   CacheService
       ↓
┌─────────────────┐
│ L1 Cache (Fast) │ → Hit: Return immediately
└─────────────────┘
       ↓ Miss
┌─────────────────┐
│L2 Cache (Shared)│ → Hit: Promote to L1, return
└─────────────────┘
       ↓ Miss
┌─────────────────┐
│   Data Source   │ → Load, cache in both layers
└─────────────────┘
```

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

Inject `CacheService` and start caching.

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
