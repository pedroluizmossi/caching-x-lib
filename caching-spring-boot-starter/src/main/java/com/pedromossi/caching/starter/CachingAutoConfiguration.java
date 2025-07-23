package com.pedromossi.caching.starter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.adapter.MessageListenerAdapter;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.lang.Nullable;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.ExecutorService;

/**
 * Spring Boot auto-configuration for the multi-level caching library.
 *
 * <p>This auto-configuration class provides comprehensive setup for a multi-level caching
 * infrastructure with L1 (local) and L2 (distributed) cache providers. It automatically
 * configures cache components based on available dependencies and application properties,
 * following Spring Boot's auto-configuration conventions.</p>
 *
 * <p><strong>Supported Cache Levels:</strong></p>
 * <ul>
 *   <li><strong>L1 Cache:</strong> Local in-memory cache using Caffeine</li>
 *   <li><strong>L2 Cache:</strong> Distributed cache using Redis</li>
 * </ul>
 *
 * <p><strong>Optional Integrations:</strong></p>
 * <ul>
 *   <li><strong>Metrics:</strong> Micrometer integration for cache performance monitoring</li>
 *   <li><strong>Resilience:</strong> Circuit breaker support via Resilience4j</li>
 *   <li><strong>AOP:</strong> Annotation-driven caching through {@link CacheXAspect}</li>
 * </ul>
 *
 * <p><strong>Configuration Properties:</strong> All behavior is controlled through
 * {@link CachingProperties}, allowing fine-tuning of cache specifications, TTL,
 * circuit breaker settings, and async execution parameters.</p>
 *
 * <p><strong>Conditional Configuration:</strong> Components are only created when
 * relevant dependencies are available and corresponding properties are enabled,
 * ensuring minimal resource usage and clean degradation.</p>
 *
 * @since 1.0.0
 * @see CachingProperties
 * @see CacheService
 * @see CacheProvider
 */
@Configuration
@EnableConfigurationProperties(CachingProperties.class)
@ConditionalOnProperty(name = "caching.enabled", havingValue = "true", matchIfMissing = true)
@AutoConfigureAfter(RedisAutoConfiguration.class)
@Import(CachingMicrometerAutoConfiguration.class)
public class CachingAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(CachingAutoConfiguration.class);

    /**
     * Configuration for L1 (local) cache provider using Caffeine.
     *
     * <p>This nested configuration class handles the setup of local in-memory caching
     * using Caffeine as the backend. It provides conditional configuration based on
     * the availability of Micrometer for metrics collection.</p>
     *
     * <p><strong>Metrics Integration:</strong> When Micrometer is available, the L1
     * cache provider is automatically wrapped with metrics collection capabilities
     * for detailed performance monitoring.</p>
     */
    @Configuration
    @ConditionalOnProperty(name = "caching.l1.enabled", havingValue = "true", matchIfMissing = true)
    static class L1CacheConfiguration {

        /**
         * Creates an L1 cache provider with Micrometer metrics integration.
         *
         * <p>This bean is created when {@link MeterRegistry} is available on the classpath
         * and provides detailed metrics collection for cache operations including hit/miss
         * ratios, operation latencies, and error rates.</p>
         *
         * @param properties caching configuration properties for L1 cache specification
         * @param meterRegistry Micrometer registry for publishing cache metrics
         * @return L1 cache provider with metrics collection enabled
         */
        @Bean("l1CacheProvider")
        @ConditionalOnBean(MeterRegistry.class)
        public CacheProvider l1CacheProviderWithMetrics(
                CachingProperties properties, MeterRegistry meterRegistry) {
            log.info("MeterRegistry found. Enabling granular metrics for L1 cache.");
            CacheProvider caffeineAdapter = new CaffeineCacheAdapter(properties.getL1().getSpec());
            return new MetricsCollectingCacheProvider(caffeineAdapter, meterRegistry, "l1");
        }

        /**
         * Creates a basic L1 cache provider without metrics integration.
         *
         * <p>This fallback bean is created when {@link MeterRegistry} is not available,
         * providing basic L1 caching functionality without metrics overhead.</p>
         *
         * @param properties caching configuration properties for L1 cache specification
         * @return basic L1 cache provider without metrics
         */
        @Bean("l1CacheProvider")
        @ConditionalOnMissingBean(MeterRegistry.class)
        public CacheProvider l1CacheProviderWithoutMetrics(CachingProperties properties) {
            log.warn(
                    "MeterRegistry not found. Granular metrics for L1 cache are disabled. "
                            + "Add 'spring-boot-starter-actuator' to enable them.");
            return new CaffeineCacheAdapter(properties.getL1().getSpec());
        }
    }

    /**
     * Creates the AOP aspect for annotation-driven caching.
     *
     * <p>This bean enables declarative caching through annotations, providing
     * a Spring AOP aspect that intercepts method calls and applies caching logic
     * transparently to application code.</p>
     *
     * @param cacheService the cache service to use for caching operations
     * @return configured cache aspect for annotation processing
     * @see CacheXAspect
     */
    @Bean
    public CacheXAspect cacheXAspect(CacheService cacheService) {
        return new CacheXAspect(cacheService);
    }

    /**
     * Configuration for L2 (distributed) cache provider using Redis.
     *
     * <p>This nested configuration class handles the setup of distributed caching
     * using Redis as the backend. It includes Redis template configuration,
     * serialization setup, metrics integration, circuit breaker support,
     * and distributed invalidation handling.</p>
     *
     * <p><strong>Dependencies Required:</strong></p>
     * <ul>
     *   <li>RedisTemplate on the classpath</li>
     *   <li>RedisConnectionFactory bean available</li>
     *   <li>L2 cache enabled in properties</li>
     * </ul>
     */
    @Configuration
    @ConditionalOnClass(RedisTemplate.class)
    @ConditionalOnProperty(name = "caching.l2.enabled", havingValue = "true", matchIfMissing = true)
    @ConditionalOnBean(RedisConnectionFactory.class)
    protected static class RedisCacheConfiguration {

        /**
         * Creates a Jackson ObjectMapper specifically configured for caching operations.
         *
         * <p>This ObjectMapper includes essential modules for handling Java 8+ features
         * and time types commonly used in cached objects.</p>
         *
         * @return pre-configured ObjectMapper with Jdk8Module and JavaTimeModule
         */
        @Bean("cachingObjectMapper")
        public ObjectMapper cachingObjectMapper() {
            return new ObjectMapper()
                    .registerModule(new Jdk8Module())
                    .registerModule(new JavaTimeModule());
        }

        /**
         * Creates a default cache serializer using Jackson for JSON serialization.
         *
         * <p>This bean provides the default serialization strategy for Redis cache
         * operations. It only creates this bean if no custom {@link CacheSerializer}
         * is already defined, allowing for easy customization of serialization behavior.</p>
         *
         * @param objectMapper the configured ObjectMapper for JSON operations
         * @return Jackson-based cache serializer
         */
        @Bean
        @ConditionalOnMissingBean
        public CacheSerializer cacheSerializer(
                @Qualifier("cachingObjectMapper") ObjectMapper objectMapper) {
            log.info("No custom CacheSerializer bean found. Creating default JacksonCacheSerializer.");
            return new JacksonCacheSerializer(objectMapper);
        }

        /**
         * Creates a Redis template specifically configured for cache operations.
         *
         * <p>This template is configured with appropriate serializers for cache data:
         * String keys and byte array values, enabling efficient storage and retrieval
         * of serialized cache objects.</p>
         *
         * @param connectionFactory Redis connection factory for creating connections
         * @return configured Redis template for cache operations
         */
        @Bean("cacheRedisTemplate")
        @ConditionalOnMissingBean(name = "cacheRedisTemplate")
        public RedisTemplate<String, byte[]> cacheRedisTemplate(
                RedisConnectionFactory connectionFactory) {

            RedisTemplate<String, byte[]> template = new RedisTemplate<>();
            template.setConnectionFactory(connectionFactory);
            template.setKeySerializer(new StringRedisSerializer());
            template.setValueSerializer(RedisSerializer.byteArray());
            template.setHashKeySerializer(new StringRedisSerializer());
            template.setHashValueSerializer(RedisSerializer.byteArray());
            template.afterPropertiesSet();
            return template;
        }

        /**
         * Creates a fully configured L2 cache provider with optional metrics and circuit breaker.
         *
         * <p>This method creates the L2 cache provider with a layered approach, adding
         * optional decorators based on available dependencies and configuration:</p>
         * <ol>
         *   <li>Base Redis cache adapter</li>
         *   <li>Metrics collection (if Micrometer available)</li>
         *   <li>Circuit breaker protection (if Resilience4j available and enabled)</li>
         * </ol>
         *
         * @param redisTemplate configured Redis template for cache operations
         * @param properties caching configuration properties
         * @param serializer cache serialization strategy
         * @param meterRegistry optional Micrometer registry for metrics
         * @param circuitBreakerRegistry optional Resilience4j registry for circuit breakers
         * @return fully configured L2 cache provider with optional decorators
         */
        @Bean("l2CacheProvider")
        public CacheProvider l2CacheProvider(
                @Qualifier("cacheRedisTemplate") RedisTemplate<String, byte[]> redisTemplate,
                CachingProperties properties,
                CacheSerializer serializer,
                Optional<MeterRegistry> meterRegistry,
                Optional<CircuitBreakerRegistry> circuitBreakerRegistry) {

            CacheProvider provider = new RedisCacheAdapter(
                    redisTemplate,
                    properties.getL2().getInvalidationTopic(),
                    properties.getL2().getTtl(),
                    serializer
            );

            if (meterRegistry.isPresent()) {
                log.info("MeterRegistry found. Enabling granular metrics for L2 cache.");
                provider = new MetricsCollectingCacheProvider(provider, meterRegistry.get(), "l2");
            } else {
                log.warn(
                        "MeterRegistry not found. Granular metrics for L2 cache are disabled. "
                                + "Add 'spring-boot-starter-actuator' to enable them.");
            }

            boolean cbEnabled = properties.getL2().getCircuitBreaker().isEnabled();
            if (cbEnabled && circuitBreakerRegistry.isPresent()) {
                log.info("Resilience4j found and circuit breaker is enabled for L2 cache.");

                CachingProperties.CircuitBreakerProperties cbProps = properties.getL2().getCircuitBreaker();
                CircuitBreakerConfig config =
                        CircuitBreakerConfig.custom()
                                .failureRateThreshold(cbProps.getFailureRateThreshold())
                                .slowCallRateThreshold(cbProps.getSlowCallRateThreshold())
                                .slowCallDurationThreshold(cbProps.getSlowCallDurationThreshold())
                                .permittedNumberOfCallsInHalfOpenState(cbProps.getPermittedNumberOfCallsInHalfOpenState())
                                .waitDurationInOpenState(cbProps.getWaitDurationInOpenState())
                                .build();

                CircuitBreaker circuitBreaker = circuitBreakerRegistry.get().circuitBreaker("l2Cache", config);
                provider = new CircuitBreakerCacheProvider(provider, circuitBreaker);

            } else if (cbEnabled) {
                log.warn(
                        "L2 circuit breaker is enabled in properties but Resilience4j is not on the classpath. "
                                + "Circuit breaker will be disabled.");
            }

            return provider;
        }

        /**
         * Creates a Redis message listener adapter for handling cache invalidation events.
         *
         * <p>This adapter connects the Redis pub/sub invalidation messages to the
         * invalidation handler, enabling distributed cache consistency across
         * multiple application instances.</p>
         *
         * @param invalidationHandler handler for processing invalidation events
         * @return configured message listener adapter
         */
        @Bean
        public MessageListenerAdapter redisInvalidationListener(InvalidationHandler invalidationHandler) {
            return new MessageListenerAdapter(invalidationHandler, "handleMessage");
        }

        /**
         * Creates an invalidation handler for processing distributed cache invalidation events.
         *
         * <p>This handler receives invalidation messages from Redis pub/sub and applies
         * them to the local L1 cache, ensuring cache consistency across distributed
         * application instances.</p>
         *
         * @param l1CacheProvider the L1 cache provider to invalidate (may be null if L1 disabled)
         * @return invalidation handler for processing Redis invalidation messages
         */
        @Bean
        public InvalidationHandler invalidationHandler(
                @Qualifier("l1CacheProvider") @Nullable CacheProvider l1CacheProvider) {
            return new InvalidationHandler(l1CacheProvider);
        }

        /**
         * Creates and configures a Redis message listener container for invalidation events.
         *
         * <p>This container manages the Redis pub/sub subscription for cache invalidation
         * messages, automatically handling connection management and message routing
         * to the configured invalidation listener.</p>
         *
         * @param connectionFactory Redis connection factory for pub/sub connections
         * @param redisInvalidationListener message listener for invalidation events
         * @param properties caching properties containing invalidation topic configuration
         * @return configured Redis message listener container
         */
        @Bean
        public RedisMessageListenerContainer redisMessageListenerContainer(
                RedisConnectionFactory connectionFactory,
                MessageListenerAdapter redisInvalidationListener,
                CachingProperties properties) {
            RedisMessageListenerContainer container = new RedisMessageListenerContainer();
            container.setConnectionFactory(connectionFactory);
            container.addMessageListener(
                    redisInvalidationListener, new ChannelTopic(properties.getL2().getInvalidationTopic()));
            return container;
        }
    }

    /**
     * Creates a dedicated executor service for asynchronous cache operations.
     *
     * <p>This executor handles background tasks such as cache warming, invalidation
     * propagation, and other non-blocking cache operations. It's configured with
     * reasonable defaults that can be customized through application properties.</p>
     *
     * @param properties caching properties containing async configuration
     * @return configured executor service for cache async operations
     */
    @Bean("cachingTaskExecutor")
    @ConditionalOnMissingBean(name = "cachingTaskExecutor")
    public ExecutorService cachingTaskExecutor(CachingProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        CachingProperties.AsyncProperties async = properties.getAsync();
        executor.setCorePoolSize(async.getCorePoolSize());
        executor.setMaxPoolSize(async.getMaxPoolSize());
        executor.setQueueCapacity(async.getQueueCapacity());
        executor.setThreadNamePrefix("cache-async-");
        executor.initialize();
        return executor.getThreadPoolExecutor();
    }

    /**
     * Creates the main cache service with configured L1 and L2 providers.
     *
     * <p>This is the primary bean that applications interact with for caching operations.
     * It coordinates between L1 and L2 cache providers and handles asynchronous operations
     * through the configured executor service.</p>
     *
     * @param l1CacheProvider optional L1 cache provider (local cache)
     * @param l2CacheProvider optional L2 cache provider (distributed cache)
     * @param executorService executor for asynchronous cache operations
     * @return configured multi-level cache service
     */
    @Bean
    public CacheService cacheService(
            @Qualifier("l1CacheProvider") Optional<CacheProvider> l1CacheProvider,
            @Qualifier("l2CacheProvider") Optional<CacheProvider> l2CacheProvider,
            @Qualifier("cachingTaskExecutor") ExecutorService executorService) {
        return new MultiLevelCacheService(l1CacheProvider, l2CacheProvider, executorService);
    }

    /**
     * Handler for Redis invalidation messages that propagates cache invalidations to L1 cache.
     *
     * <p>This class processes invalidation messages received from Redis pub/sub and applies
     * them to the local L1 cache. This ensures that when one application instance
     * invalidates a cache entry, all other instances are notified and can invalidate
     * their local copies, maintaining cache consistency across the distributed system.</p>
     *
     * <p><strong>Message Processing:</strong> Each invalidation message contains a cache
     * key that should be removed from the local L1 cache. The handler safely handles
     * cases where L1 cache is not available (disabled).</p>
     */
    public static class InvalidationHandler {
        private static final Logger log = LoggerFactory.getLogger(InvalidationHandler.class);
        private final CacheProvider l1Cache;

        /**
         * Creates a new invalidation handler with the specified L1 cache provider.
         *
         * @param l1Cache the L1 cache provider to invalidate, may be null if L1 cache is disabled
         */
        public InvalidationHandler(@Nullable CacheProvider l1Cache) {
            this.l1Cache = l1Cache;
        }

        /**
         * Handles an invalidation message from Redis by removing the key from L1 cache.
         *
         * <p>This method is called by the Redis message listener when an invalidation
         * message is received. It safely handles the case where L1 cache is not
         * available by checking for null before attempting invalidation.</p>
         *
         * @param message the cache key to invalidate from L1 cache
         */
        public void handleMessage(String message) {
            if (l1Cache != null) {
                log.info("Received invalidation message from Redis. Invalidating L1 key: {}", message);
                l1Cache.evict(message);
            }
        }
    }

    /**
     * Creates a service for binding Caffeine native cache metrics to Micrometer.
     *
     * <p>This bean is only created when specific conditions are met: Caffeine cache
     * metrics are available, both L1 cache provider and MeterRegistry beans exist,
     * and the cache is configured with native stats recording enabled.</p>
     *
     * @param l1CacheProvider the L1 cache provider (should be Caffeine-based)
     * @param meterRegistry Micrometer registry for publishing metrics
     * @param properties caching properties for checking stats configuration
     * @return service for binding Caffeine native metrics
     */
    @Bean
    @ConditionalOnClass(CaffeineCacheMetrics.class)
    @ConditionalOnBean(name = "l1CacheProvider", value = MeterRegistry.class)
    @ConditionalOnProperty(name = "caching.l1.spec", havingValue = "recordStats", matchIfMissing = false)
    public CacheMetricsBindingService cacheMetricsBindingService(
            @Qualifier("l1CacheProvider") CacheProvider l1CacheProvider,
            MeterRegistry meterRegistry,
            CachingProperties properties) {
        return new CacheMetricsBindingService(l1CacheProvider, meterRegistry, properties);
    }

    /**
     * Service for binding Caffeine native cache statistics to Micrometer metrics.
     *
     * <p>This service provides integration between Caffeine's built-in statistics
     * collection and Micrometer metrics, enabling detailed monitoring of cache
     * performance including native Caffeine metrics like eviction counts and
     * load statistics.</p>
     *
     * <p><strong>Requirements:</strong> The Caffeine cache must be configured with
     * {@code recordStats()} enabled in its specification for native metrics to
     * be available for binding.</p>
     */
    public static class CacheMetricsBindingService {
        private static final Logger log = LoggerFactory.getLogger(CacheMetricsBindingService.class);
        private final CacheProvider l1CacheProvider;
        private final MeterRegistry meterRegistry;
        private final CachingProperties properties;

        /**
         * Creates a new cache metrics binding service.
         *
         * @param l1CacheProvider the L1 cache provider containing the Caffeine cache
         * @param meterRegistry Micrometer registry for publishing metrics
         * @param properties caching properties for configuration checking
         */
        public CacheMetricsBindingService(
                CacheProvider l1CacheProvider, MeterRegistry meterRegistry, CachingProperties properties) {
            this.l1CacheProvider = l1CacheProvider;
            this.meterRegistry = meterRegistry;
            this.properties = properties;
        }

        /**
         * Binds Caffeine native cache statistics to Micrometer after bean initialization.
         *
         * <p>This method is called automatically after the bean is fully constructed
         * and configured. It checks if the cache specification includes native stats
         * recording and attempts to bind the Caffeine cache to Micrometer metrics.</p>
         *
         * <p><strong>Binding Process:</strong> The method attempts to extract the
         * native Caffeine cache from the cache provider (potentially through
         * decorator layers) and register it with Micrometer for native statistics
         * collection.</p>
         */
        @PostConstruct
        public void bindL1CacheToMicrometer() {
            String spec = properties.getL1().getSpec();
            if (spec != null && spec.contains("recordStats")) {
                if (l1CacheProvider instanceof CaffeineCacheAdapter adapter) {
                    CaffeineCacheMetrics.monitor(
                            meterRegistry, adapter.getNativeCache(), "l1Cache", Collections.emptyList());
                    log.info("Caffeine native stats for L1 cache are bound to Micrometer.");
                } else if (l1CacheProvider instanceof MetricsCollectingCacheProvider) {
                    log.info("Attempting to bind Caffeine native stats through decorator...");
                }
            }
        }
    }
}

