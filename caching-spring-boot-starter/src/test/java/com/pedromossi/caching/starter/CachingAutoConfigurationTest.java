package com.pedromossi.caching.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pedromossi.caching.CacheProvider;
import com.pedromossi.caching.CacheService;
import com.pedromossi.caching.caffeine.CaffeineCacheAdapter;
import com.pedromossi.caching.micrometer.MetricsCollectingCacheProvider;
import com.pedromossi.caching.starter.CachingAutoConfiguration.InvalidationHandler;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

@DisplayName("CachingAutoConfiguration Unit Tests")
class CachingAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(CachingAutoConfiguration.class));

    /** Test-specific configuration to provide mock beans for Redis infrastructure. */
    @TestConfiguration
    static class MockRedisInfrastructure {
        @Bean
        public RedisConnectionFactory redisConnectionFactory() {
            RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
            when(factory.getConnection()).thenReturn(mock(RedisConnection.class));
            return factory;
        }

        @Bean
        @Primary // Ensures this mock is used instead of the auto-configured one
        public RedisMessageListenerContainer redisMessageListenerContainer() {
            return mock(RedisMessageListenerContainer.class);
        }
    }

    @Nested
    @DisplayName("Global Configuration")
    class GlobalConfigurationTests {
        @Test
        @DisplayName("should create default beans when enabled")
        void shouldCreateDefaultBeans() {
            contextRunner.run(
                    context -> {
                        assertThat(context).hasSingleBean(CacheService.class);
                        assertThat(context).hasBean("cachingTaskExecutor");
                    });
        }

        @Test
        @DisplayName("should disable all beans when caching.enabled=false")
        void shouldDisableAllBeans() {
            contextRunner
                    .withPropertyValues("caching.enabled=false")
                    .run(
                            context -> {
                                assertThat(context).doesNotHaveBean(CacheService.class);
                                assertThat(context).doesNotHaveBean("l1CacheProvider");
                                assertThat(context).doesNotHaveBean("l2CacheProvider");
                            });
        }
    }

    @Nested
    @DisplayName("L1 Cache (Caffeine)")
    class L1CacheConfigurationTests {
        @Test
        @DisplayName("should be configured conditionally")
        void shouldConfigureL1ProviderConditionally() {
            // Case 1: Default (no MeterRegistry)
            contextRunner.run(
                    context -> {
                        assertThat(context)
                                .hasBean("l1CacheProvider")
                                .getBean("l1CacheProvider", CacheProvider.class)
                                .isInstanceOf(CaffeineCacheAdapter.class)
                                .isNotInstanceOf(MetricsCollectingCacheProvider.class);
                    });

            // Case 2: With MeterRegistry
            contextRunner
                    .withBean(MeterRegistry.class, () -> mock(MeterRegistry.class))
                    .run(
                            context ->
                                    assertThat(context)
                                            .hasBean("l1CacheProvider")
                                            .getBean("l1CacheProvider", CacheProvider.class)
                                            .isInstanceOf(MetricsCollectingCacheProvider.class));
        }

        @Test
        @DisplayName("should not be created when disabled")
        void shouldNotCreateL1ProviderWhenDisabled() {
            contextRunner
                    .withPropertyValues("caching.l1.enabled=false")
                    .run(context -> assertThat(context).doesNotHaveBean("l1CacheProvider"));
        }
    }

    @Nested
    @DisplayName("L2 Cache (Redis)")
    class L2CacheConfigurationTests {
        // Pre-configure a runner for all L2 tests to reduce boilerplate

        @Test
        @DisplayName("should not be created when disabled")
        void shouldNotCreateL2BeansWhenDisabled() {
            contextRunner // Use the base runner without L2 mocks
                    .withPropertyValues("caching.l2.enabled=false")
                    .run(context -> assertThat(context).doesNotHaveBean("l2CacheProvider"));
        }
    }

    @Nested
    @DisplayName("Component Unit Tests")
    class ComponentTests {
        @Test
        @DisplayName("InvalidationHandler should evict from L1 cache")
        void invalidationHandler_shouldEvictFromL1() {
            CacheProvider mockL1Cache = mock(CacheProvider.class);
            InvalidationHandler handler = new InvalidationHandler(mockL1Cache);

            handler.handleMessage("test-key");

            verify(mockL1Cache).evict("test-key");
        }

        @Test
        @DisplayName("InvalidationHandler should not fail if L1 cache is absent")
        void invalidationHandler_shouldNotFailIfL1Absent() {
            InvalidationHandler handler = new InvalidationHandler(null);
            // Should not throw an exception
            handler.handleMessage("test-key");
        }
    }
}