# Caching-Spring-Boot-Starter Module

The Spring Boot starter provides auto-configuration and seamless integration of the caching library with Spring Boot applications, including automatic setup for annotation-based caching.

## Overview

This module provides:

- **Auto-Configuration**: Automatic bean creation and configuration
- **Property Binding**: Type-safe configuration via `application.yml`
- **Conditional Beans**: Smart activation based on classpath and properties
- **Redis Integration**: Automatic Redis listener setup for invalidation
- **Annotation Support**: Automatic `@CacheX` aspect configuration
- **Zero Configuration**: Works out-of-the-box with sensible defaults

## Features

- **Automatic Bean Creation**: Creates `CacheService`, `CacheProvider` beans
- **AOP Integration**: Automatically configures `CacheXAspect` for annotation support
- **Configuration Properties**: Externalized configuration via properties
- **Conditional Loading**: Adapters only load when dependencies are present
- **Redis Pub/Sub Setup**: Automatic invalidation listener configuration
- **Health Checks**: Integration with Spring Boot health indicators

## Auto-Configuration

### CachingAutoConfiguration

The main auto-configuration class that:

1. **Creates L1 Cache**: Caffeine adapter when enabled
2. **Creates L2 Cache**: Redis adapter when Redis is available
3. **Sets up Pub/Sub**: Redis message listeners for invalidation
4. **Configures Service**: Multi-level cache service with both adapters
5. **Enables AOP**: Automatically configures `CacheXAspect` for `@CacheX` annotation support

### Annotation Support Configuration

```java
@Bean
public CacheXAspect cacheXAspect(CacheService cacheService) {
    return new CacheXAspect(cacheService);
}
```

The starter automatically:
- Creates the `CacheXAspect` bean
- Enables Spring AOP processing for `@CacheX` annotations
- Integrates the aspect with the configured `CacheService`

### Conditional Bean Creation

```java
@Bean
@ConditionalOnProperty(name = "caching.l1.enabled", havingValue = "true", matchIfMissing = true)
public CacheProvider l1CacheProvider(CachingProperties properties) {
    return new CaffeineCacheAdapter(properties.getL1().getSpec());
}
```

**Conditions:**
- **L1 Cache**: Created when `caching.l1.enabled=true` (default)
- **L2 Cache**: Created when Redis is available and `caching.l2.enabled=true`
- **Redis Config**: Only loads when `RedisTemplate` is on classpath
- **Message Listener**: Only when Redis is configured
- **CacheX Aspect**: Always created when caching is enabled

## Configuration Properties

### CachingProperties

Type-safe configuration binding for all cache settings:

```java
@ConfigurationProperties(prefix = "caching")
public class CachingProperties {
    private boolean enabled = true;
    private L1CacheProperties l1 = new L1CacheProperties();
    private L2CacheProperties l2 = new L2CacheProperties();
}
```

### Property Structure

```yaml
caching:
  enabled: true                    # Global enable/disable
  l1:                             # L1 (Caffeine) configuration
    enabled: true
    spec: "maximumSize=500,expireAfterWrite=10m"
  l2:                             # L2 (Redis) configuration  
    enabled: true
    ttl: PT1H
    invalidationTopic: "cache:invalidation"
```

## Redis Integration

### Automatic Redis Template

```java
@Bean
@ConditionalOnMissingBean(name = "cacheRedisTemplate")
public RedisTemplate<String, Object> cacheRedisTemplate(
        RedisConnectionFactory connectionFactory, 
        ObjectMapper objectMapper) {
    RedisTemplate<String, Object> template = new RedisTemplate<>();
    template.setConnectionFactory(connectionFactory);
    template.setKeySerializer(new StringRedisSerializer());
    template.setValueSerializer(new GenericJackson2JsonRedisSerializer(objectMapper));
    return template;
}
```

### Redis Object Serialization

The Redis adapter uses Jackson (JSON) for object serialization with enhanced type safety. The auto-configuration creates a secure ObjectMapper copy that:

- **Preserves Configuration**: Inherits all modules and settings from the main ObjectMapper
- **Disables Default Typing**: Prevents deserialization vulnerabilities by not storing `@class` information
- **Uses Type References**: Relies on `ParameterizedTypeReference` for proper deserialization
- **Secure by Design**: No automatic class instantiation from JSON metadata

**Important**: Objects are serialized as pure JSON without type metadata. Deserialization uses the type information provided via `ParameterizedTypeReference` in the cache service methods.

```java
// This is how type-safe deserialization works:
// 1. Object stored as: {"id": 123, "name": "John"}
// 2. Retrieved with type info: new ParameterizedTypeReference<User>(){}
// 3. ObjectMapper converts JSON → User instance
```

If you need custom serialization behavior, you can define your own RedisTemplate bean with the name `cacheRedisTemplate`.

```java
@Configuration
public class CustomCacheConfig {
    
    @Bean
    @Primary // Ensures this bean takes precedence
    public RedisTemplate<String, Object> cacheRedisTemplate(
            RedisConnectionFactory factory) {
        
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        
        // Your custom serialization logic here
        template.setValueSerializer(new GenericJackson2JsonRedisSerializer()); 
        
        return template;
    }
}
```

### Invalidation Handler

Automatic setup of Redis pub/sub listener:

```java
public static class InvalidationHandler {
    public void handleMessage(String message) {
        if (l1Cache != null) {
            log.info("Received invalidation message from Redis. Invalidating L1 key: {}", message);
            l1Cache.evict(message);
        }
    }
}
```

## Asynchronous Operations

To improve performance and reduce latency, write operations (`put`) and invalidation operations (`evict`) are executed asynchronously using a dedicated thread pool. This means your application doesn't need to wait for Redis communication to complete before continuing processing.

### Configuring the Thread Pool

You can customize the `ExecutorService` used for asynchronous operations through the following properties in your `application.yml`:

```yaml
caching:
  async:
    core-pool-size: 2      # Base number of threads
    max-pool-size: 50      # Maximum number of threads  
    queue-capacity: 1000  # Task queue capacity
```

### Benefits

- **Reduced Latency**: Cache operations don't block your main application flow
- **Better Throughput**: Multiple cache operations can be processed concurrently
- **Resilience**: Application continues working even if Redis operations are slow
- **Resource Management**: Configurable thread pool prevents resource exhaustion

### Thread Pool Configuration

The default configuration provides:
- **Core Pool Size**: 2 threads always available
- **Max Pool Size**: Up to 50 threads during peak load
- **Queue Capacity**: 10,000 pending operations can be queued

Adjust these values based on your application's cache usage patterns and system resources.

## Usage

### 1. Add Dependency

```xml
<dependency>
    <groupId>com.pedromossi</groupId>
    <artifactId>caching-spring-boot-starter</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### 2. Configure Properties

```yaml
# Minimal configuration (uses defaults)
caching:
  enabled: true

# Full configuration example  
caching:
  enabled: true
  l1:
    enabled: true
    spec: "maximumSize=1000,expireAfterWrite=5m,recordStats"
  l2:
    enabled: true
    ttl: PT30M
    invalidationTopic: "myapp:cache:invalidation"

# Redis configuration (if using L2)
spring:
  redis:
    host: localhost
    port: 6379
```

### 3. Use in Application

#### Option A: Programmatic API

```java
@Service
public class UserService {
    
    @Autowired
    private CacheService cacheService;
    
    @Autowired  
    private UserRepository userRepository;
    
    public User getUser(Long userId) {
        return cacheService.getOrLoad(
            "user:" + userId,
            User.class,
            () -> userRepository.findById(userId).orElse(null)
        );
    }
    
    public void updateUser(User user) {
        userRepository.save(user);
        cacheService.invalidate("user:" + user.getId());
    }
}
```

#### Option B: Annotation-Based API

```java
@Service
public class UserService {
    
    @Autowired  
    private UserRepository userRepository;
    
    @CacheX(key = "'user:' + #userId")
    public User getUser(Long userId) {
        return userRepository.findById(userId).orElse(null);
    }
    
    @CacheX(key = "'user:' + #user.id", operation = CacheX.Operation.EVICT)
    public void updateUser(User user) {
        userRepository.save(user);
    }
    
    @CacheX(key = "'users:active'")
    public List<User> getActiveUsers() {
        return userRepository.findByActiveTrue();
    }
    
    @CacheX(key = "'users:department:' + #departmentId", operation = CacheX.Operation.EVICT)
    public void evictDepartmentUsers(Long departmentId) {
        // Cache eviction method - implementation can be empty
    }
}
```

#### Option C: Mixed Approach

You can combine both approaches in the same application:

```java
@Service
public class ProductService {
    
    @Autowired
    private CacheService cacheService;
    
    @Autowired
    private ProductRepository productRepository;
    
    // Use annotation for simple cases
    @CacheX(key = "'product:' + #id")
    public Product getProduct(Long id) {
        return productRepository.findById(id).orElse(null);
    }
    
    // Use programmatic API for complex logic
    public List<Product> getProductsWithComplexLogic(SearchCriteria criteria) {
        String key = "products:search:" + criteria.hashCode();
        
        return cacheService.getOrLoad(key, 
            new ParameterizedTypeReference<List<Product>>() {},
            () -> {
                // Complex search logic here
                if (criteria.hasAdvancedFilters()) {
                    return productRepository.searchAdvanced(criteria);
                } else {
                    return productRepository.searchBasic(criteria);
                }
            }
        );
    }
}
```

## AOP Requirements

For `@CacheX` annotations to work, ensure:

1. **Spring AOP is enabled** (automatic with Spring Boot)
2. **Classes are Spring-managed beans** (annotated with `@Service`, `@Component`, etc.)
3. **Methods are public** (AOP limitation)
4. **Self-invocation is avoided** (call annotated methods from other beans)

### Common AOP Pitfalls

```java
@Service
public class UserService {
    
    @CacheX(key = "'user:' + #id")
    public User getUser(Long id) {
        return userRepository.findById(id).orElse(null);
    }
    
    public void someMethod() {
        // ❌ This won't trigger caching (self-invocation)
        User user = this.getUser(123L);
        
        // ✅ This will trigger caching (through proxy)
        // Inject another service and call its @CacheX methods
    }
}
```

## Configuration Examples

### Development Profile
```yaml
# application-dev.yml
caching:
  enabled: true
  l1:
    enabled: true
    spec: "maximumSize=100,expireAfterWrite=1m,recordStats"
  l2:
    enabled: false  # No Redis in development
```

### Production Profile
```yaml
# application-prod.yml
caching:
  enabled: true
  l1:
    enabled: true
    spec: "maximumSize=1000,expireAfterWrite=10m"
  l2:
    enabled: true
    ttl: PT1H
    invalidationTopic: "prod:cache:invalidation"

spring:
  redis:
    host: redis-cluster.prod.com
    port: 6379
    password: ${REDIS_PASSWORD}
```

### Testing Profile
```yaml
# application-test.yml
caching:
  enabled: false  # Disable caching in tests
```

## Customization

### Custom Cache Providers

You can override default beans:

```java
@Configuration
public class CustomCacheConfig {
    
    @Bean
    @Primary
    public CacheProvider customL1Cache() {
        return new CaffeineCacheAdapter("maximumSize=5000,expireAfterWrite=30m");
    }
    
    @Bean("l2CacheProvider")
    public CacheProvider customL2Cache(RedisTemplate<String, Object> redisTemplate) {
        return new RedisCacheAdapter(redisTemplate, "custom:topic", Duration.ofHours(2));
    }
}
```

### Custom Redis Template

```java
@Bean
public RedisTemplate<String, Object> cacheRedisTemplate(RedisConnectionFactory factory) {
    RedisTemplate<String, Object> template = new RedisTemplate<>();
    template.setConnectionFactory(factory);
    // Custom serialization configuration
    template.setKeySerializer(new StringRedisSerializer());
    template.setValueSerializer(new Jackson2JsonRedisSerializer<>(Object.class));
    return template;
}
```

## Monitoring and Observability

### Comprehensive Metrics System

The starter provides a dual-layer metrics system for complete cache observability:

#### 1. Granular Operation Metrics (MetricsCollectingCacheProvider)

When `MeterRegistry` is available (via `spring-boot-starter-actuator`), cache providers are automatically wrapped with `MetricsCollectingCacheProvider` to collect detailed metrics:

**Available Metrics:**
- `cache.operations.total` - Total cache operations with tags:
  - `cache.level`: "l1" or "l2"
  - `result`: "hit" or "miss" (for get operations)
- `cache.latency` - Operation latency with percentiles (50th, 95th, 99th):
  - `cache.level`: "l1" or "l2"
  - `operation`: "get", "put", or "evict"
  - `key.prefix`: Extracted key prefix for grouping
- `cache.errors.total` - Error tracking with tags:
  - `cache.level`: "l1" or "l2"
  - `operation`: "get", "put", or "evict"
  - `exception.type`: Exception class name
  - `key.prefix`: Extracted key prefix
- `cache.payload.size.bytes` - Payload size distribution (for put operations)

**Smart Key Prefix Extraction:**
Metrics use key prefixes (part before first `:`) to group related operations without causing high cardinality issues:
- `user:123` → prefix: `user`
- `product:456` → prefix: `product`
- `global-config` → prefix: `none`

#### 2. Native Caffeine Statistics

For L1 cache, enable Caffeine's built-in statistics by adding `recordStats` to the cache specification:

```yaml
caching:
  l1:
    spec: "maximumSize=1000,expireAfterWrite=10m,recordStats"
```

This automatically binds Caffeine metrics to Micrometer:
- `cache.gets` - Get operations count
- `cache.puts` - Put operations count
- `cache.evictions` - Eviction count
- `cache.size` - Current cache size
- `cache.hit.ratio` - Cache hit ratio

#### 3. CacheX Actuator Endpoint

Custom endpoint `/actuator/cachex` provides operational capabilities:

- **`GET /actuator/cachex`** - Cache layer status and available actions
- **`GET /actuator/cachex/{key}`** - Inspect specific cache key across layers
- **`DELETE /actuator/cachex/{key}`** - Evict key from all cache layers

**Example Response:**
```json
{
  "l1Cache": "ENABLED",
  "l2Cache": "ENABLED", 
  "actions": {
    "inspectKey": "GET /actuator/cachex/{key}",
    "evictKey": "DELETE /actuator/cachex/{key}"
  }
}
```

### Configuration for Metrics

#### Enable Comprehensive Monitoring

```yaml
# Enable actuator endpoints
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,cachex
  endpoint:
    health:
      show-details: always

# Enable both granular and native metrics
caching:
  l1:
    enabled: true
    spec: "maximumSize=1000,expireAfterWrite=10m,recordStats" # recordStats enables native metrics
  l2:
    enabled: true
    ttl: PT1H
```

#### Dependencies for Metrics

Add Spring Boot Actuator for complete metrics support:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
```

### Metrics Behavior Matrix

| MeterRegistry Available | recordStats Enabled | Granular Metrics | Native Caffeine Metrics |
|------------------------|-------------------|------------------|-------------------------|
| ✅ Yes | ✅ Yes | ✅ Enabled | ✅ Enabled |
| ✅ Yes | ❌ No | ✅ Enabled | ❌ Disabled |
| ❌ No | ✅ Yes | ❌ Disabled | ❌ Disabled |
| ❌ No | ❌ No | ❌ Disabled | ❌ Disabled |

**Note**: Granular metrics are automatically enabled when `MeterRegistry` is detected. Native Caffeine metrics require explicit `recordStats` in the L1 spec.

### Example Metrics Queries

```bash
# Granular operation metrics
curl "/actuator/metrics/cache.operations.total?tag=cache.level:l1&tag=result:hit"
curl "/actuator/metrics/cache.latency?tag=cache.level:l2&tag=operation:get"
curl "/actuator/metrics/cache.errors.total?tag=cache.level:l1&tag=operation:put"

# Native Caffeine metrics (requires recordStats)
curl "/actuator/metrics/cache.gets?tag=cache:l1Cache"
curl "/actuator/metrics/cache.hit.ratio?tag=cache:l1Cache"
curl "/actuator/metrics/cache.size?tag=cache:l1Cache"

# CacheX endpoint
curl "/actuator/cachex"
curl "/actuator/cachex/user:123"
curl -X DELETE "/actuator/cachex/user:123"
```

### Monitoring Integration

The metrics are compatible with popular monitoring systems:

- **Prometheus**: Scrape `/actuator/prometheus` endpoint
- **Grafana**: Create dashboards using Micrometer metrics
- **New Relic/DataDog**: Automatic metric collection via Micrometer
- **Spring Boot Admin**: Built-in cache monitoring views

### Troubleshooting Metrics

**Granular metrics not appearing:**
```yaml
# Check if MeterRegistry is available
logging:
  level:
    com.pedromossi.caching.starter.CachingAutoConfiguration: DEBUG
```

Expected log: `"MeterRegistry found. Enabling granular metrics for L1 cache."`

**Native Caffeine metrics not appearing:**
- Ensure `recordStats` is in L1 spec
- Check `/actuator/metrics` for `cache.*` metrics with `cache:l1Cache` tag

## Circuit Breaker & Resilience Protection

The starter provides built-in circuit breaker protection for L2 (Redis) cache operations using Resilience4j, ensuring your application remains stable during Redis outages or performance issues.

### Automatic Circuit Breaker Configuration

When circuit breaker is enabled, the starter automatically:

1. **Wraps L2 Cache Provider**: The `CircuitBreakerCacheProvider` decorates the Redis adapter
2. **Monitors Operations**: Tracks failure rates, slow calls, and exception patterns
3. **Manages State Transitions**: Automatically opens/closes the circuit based on Redis health
4. **Provides Graceful Degradation**: Falls back to L1-only mode when L2 is unavailable

### Configuration

Enable and configure circuit breaker protection:

```yaml
caching:
  l2:
    enabled: true
    circuit-breaker:
      enabled: true                                    # Enable circuit breaker protection
      failure-rate-threshold: 50.0                    # Open circuit at 50% failure rate
      slow-call-rate-threshold: 100.0                 # Consider slow calls as failures  
      slow-call-duration-threshold: PT1S              # Calls > 1s are considered slow
      wait-duration-in-open-state: PT60S              # Wait 60s before testing recovery
      permitted-number-of-calls-in-half-open-state: 10 # Test recovery with 10 calls
```

### Circuit Breaker States & Behavior

#### CLOSED State (Normal Operation)
```yaml
# All cache operations proceed normally
L1 Hit: ✅ Served from Caffeine (fast)
L1 Miss + L2 Hit: ✅ Served from Redis + promoted to L1
L1 Miss + L2 Miss: ✅ Load from source + cache in both layers
```

#### OPEN State (Redis Unavailable)
```yaml
# Circuit breaker blocks L2 operations
L1 Hit: ✅ Served from Caffeine (unaffected)
L1 Miss: ⚡ Fast-fail → Direct to data source (no Redis wait)
Result: Application continues with degraded caching
```

#### HALF_OPEN State (Testing Recovery)
```yaml
# Limited requests test if Redis has recovered
Test Requests: 🟡 10 calls allowed to pass through
All Successful: ✅ Circuit CLOSED → Resume normal operation
Any Failure: 🔴 Circuit OPEN → Continue degraded mode
```

### Benefits

**Prevents Cascading Failures:**
- Redis timeouts don't block your application
- Failed Redis connections fail fast instead of hanging
- L1 cache continues serving frequently accessed data

**Automatic Recovery:**
- Circuit breaker periodically tests Redis availability
- Seamless transition back to normal operation when Redis recovers
- No manual intervention required

**Performance Protection:**
- Slow Redis operations are detected and bypassed
- Application latency remains stable during Redis performance issues
- Thread pool exhaustion is prevented

### Circuit Breaker Metrics

When the circuit breaker is enabled, additional metrics are available:

```bash
# Circuit breaker state metrics (via Resilience4j)
GET /actuator/metrics/resilience4j.circuitbreaker.state?tag=name:l2Cache
GET /actuator/metrics/resilience4j.circuitbreaker.failure.rate?tag=name:l2Cache
GET /actuator/metrics/resilience4j.circuitbreaker.slow.call.rate?tag=name:l2Cache

# Standard cache metrics continue working
GET /actuator/metrics/cache.operations.total?tag=cache.level:l2
GET /actuator/metrics/cache.errors.total?tag=cache.level:l2
```

### Example Scenarios

#### Scenario 1: Redis Connection Loss
```yaml
# Before failure
L1 Miss Rate: 20%
L2 Hit Rate: 80% 
Average Response: 50ms

# Redis goes down
L2 Failures: 100% → Circuit OPENS
L1 Miss → Database: Direct (no Redis wait)
Average Response: 45ms (still fast!)

# Redis recovers
Circuit HALF_OPEN: Test 10 requests
Success Rate: 100% → Circuit CLOSED
Back to normal operation
```

#### Scenario 2: Redis Performance Degradation
```yaml
# Normal operation
L2 Response Time: 50ms
Circuit State: CLOSED

# Network issues cause slow Redis
L2 Response Time: 1.5s (> threshold)
Slow Call Rate: 60% → Circuit OPENS
Fast-fail fallback: 45ms response maintained

# Network issues resolved
Circuit tests recovery automatically
L2 Response Time: 50ms → Circuit CLOSED
```

### Advanced Configuration

#### Custom Circuit Breaker Settings

```yaml
caching:
  l2:
    circuit-breaker:
      enabled: true
      # Sliding window configuration
      sliding-window-type: COUNT_BASED        # or TIME_BASED
      sliding-window-size: 100               # Last 100 calls
      minimum-number-of-calls: 10            # Need 10 calls before calculating rates
      
      # Failure thresholds
      failure-rate-threshold: 60.0           # 60% failures trigger OPEN
      slow-call-rate-threshold: 50.0         # 50% slow calls trigger OPEN
      slow-call-duration-threshold: PT2S     # 2 seconds is considered slow
      
      # Recovery settings
      wait-duration-in-open-state: PT30S     # Test recovery every 30s
      permitted-number-of-calls-in-half-open-state: 5  # Test with 5 calls
```

#### Integration with Spring Boot Profiles

```yaml
# application-dev.yml (disable in development)
caching:
  l2:
    circuit-breaker:
      enabled: false

# application-test.yml (disable in tests)
caching:
  l2:
    circuit-breaker:
      enabled: false

# application-prod.yml (production settings)
caching:
  l2:
    circuit-breaker:
      enabled: true
      failure-rate-threshold: 30.0      # More sensitive in production
      slow-call-duration-threshold: PT500MS  # Tighter latency requirements
```

### Monitoring Circuit Breaker Health

#### Log Messages

The circuit breaker logs state transitions:

```bash
# Enable circuit breaker logging
logging:
  level:
    com.pedromossi.caching.resilience.CircuitBreakerCacheProvider: INFO
    io.github.resilience4j.circuitbreaker: DEBUG
```

Expected log output:
```
INFO  - L2 Cache Circuit Breaker state changed: StateTransition{from=CLOSED, to=OPEN, ...}
WARN  - L2 Cache Circuit Breaker state changed: StateTransition{from=OPEN, to=HALF_OPEN, ...}
INFO  - L2 Cache Circuit Breaker state changed: StateTransition{from=HALF_OPEN, to=CLOSED, ...}
```

#### Health Indicators

Monitor circuit breaker health via actuator:

```bash
# Check overall health (includes circuit breaker status)
GET /actuator/health

# Detailed circuit breaker information
GET /actuator/circuitbreakerevents/l2Cache
GET /actuator/circuitbreakers
```

### Troubleshooting

**Circuit breaker not activating:**
- Verify `caching.l2.circuit-breaker.enabled=true`
- Check that `minimum-number-of-calls` threshold is reached
- Ensure failures exceed the configured `failure-rate-threshold`

**Too sensitive (frequent opening):**
- Increase `failure-rate-threshold` (e.g., from 50% to 70%)
- Increase `slow-call-duration-threshold` for network latency
- Increase `minimum-number-of-calls` for more stable measurements

**Too permissive (not opening when it should):**
- Decrease `failure-rate-threshold` (e.g., from 50% to 30%)
- Decrease `slow-call-duration-threshold` for stricter latency requirements
- Check that actual Redis failures are being detected

