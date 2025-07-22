package com.pedromossi.caching.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pedromossi.caching.CacheProvider;
import com.pedromossi.caching.CacheService;
import com.pedromossi.caching.aspect.CacheXAspect;
import com.pedromossi.caching.caffeine.CaffeineCacheAdapter;
import com.pedromossi.caching.impl.MultiLevelCacheService;
import com.pedromossi.caching.micrometer.MetricsCollectingCacheProvider;
import com.pedromossi.caching.redis.serializer.JacksonCacheSerializer;
import com.pedromossi.caching.resilience.CircuitBreakerCacheProvider;
import com.pedromossi.caching.serializer.CacheSerializer;
import com.pedromossi.caching.starter.CachingAutoConfiguration.InvalidationHandler;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.ExecutorService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.connection.RedisConnection;
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

    // A runner for tests that DO NOT involve L2/Redis configuration.
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CachingAutoConfiguration.class));

    // A pre-configured runner for tests that DO involve L2/Redis.
    // It includes mocks for Redis dependencies to prevent actual network connections.
    private final ApplicationContextRunner l2ContextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CachingAutoConfiguration.class))
            .withBean(RedisConnectionFactory.class, this::createMockRedisConnectionFactory)
            // *** THE KEY FIX IS HERE ***
            // Provide a mock RedisMessageListenerContainer to prevent it from trying to connect.
            .withBean(RedisMessageListenerContainer.class, () -> mock(RedisMessageListenerContainer.class));


    private RedisConnectionFactory createMockRedisConnectionFactory() {
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        RedisConnection connection = mock(RedisConnection.class);
        when(factory.getConnection()).thenReturn(connection);
        return factory;
    }

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
    @DisplayName("should not create Redis configuration when L2 cache disabled")
    void shouldNotCreateRedisConfigurationWhenL2CacheDisabled() {
        l2ContextRunner
                .withPropertyValues("caching.l2.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean("cacheRedisTemplate");
                    assertThat(context).doesNotHaveBean("cachingObjectMapper");
                    assertThat(context).doesNotHaveBean("l2CacheProvider");
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

        // Should not throw any exception
        handler.handleMessage("test-key");
    }
}