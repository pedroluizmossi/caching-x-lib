# Caching-Core Module

Core interfaces, multi-level cache implementation with intelligent cache promotion, null value handling, annotation-based caching support, and resilience patterns.

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

## Resilience & Circuit Breaker Support

The core module provides the `CircuitBreakerCacheProvider` for protecting against cache layer failures using the circuit breaker pattern.

### CircuitBreakerCacheProvider

A decorator that wraps any `CacheProvider` implementation with Resilience4j circuit breaker protection:

```java
public class CircuitBreakerCacheProvider implements CacheProvider {
    private final CacheProvider delegate;
    private final CircuitBreaker circuitBreaker;
    
    // Wraps all cache operations with circuit breaker protection
    // Monitors failures, slow calls, and automatically manages state transitions
}
```

**Key Features:**
- **Failure Detection**: Monitors exception rates and slow operation rates
- **Automatic State Management**: Transitions between CLOSED, OPEN, and HALF_OPEN states
- **Fast Failure**: Prevents cascading failures by failing fast when circuit is OPEN
- **Automatic Recovery**: Periodically tests for recovery and restores normal operation
- **Comprehensive Logging**: Logs all state transitions for monitoring and debugging

### Integration with Multi-Level Caching

When used with `MultiLevelCacheService`, the circuit breaker provides graceful degradation:

```java
// Circuit breaker typically wraps L2 (Redis) cache provider
// L1 (Caffeine) continues operating normally during L2 failures

L1 Hit: ✅ Served immediately (no circuit breaker involvement)
L1 Miss + Circuit CLOSED: ✅ Normal L2 lookup + promotion
L1 Miss + Circuit OPEN: ⚡ Skip L2, load from source directly
```

### Manual Configuration

For non-Spring Boot applications, you can manually configure circuit breaker protection:

```java
@Configuration
public class CacheResilienceConfig {
    
    @Bean
    public CircuitBreakerRegistry circuitBreakerRegistry() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
            .failureRateThreshold(50.0f)
            .slowCallRateThreshold(100.0f)
            .slowCallDurationThreshold(Duration.ofSeconds(1))
            .waitDurationInOpenState(Duration.ofSeconds(60))
            .permittedNumberOfCallsInHalfOpenState(10)
            .slidingWindowSize(100)
            .build();
            
        return CircuitBreakerRegistry.of(config);
    }
    
    @Bean
    public CacheProvider resilientL2Cache(
            CacheProvider l2CacheProvider,
            CircuitBreakerRegistry registry) {
        
        CircuitBreaker circuitBreaker = registry.circuitBreaker("l2Cache");
        return new CircuitBreakerCacheProvider(l2CacheProvider, circuitBreaker);
    }
}
```

### Circuit Breaker Events & Monitoring

The `CircuitBreakerCacheProvider` automatically logs state transitions:

```java
// State transition logging
circuitBreaker.getEventPublisher()
    .onStateTransition(event -> 
        log.warn("L2 Cache Circuit Breaker state changed: {}", event)
    );
```

**Logged Events:**
- `CLOSED → OPEN`: Circuit opens due to failures/slow calls
- `OPEN → HALF_OPEN`: Circuit tests for recovery
- `HALF_OPEN → CLOSED`: Circuit closes after successful recovery
- `HALF_OPEN → OPEN`: Circuit reopens due to continued failures

### Testing Circuit Breaker Behavior

The core module includes comprehensive tests demonstrating circuit breaker behavior:

```java
// Example test scenarios from CircuitBreakerCacheProviderTest:

@Test
void shouldOpenCircuitAfterFailuresAndBlockCalls() {
    // Simulates Redis failures triggering circuit breaker
    // Verifies OPEN state blocks subsequent calls
}

@Test
void shouldTransitionToHalfOpenAndCloseOnSuccess() {
    // Tests recovery mechanism
    // Verifies successful calls close the circuit
}

@Test
void shouldOpenCircuitOnSlowCalls() {
    // Tests slow call detection
    // Verifies latency-based circuit opening
}
```

### Error Handling & Resilience Patterns

The circuit breaker implements several resilience patterns:

**Fail-Fast Pattern:**
```java
// When circuit is OPEN
L2 Operation → CircuitBreaker → CallNotPermittedException (immediate)
// No waiting for timeouts, no resource exhaustion
```

**Bulkhead Pattern:**
```java
// L1 and L2 caches are isolated
L1 Failure: ❌ L2 continues normally
L2 Failure: ❌ L1 continues normally (circuit breaker protects)
```

**Graceful Degradation:**
```java
// Application continues with reduced caching capability
Normal: L1 + L2 caching
Degraded: L1-only caching + direct data source access
```

## Metrics Integration

The core module provides the foundation for comprehensive metrics collection through the `MetricsCollectingCacheProvider` decorator:

### MetricsCollectingCacheProvider

A decorator that wraps any `CacheProvider` implementation to collect detailed operation metrics:

```java
public class MetricsCollectingCacheProvider implements CacheProvider {
    // Wraps existing cache providers with metrics collection
    // Automatically applied by Spring Boot starter when MeterRegistry is available
}
```

**Collected Metrics:**
- **Operation Counts**: Total cache operations with hit/miss classification
- **Latency Tracking**: Response times with percentile distributions (50th, 95th, 99th)
- **Error Monitoring**: Exception tracking with detailed context
- **Payload Analysis**: Size distribution for stored data

**Smart Key Grouping:**
Uses key prefix extraction (`user:123` → `user`) to avoid metric cardinality explosion while maintaining useful grouping.

### Integration with Spring Boot Starter

When used with the Spring Boot starter:
- Automatically wraps L1 and L2 cache providers when `MeterRegistry` is present
- Provides dual-layer metrics: custom granular metrics + native Caffeine statistics
- Exposes metrics via standard Spring Boot Actuator endpoints
- No code changes required - metrics are collected transparently

### Manual Integration

For non-Spring Boot applications:

```java
@Configuration
public class CacheMetricsConfig {
    
    @Bean
    public CacheProvider metricsEnabledL1Cache(MeterRegistry meterRegistry) {
        CacheProvider caffeine = new CaffeineCacheAdapter("maximumSize=1000");
        return new MetricsCollectingCacheProvider(caffeine, meterRegistry, "l1");
    }
}
```

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

**Metrics-Related Logging:**
```yaml
logging:
  level:
    com.pedromossi.caching.micrometer.MetricsCollectingCacheProvider: DEBUG
```

This will show:
- Metric collection events
- Key prefix extraction results
- Performance measurement details
- Error classification logs

**Circuit Breaker Logging:**
```yaml
logging:
  level:
    com.pedromossi.caching.resilience.CircuitBreakerCacheProvider: INFO
    io.github.resilience4j.circuitbreaker: DEBUG
```

This will show:
- Circuit breaker state transitions
- Failure and slow call detection
- Recovery attempts and results
- Performance impact of circuit breaker operations

> For complete metrics and circuit breaker configuration and monitoring setup, see [caching-spring-boot-starter/README.md](../caching-spring-boot-starter/README.md#monitoring-and-observability).
