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

## Monitoring and Health

### Actuator Integration

The starter integrates with Spring Boot Actuator:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,caches
  endpoint:
    health:
      show-details: always
```

### Cache Statistics

Enable Caffeine statistics:

```yaml
caching:
  l1:
    spec: "maximumSize=1000,expireAfterWrite=10m,recordStats"
```

Access via JMX or custom endpoints.

## Monitoring with Micrometer

If you are using Spring Boot Actuator, metrics for the L1 cache (Caffeine) can be exposed automatically.

### 1. Add the Actuator dependency

Make sure `spring-boot-starter-actuator` is in your `pom.xml`:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
```

### 2. Enable Caffeine Statistics

For metrics to be collected, you must include `recordStats` in the L1 cache specification in your `application.yml`:

```yaml
caching:
  l1:
    enabled: true
    spec: "maximumSize=1000,expireAfterWrite=10m,recordStats" # Add recordStats here
```

### 3. Expose the Metrics Endpoint

Expose the `/actuator/metrics` endpoint in your `application.yml`:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health, info, metrics
```

Once configured, you will be able to see cache metrics (like `cache.gets`, `cache.puts`, `cache.size`, `cache.evictions`) by visiting the `/actuator/metrics/cache.gets?tag=cache:l1Cache` endpoint.

## Troubleshooting

### Common Issues

1. **Redis Not Available**
   - L2 cache will be disabled automatically
   - Application continues with L1 cache only

2. **Serialization Issues**
   - Ensure cached objects are JSON-serializable
   - Check ObjectMapper configuration

3. **Memory Issues**
   - Monitor L1 cache size limits
   - Adjust Caffeine specifications

### Debug Logging

```yaml
logging:
  level:
    com.pedromossi.caching: DEBUG
    org.springframework.data.redis: DEBUG
```

This will show:
- Cache configuration details
- Hit/miss statistics  
- Invalidation events
- Redis operations
- Error conditions

## Dependencies

The starter automatically pulls in required dependencies:

```xml
<!-- Automatically included -->
<dependency>
    <groupId>com.pedromossi</groupId>
    <artifactId>caching-core</artifactId>
</dependency>
<dependency>
    <groupId>com.pedromossi</groupId>
    <artifactId>caching-caffeine-adapter</artifactId>
</dependency>
<dependency>
    <groupId>com.pedromossi</groupId>
    <artifactId>caching-redis-adapter</artifactId>
</dependency>
```
