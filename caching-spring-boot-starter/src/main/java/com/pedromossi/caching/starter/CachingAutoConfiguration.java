// Código aderente ao Google Java Style Guide
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
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import jakarta.annotation.PostConstruct;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
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
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.lang.Nullable;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Spring Boot auto-configuration class for the caching library. Creates and configures cache
 * beans based on application properties.
 *
 * @since 1.0.0
 */
@Configuration
@EnableConfigurationProperties(CachingProperties.class)
@ConditionalOnProperty(name = "caching.enabled", havingValue = "true", matchIfMissing = true)
@AutoConfigureAfter(RedisAutoConfiguration.class)
@Import(CachingMicrometerAutoConfiguration.class)
public class CachingAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(CachingAutoConfiguration.class);

    @Configuration
    @ConditionalOnProperty(name = "caching.l1.enabled", havingValue = "true", matchIfMissing = true)
    static class L1CacheConfiguration {

        @Bean("l1CacheProvider")
        @ConditionalOnBean(MeterRegistry.class)
        public CacheProvider l1CacheProviderWithMetrics(
                CachingProperties properties, MeterRegistry meterRegistry) {
            log.info("MeterRegistry found. Enabling granular metrics for L1 cache.");
            CacheProvider caffeineAdapter = new CaffeineCacheAdapter(properties.getL1().getSpec());
            return new MetricsCollectingCacheProvider(caffeineAdapter, meterRegistry, "l1");
        }

        @Bean("l1CacheProvider")
        @ConditionalOnMissingBean(MeterRegistry.class)
        public CacheProvider l1CacheProviderWithoutMetrics(CachingProperties properties) {
            log.warn(
                    "MeterRegistry not found. Granular metrics for L1 cache are disabled. "
                            + "Add 'spring-boot-starter-actuator' to enable them.");
            return new CaffeineCacheAdapter(properties.getL1().getSpec());
        }
    }

    @Bean
    public CacheXAspect cacheXAspect(CacheService cacheService) {
        return new CacheXAspect(cacheService);
    }

    @Configuration
    @ConditionalOnClass(RedisTemplate.class)
    @ConditionalOnProperty(name = "caching.l2.enabled", havingValue = "true", matchIfMissing = true)
    @ConditionalOnBean(RedisConnectionFactory.class)
    protected static class RedisCacheConfiguration {

        @Bean("cachingObjectMapper")
        public ObjectMapper cachingObjectMapper() {
            return new ObjectMapper()
                    .registerModule(new Jdk8Module())
                    .registerModule(new JavaTimeModule());
        }

        @Bean("cacheRedisTemplate")
        @ConditionalOnMissingBean(name = "cacheRedisTemplate")
        public RedisTemplate<String, Object> cacheRedisTemplate(
                RedisConnectionFactory connectionFactory,
                @Qualifier("cachingObjectMapper") ObjectMapper objectMapper) {

            RedisTemplate<String, Object> template = new RedisTemplate<>();
            template.setConnectionFactory(connectionFactory);
            GenericJackson2JsonRedisSerializer serializer =
                    new GenericJackson2JsonRedisSerializer(objectMapper);
            template.setKeySerializer(new StringRedisSerializer());
            template.setValueSerializer(serializer);
            template.afterPropertiesSet();
            return template;
        }

        @Bean("l2CacheProvider")
        @ConditionalOnBean(MeterRegistry.class)
        public CacheProvider l2CacheProviderWithMetrics(
                @Qualifier("cacheRedisTemplate") RedisTemplate<String, Object> redisTemplate,
                CachingProperties properties,
                @Qualifier("cachingObjectMapper") ObjectMapper objectMapper,
                MeterRegistry meterRegistry) {
            log.info("MeterRegistry found. Enabling granular metrics for L2 cache.");
            CacheProvider redisAdapter =
                    new RedisCacheAdapter(
                            redisTemplate,
                            properties.getL2().getInvalidationTopic(),
                            properties.getL2().getTtl(),
                            objectMapper);
            return new MetricsCollectingCacheProvider(redisAdapter, meterRegistry, "l2");
        }

        @Bean("l2CacheProvider")
        @ConditionalOnMissingBean(MeterRegistry.class)
        public CacheProvider l2CacheProviderWithoutMetrics(
                @Qualifier("cacheRedisTemplate") RedisTemplate<String, Object> redisTemplate,
                CachingProperties properties,
                @Qualifier("cachingObjectMapper") ObjectMapper objectMapper) {
            log.warn(
                    "MeterRegistry not found. Granular metrics for L2 cache are disabled. "
                            + "Add 'spring-boot-starter-actuator' to enable them.");
            return new RedisCacheAdapter(
                    redisTemplate,
                    properties.getL2().getInvalidationTopic(),
                    properties.getL2().getTtl(),
                    objectMapper);
        }

        @Bean
        public MessageListenerAdapter redisInvalidationListener(InvalidationHandler invalidationHandler) {
            return new MessageListenerAdapter(invalidationHandler, "handleMessage");
        }

        @Bean
        public InvalidationHandler invalidationHandler(
                @Qualifier("l1CacheProvider") @Nullable CacheProvider l1CacheProvider) {
            return new InvalidationHandler(l1CacheProvider);
        }

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

    @Bean
    public CacheService cacheService(
            @Qualifier("l1CacheProvider") Optional<CacheProvider> l1CacheProvider,
            @Qualifier("l2CacheProvider") Optional<CacheProvider> l2CacheProvider,
            @Qualifier("cachingTaskExecutor") ExecutorService executorService) {
        return new MultiLevelCacheService(l1CacheProvider, l2CacheProvider, executorService);
    }

    public static class InvalidationHandler {
        private static final Logger log = LoggerFactory.getLogger(InvalidationHandler.class);
        private final CacheProvider l1Cache;

        public InvalidationHandler(@Nullable CacheProvider l1Cache) {
            this.l1Cache = l1Cache;
        }

        public void handleMessage(String message) {
            if (l1Cache != null) {
                log.info("Received invalidation message from Redis. Invalidating L1 key: {}", message);
                l1Cache.evict(message);
            }
        }
    }

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

    public static class CacheMetricsBindingService {
        private static final Logger log = LoggerFactory.getLogger(CacheMetricsBindingService.class);
        private final CacheProvider l1CacheProvider;
        private final MeterRegistry meterRegistry;
        private final CachingProperties properties;

        public CacheMetricsBindingService(
                CacheProvider l1CacheProvider, MeterRegistry meterRegistry, CachingProperties properties) {
            this.l1CacheProvider = l1CacheProvider;
            this.meterRegistry = meterRegistry;
            this.properties = properties;
        }

        @PostConstruct
        public void bindL1CacheToMicrometer() {
            String spec = properties.getL1().getSpec();
            if (spec != null && spec.contains("recordStats")) {
                if (l1CacheProvider instanceof CaffeineCacheAdapter adapter) {
                    CaffeineCacheMetrics.monitor(
                            meterRegistry, adapter.getNativeCache(), "l1Cache", Collections.emptyList());
                    log.info("Caffeine native stats for L1 cache are bound to Micrometer.");
                } else if (l1CacheProvider instanceof MetricsCollectingCacheProvider decorator) {
                    log.info("Attempting to bind Caffeine native stats through decorator...");
                }
            }
        }
    }
}