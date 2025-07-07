package com.pedromossi.caching.starter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pedromossi.caching.CacheProvider;
import com.pedromossi.caching.CacheService;
import com.pedromossi.caching.caffeine.CaffeineCacheAdapter;
import com.pedromossi.caching.impl.MultiLevelCacheService;
import com.pedromossi.caching.redis.RedisCacheAdapter;
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
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.adapter.MessageListenerAdapter;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.lang.Nullable;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Optional;
import java.util.concurrent.ExecutorService;

/**
 * Spring Boot auto-configuration class for the caching library.
 * Creates and configures cache beans based on application properties.
 *
 * @since 1.0.0
 */
@Configuration
@EnableConfigurationProperties(CachingProperties.class)
@ConditionalOnProperty(name = "caching.enabled", havingValue = "true", matchIfMissing = true)
@AutoConfigureAfter(RedisAutoConfiguration.class)
public class CachingAutoConfiguration {

    @Bean
    @ConditionalOnProperty(name = "caching.l1.enabled", havingValue = "true", matchIfMissing = true)
    public CacheProvider l1CacheProvider(CachingProperties properties) {
        return new CaffeineCacheAdapter(properties.getL1().getSpec());
    }

    @Configuration
    @ConditionalOnClass(RedisTemplate.class)
    @ConditionalOnProperty(name = "caching.l2.enabled", havingValue = "true", matchIfMissing = true)
    @ConditionalOnBean(RedisConnectionFactory.class)
    protected static class RedisCacheConfiguration {

        /**
         * Creates a RedisTemplate configured for caching with secure JSON serialization.
         * <p>
         * This template uses a copy of the application's primary ObjectMapper with secure
         * serialization settings. Default typing is disabled to prevent deserialization
         * vulnerabilities. Objects are serialized/deserialized based on their declared
         * types using ParameterizedTypeReference in the cache service methods.
         * <p>
         * <strong>ObjectMapper Module Inheritance:</strong>
         * The {@code objectMapper.copy()} method preserves all registered modules from the
         * primary ObjectMapper, including JavaTimeModule, custom serializers, and deserializers.
         * This ensures consistent serialization behavior between your application and cache.
         * <p>
         * <strong>When to provide a custom RedisTemplate:</strong>
         * <ul>
         *   <li>Advanced Jackson configuration (custom MixIns, PropertyNamingStrategies)</li>
         *   <li>Alternative serialization formats (Protobuf, Avro, etc.)</li>
         *   <li>Custom Redis key/value serialization strategies</li>
         *   <li>Integration with external systems requiring specific JSON format</li>
         * </ul>
         * <p>
         * To use a custom serialization strategy, define your own bean with the name
         * "cacheRedisTemplate" and it will take precedence over this auto-configured bean.
         * <p>
         * Example of custom RedisTemplate:
         * <pre>{@code
         * @Bean
         * public RedisTemplate<String, Object> cacheRedisTemplate(RedisConnectionFactory factory) {
         *     RedisTemplate<String, Object> template = new RedisTemplate<>();
         *     template.setConnectionFactory(factory);
         *     // Your custom serialization configuration here
         *     return template;
         * }
         * }</pre>
         *
         * @param connectionFactory the Redis connection factory
         * @param objectMapper the primary ObjectMapper bean (modules will be copied)
         * @return a configured RedisTemplate for caching
         */
        @Bean
        @ConditionalOnMissingBean(name = "cacheRedisTemplate")
        public RedisTemplate<String, Object> cacheRedisTemplate(
                RedisConnectionFactory connectionFactory,
                ObjectMapper objectMapper) {
            RedisTemplate<String, Object> template = new RedisTemplate<>();
            template.setConnectionFactory(connectionFactory);

            // Use a copy of the ObjectMapper without unsafe default typing
            ObjectMapper secureMapper = objectMapper.copy();
            // Do not activate default typing - this prevents deserialization vulnerabilities

            GenericJackson2JsonRedisSerializer serializer = new GenericJackson2JsonRedisSerializer(secureMapper);

            template.setKeySerializer(new StringRedisSerializer());
            template.setValueSerializer(serializer);
            template.setHashKeySerializer(new StringRedisSerializer());
            template.setHashValueSerializer(serializer);
            template.afterPropertiesSet();

            return template;
        }

        @Bean("l2CacheProvider")
        public CacheProvider l2CacheProvider(RedisTemplate<String, Object> redisTemplate, CachingProperties properties) {
            return new RedisCacheAdapter(
                    redisTemplate,
                    properties.getL2().getInvalidationTopic(),
                    properties.getL2().getTtl()
            );
        }

        @Bean
        public MessageListenerAdapter redisInvalidationListener(InvalidationHandler invalidationHandler) {
            // This listener will call the "handleMessage" method on InvalidationHandler
            return new MessageListenerAdapter(invalidationHandler, "handleMessage");
        }

        @Bean
        public InvalidationHandler invalidationHandler(
                @Qualifier("l1CacheProvider") @Nullable CacheProvider l1CacheProvider) {
            return new InvalidationHandler(l1CacheProvider);
        }

        @Bean
        @ConditionalOnMissingBean(name = "cacheRedisTemplate")
        public RedisMessageListenerContainer redisMessageListenerContainer(
                RedisConnectionFactory connectionFactory,
                MessageListenerAdapter redisInvalidationListener,
                CachingProperties properties) {
            RedisMessageListenerContainer container = new RedisMessageListenerContainer();
            container.setConnectionFactory(connectionFactory);
            container.addMessageListener(redisInvalidationListener, new ChannelTopic(properties.getL2().getInvalidationTopic()));
            return container;
        }
    }

    /**
     * Creates a dedicated thread pool for asynchronous cache operations.
     * This allows isolating cache I/O from the main application threads.
     * Users can override this bean to provide a custom executor.
     *
     * @param properties Configuration properties.
     * @return A configured Executor for caching tasks.
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


    @Bean
    public CacheService cacheService(
            @Qualifier("l1CacheProvider") Optional<CacheProvider> l1CacheProvider,
            @Qualifier("l2CacheProvider") Optional<CacheProvider> l2CacheProvider,
            @Qualifier("cachingTaskExecutor") ExecutorService executorService
    ) {
        return new MultiLevelCacheService(l1CacheProvider, l2CacheProvider, executorService);
    }

    /**
     * Inner class to handle Redis invalidation messages.
     * This is a bean so Spring can manage it and inject dependencies if needed.
     */
    public static class InvalidationHandler {
        private static final Logger log = LoggerFactory.getLogger(InvalidationHandler.class);
        private final CacheProvider l1Cache;

        public InvalidationHandler(@Nullable CacheProvider l1Cache) {
            this.l1Cache = l1Cache;
        }

        /**
         * Method called by MessageListenerAdapter when a message arrives on the topic.
         *
         * @param message The cache key to be invalidated.
         */
        public void handleMessage(String message) {
            if (l1Cache != null) {
                log.info("Received invalidation message from Redis. Invalidating L1 key: {}", message);
                l1Cache.evict(message);
            }
        }
    }
}
