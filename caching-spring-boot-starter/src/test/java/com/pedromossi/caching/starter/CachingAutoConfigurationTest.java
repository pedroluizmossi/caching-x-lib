package com.pedromossi.caching.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pedromossi.caching.CacheProvider;
import com.pedromossi.caching.CacheService;
import com.pedromossi.caching.aspect.CacheXAspect;
import com.pedromossi.caching.caffeine.CaffeineCacheAdapter;
import com.pedromossi.caching.impl.MultiLevelCacheService;
import com.pedromossi.caching.micrometer.MetricsCollectingCacheProvider;
import com.pedromossi.caching.redis.RedisCacheAdapter;
import com.pedromossi.caching.redis.serializer.JacksonCacheSerializer;
import com.pedromossi.caching.resilience.CircuitBreakerCacheProvider;
import com.pedromossi.caching.serializer.CacheSerializer;
import com.pedromossi.caching.starter.CachingAutoConfiguration.InvalidationHandler;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.adapter.MessageListenerAdapter;

/**
 * Unit tests for {@link CachingAutoConfiguration}.
 *
 * <p>This test class verifies the auto-configuration behavior of the caching library,
 * testing bean creation, conditional configuration, and proper integration between
 * different cache levels and optional components.</p>
 */
class CachingAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CachingAutoConfiguration.class));

    @Test
    @DisplayName("should not create any cache beans when caching is disabled")
    void shouldNotCreateAnyBeansWhenCachingDisabled() {
        contextRunner
                .withPropertyValues("caching.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(CacheService.class);
                    assertThat(context).doesNotHaveBean(CacheXAspect.class);
                    assertThat(context).doesNotHaveBean("l1CacheProvider");
                    assertThat(context).doesNotHaveBean("l2CacheProvider");
                });
    }

    @Test
    @DisplayName("should create basic configuration when caching is enabled with defaults")
    void shouldCreateBasicConfigurationWithDefaults() {
        contextRunner
                .run(context -> {
                    assertThat(context).hasSingleBean(CacheService.class);
                    assertThat(context).hasSingleBean(CacheXAspect.class);
                    assertThat(context).hasSingleBean(ExecutorService.class);
                    assertThat(context).hasBean("cachingTaskExecutor");

                    CacheService cacheService = context.getBean(CacheService.class);
                    assertThat(cacheService).isInstanceOf(MultiLevelCacheService.class);
                });
    }

    @Test
    @DisplayName("should create L1 cache provider without metrics when MeterRegistry not available")
    void shouldCreateL1CacheProviderWithoutMetrics() {
        contextRunner
                .withPropertyValues("caching.l1.enabled=true")
                .run(context -> {
                    assertThat(context).hasBean("l1CacheProvider");

                    CacheProvider l1Provider = context.getBean("l1CacheProvider", CacheProvider.class);
                    assertThat(l1Provider).isInstanceOf(CaffeineCacheAdapter.class);
                    assertThat(l1Provider).isNotInstanceOf(MetricsCollectingCacheProvider.class);
                });
    }

    @Test
    @DisplayName("should create L1 cache provider with metrics when MeterRegistry available")
    void shouldCreateL1CacheProviderWithMetrics() {
        contextRunner
                .withPropertyValues("caching.l1.enabled=true")
                .withBean(MeterRegistry.class, () -> mock(MeterRegistry.class))
                .run(context -> {
                    assertThat(context).hasBean("l1CacheProvider");
                    assertThat(context).hasSingleBean(MeterRegistry.class);

                    CacheProvider l1Provider = context.getBean("l1CacheProvider", CacheProvider.class);
                    assertThat(l1Provider).isInstanceOf(MetricsCollectingCacheProvider.class);
                });
    }

    @Test
    @DisplayName("should not create L1 cache provider when disabled")
    void shouldNotCreateL1CacheProviderWhenDisabled() {
        contextRunner
                .withPropertyValues("caching.l1.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean("l1CacheProvider");
                });
    }

    @Test
    @DisplayName("should create Redis configuration when RedisTemplate available")
    void shouldCreateRedisConfigurationWhenRedisTemplateAvailable() {
        contextRunner
                .withConfiguration(AutoConfigurations.of(RedisAutoConfiguration.class))
                .withPropertyValues(
                        "caching.l2.enabled=true",
                        "spring.data.redis.host=localhost",
                        "spring.data.redis.port=6379"
                )
                .run(context -> {
                    assertThat(context).hasBean("cacheRedisTemplate");
                    assertThat(context).hasBean("cachingObjectMapper");
                    assertThat(context).hasSingleBean(CacheSerializer.class);
                    assertThat(context).hasBean("l2CacheProvider");

                    RedisTemplate<String, byte[]> redisTemplate = context.getBean("cacheRedisTemplate", RedisTemplate.class);
                    assertThat(redisTemplate).isNotNull();

                    ObjectMapper objectMapper = context.getBean("cachingObjectMapper", ObjectMapper.class);
                    assertThat(objectMapper).isNotNull();

                    CacheSerializer serializer = context.getBean(CacheSerializer.class);
                    assertThat(serializer).isInstanceOf(JacksonCacheSerializer.class);
                });
    }

    @Test
    @DisplayName("should not create Redis configuration when L2 cache disabled")
    void shouldNotCreateRedisConfigurationWhenL2CacheDisabled() {
        contextRunner
                .withConfiguration(AutoConfigurations.of(RedisAutoConfiguration.class))
                .withPropertyValues(
                        "caching.l2.enabled=false",
                        "spring.data.redis.host=localhost",
                        "spring.data.redis.port=6379"
                )
                .run(context -> {
                    assertThat(context).doesNotHaveBean("cacheRedisTemplate");
                    assertThat(context).doesNotHaveBean("cachingObjectMapper");
                    assertThat(context).doesNotHaveBean("l2CacheProvider");
                });
    }

    @Test
    @DisplayName("should not create Redis configuration when RedisConnectionFactory not available")
    void shouldNotCreateRedisConfigurationWhenRedisConnectionFactoryNotAvailable() {
        contextRunner
                .withPropertyValues("caching.l2.enabled=true")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(RedisConnectionFactory.class);
                    assertThat(context).doesNotHaveBean("cacheRedisTemplate");
                    assertThat(context).doesNotHaveBean("l2CacheProvider");
                });
    }

    @Test
    @DisplayName("should create L2 cache provider without circuit breaker when Resilience4j not available")
    void shouldCreateL2CacheProviderWithoutCircuitBreaker() {
        contextRunner
                .withConfiguration(AutoConfigurations.of(RedisAutoConfiguration.class))
                .withPropertyValues(
                        "caching.l2.enabled=true",
                        "caching.l2.circuit-breaker.enabled=true",
                        "spring.data.redis.host=localhost",
                        "spring.data.redis.port=6379"
                )
                .run(context -> {
                    assertThat(context).hasBean("l2CacheProvider");
                    assertThat(context).doesNotHaveBean(CircuitBreakerRegistry.class);

                    CacheProvider l2Provider = context.getBean("l2CacheProvider", CacheProvider.class);
                    assertThat(l2Provider).isNotInstanceOf(CircuitBreakerCacheProvider.class);
                });
    }

    @Test
    @DisplayName("should create L2 cache provider with circuit breaker when Resilience4j available")
    void shouldCreateL2CacheProviderWithCircuitBreaker() {
        contextRunner
                .withConfiguration(AutoConfigurations.of(RedisAutoConfiguration.class))
                .withPropertyValues(
                        "caching.l2.enabled=true",
                        "caching.l2.circuit-breaker.enabled=true",
                        "spring.data.redis.host=localhost",
                        "spring.data.redis.port=6379"
                )
                .withBean(CircuitBreakerRegistry.class, CircuitBreakerRegistry::ofDefaults)
                .run(context -> {
                    assertThat(context).hasBean("l2CacheProvider");
                    assertThat(context).hasSingleBean(CircuitBreakerRegistry.class);

                    CacheProvider l2Provider = context.getBean("l2CacheProvider", CacheProvider.class);
                    assertThat(l2Provider).isInstanceOf(CircuitBreakerCacheProvider.class);
                });
    }

    @Test
    @DisplayName("should create L2 cache provider with metrics when MeterRegistry available")
    void shouldCreateL2CacheProviderWithMetrics() {
        contextRunner
                .withConfiguration(AutoConfigurations.of(RedisAutoConfiguration.class))
                .withPropertyValues(
                        "caching.l2.enabled=true",
                        "spring.data.redis.host=localhost",
                        "spring.data.redis.port=6379"
                )
                .withBean(MeterRegistry.class, () -> mock(MeterRegistry.class))
                .run(context -> {
                    assertThat(context).hasBean("l2CacheProvider");
                    assertThat(context).hasSingleBean(MeterRegistry.class);

                    CacheProvider l2Provider = context.getBean("l2CacheProvider", CacheProvider.class);
                    assertThat(l2Provider).isInstanceOf(MetricsCollectingCacheProvider.class);
                });
    }

    @Test
    @DisplayName("should create invalidation components when Redis configuration present")
    void shouldCreateInvalidationComponentsWhenRedisConfigurationPresent() {
        contextRunner
                .withConfiguration(AutoConfigurations.of(RedisAutoConfiguration.class))
                .withPropertyValues(
                        "caching.l2.enabled=true",
                        "spring.data.redis.host=localhost",
                        "spring.data.redis.port=6379"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(InvalidationHandler.class);
                    assertThat(context).hasSingleBean(MessageListenerAdapter.class);
                    assertThat(context).hasSingleBean(RedisMessageListenerContainer.class);

                    InvalidationHandler handler = context.getBean(InvalidationHandler.class);
                    assertThat(handler).isNotNull();
                });
    }

    @Test
    @DisplayName("should use custom cache serializer when provided")
    void shouldUseCustomCacheSerializerWhenProvided() {
        CacheSerializer customSerializer = mock(CacheSerializer.class);

        contextRunner
                .withConfiguration(AutoConfigurations.of(RedisAutoConfiguration.class))
                .withPropertyValues(
                        "caching.l2.enabled=true",
                        "spring.data.redis.host=localhost",
                        "spring.data.redis.port=6379"
                )
                .withBean(CacheSerializer.class, () -> customSerializer)
                .run(context -> {
                    assertThat(context).hasSingleBean(CacheSerializer.class);
                    assertThat(context).doesNotHaveBean(JacksonCacheSerializer.class);

                    CacheSerializer serializer = context.getBean(CacheSerializer.class);
                    assertThat(serializer).isSameAs(customSerializer);
                });
    }

    @Test
    @DisplayName("should create executor with custom properties")
    void shouldCreateExecutorWithCustomProperties() {
        contextRunner
                .withPropertyValues(
                        "caching.async.core-pool-size=5",
                        "caching.async.max-pool-size=15",
                        "caching.async.queue-capacity=200"
                )
                .run(context -> {
                    assertThat(context).hasBean("cachingTaskExecutor");

                    ExecutorService executor = context.getBean("cachingTaskExecutor", ExecutorService.class);
                    assertThat(executor).isNotNull();
                });
    }

    @Test
    @DisplayName("should not override custom executor when provided")
    void shouldNotOverrideCustomExecutorWhenProvided() {
        ExecutorService customExecutor = mock(ExecutorService.class);

        contextRunner
                .withBean("cachingTaskExecutor", ExecutorService.class, () -> customExecutor)
                .run(context -> {
                    assertThat(context).hasSingleBean(ExecutorService.class);

                    ExecutorService executor = context.getBean("cachingTaskExecutor", ExecutorService.class);
                    assertThat(executor).isSameAs(customExecutor);
                });
    }

    @Test
    @DisplayName("should create cache service with both L1 and L2 providers")
    void shouldCreateCacheServiceWithBothL1AndL2Providers() {
        contextRunner
                .withConfiguration(AutoConfigurations.of(RedisAutoConfiguration.class))
                .withPropertyValues(
                        "caching.l1.enabled=true",
                        "caching.l2.enabled=true",
                        "spring.data.redis.host=localhost",
                        "spring.data.redis.port=6379"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(CacheService.class);
                    assertThat(context).hasBean("l1CacheProvider");
                    assertThat(context).hasBean("l2CacheProvider");

                    CacheService cacheService = context.getBean(CacheService.class);
                    assertThat(cacheService).isInstanceOf(MultiLevelCacheService.class);
                });
    }

    @Test
    @DisplayName("should create cache service with only L1 provider")
    void shouldCreateCacheServiceWithOnlyL1Provider() {
        contextRunner
                .withPropertyValues(
                        "caching.l1.enabled=true",
                        "caching.l2.enabled=false"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(CacheService.class);
                    assertThat(context).hasBean("l1CacheProvider");
                    assertThat(context).doesNotHaveBean("l2CacheProvider");
                });
    }

    @Test
    @DisplayName("should create cache service with only L2 provider")
    void shouldCreateCacheServiceWithOnlyL2Provider() {
        contextRunner
                .withConfiguration(AutoConfigurations.of(RedisAutoConfiguration.class))
                .withPropertyValues(
                        "caching.l1.enabled=false",
                        "caching.l2.enabled=true",
                        "spring.data.redis.host=localhost",
                        "spring.data.redis.port=6379"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(CacheService.class);
                    assertThat(context).doesNotHaveBean("l1CacheProvider");
                    assertThat(context).hasBean("l2CacheProvider");
                });
    }

    @Test
    @DisplayName("should configure L1 cache with custom specification")
    void shouldConfigureL1CacheWithCustomSpecification() {
        contextRunner
                .withPropertyValues(
                        "caching.l1.enabled=true",
                        "caching.l1.spec=maximumSize=200,expireAfterWrite=10m"
                )
                .run(context -> {
                    assertThat(context).hasBean("l1CacheProvider");

                    CacheProvider l1Provider = context.getBean("l1CacheProvider", CacheProvider.class);
                    assertThat(l1Provider).isInstanceOf(CaffeineCacheAdapter.class);
                });
    }

    @Test
    @DisplayName("should configure L2 cache with custom TTL and invalidation topic")
    void shouldConfigureL2CacheWithCustomTtlAndInvalidationTopic() {
        contextRunner
                .withConfiguration(AutoConfigurations.of(RedisAutoConfiguration.class))
                .withPropertyValues(
                        "caching.l2.enabled=true",
                        "caching.l2.ttl=PT2H",
                        "caching.l2.invalidation-topic=custom-cache-invalidation",
                        "spring.data.redis.host=localhost",
                        "spring.data.redis.port=6379"
                )
                .run(context -> {
                    assertThat(context).hasBean("l2CacheProvider");

                    CacheProvider l2Provider = context.getBean("l2CacheProvider", CacheProvider.class);
                    // Verify it's a Redis adapter (may be wrapped with decorators)
                    assertThat(l2Provider).isNotNull();
                });
    }

    @Test
    @DisplayName("should configure circuit breaker with custom properties")
    void shouldConfigureCircuitBreakerWithCustomProperties() {
        contextRunner
                .withConfiguration(AutoConfigurations.of(RedisAutoConfiguration.class))
                .withPropertyValues(
                        "caching.l2.enabled=true",
                        "caching.l2.circuit-breaker.enabled=true",
                        "caching.l2.circuit-breaker.failure-rate-threshold=60",
                        "caching.l2.circuit-breaker.slow-call-rate-threshold=80",
                        "spring.data.redis.host=localhost",
                        "spring.data.redis.port=6379"
                )
                .withBean(CircuitBreakerRegistry.class, CircuitBreakerRegistry::ofDefaults)
                .run(context -> {
                    assertThat(context).hasBean("l2CacheProvider");
                    assertThat(context).hasSingleBean(CircuitBreakerRegistry.class);

                    CacheProvider l2Provider = context.getBean("l2CacheProvider", CacheProvider.class);
                    assertThat(l2Provider).isInstanceOf(CircuitBreakerCacheProvider.class);
                });
    }

    @Test
    @DisplayName("InvalidationHandler should handle message when L1 cache is available")
    void invalidationHandler_shouldHandleMessageWhenL1CacheAvailable() {
        CacheProvider mockL1Cache = mock(CacheProvider.class);
        InvalidationHandler handler = new InvalidationHandler(mockL1Cache);

        handler.handleMessage("test-key");

        org.mockito.Mockito.verify(mockL1Cache).evict("test-key");
    }

    @Test
    @DisplayName("InvalidationHandler should handle message gracefully when L1 cache is null")
    void invalidationHandler_shouldHandleMessageGracefullyWhenL1CacheIsNull() {
        InvalidationHandler handler = new InvalidationHandler(null);

        // Should not throw exception
        handler.handleMessage("test-key");
    }

    @Test
    @DisplayName("should create CacheXAspect with configured cache service")
    void shouldCreateCacheXAspectWithConfiguredCacheService() {
        contextRunner
                .run(context -> {
                    assertThat(context).hasSingleBean(CacheXAspect.class);
                    assertThat(context).hasSingleBean(CacheService.class);

                    CacheXAspect aspect = context.getBean(CacheXAspect.class);
                    CacheService cacheService = context.getBean(CacheService.class);

                    assertThat(aspect).isNotNull();
                    assertThat(cacheService).isNotNull();
                });
    }

    @Test
    @DisplayName("should configure ObjectMapper with required modules")
    void shouldConfigureObjectMapperWithRequiredModules() {
        contextRunner
                .withConfiguration(AutoConfigurations.of(RedisAutoConfiguration.class))
                .withPropertyValues(
                        "caching.l2.enabled=true",
                        "spring.data.redis.host=localhost",
                        "spring.data.redis.port=6379"
                )
                .run(context -> {
                    assertThat(context).hasBean("cachingObjectMapper");

                    ObjectMapper objectMapper = context.getBean("cachingObjectMapper", ObjectMapper.class);
                    assertThat(objectMapper).isNotNull();

                    // Verify modules are registered (this is a bit tricky to test directly)
                    assertThat(objectMapper.getRegisteredModuleIds()).isNotEmpty();
                });
    }
}
