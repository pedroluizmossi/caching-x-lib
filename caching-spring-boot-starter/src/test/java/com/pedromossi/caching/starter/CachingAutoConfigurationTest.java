package com.pedromossi.caching.starter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pedromossi.caching.CacheProvider;
import com.pedromossi.caching.CacheService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class CachingAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    CachingAutoConfiguration.class,
                    JacksonAutoConfiguration.class
            ));

    @Configuration
    static class MockRedisConfiguration {
        @Bean
        @Primary
        public RedisConnectionFactory redisConnectionFactory() {
            return mock(RedisConnectionFactory.class);
        }

        @Bean
        @Primary
        public ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        @Primary
        public org.springframework.data.redis.core.RedisTemplate<?, ?> cacheRedisTemplate() {
            return mock(org.springframework.data.redis.core.RedisTemplate.class);
        }
    }

    @Test
    void shouldCreateAllBeansByDefault() {
        // Testa o cenário padrão: com Redis no classpath e todas as propriedades ativas
        this.contextRunner
                .withUserConfiguration(MockRedisConfiguration.class, RedisAutoConfiguration.class)
                .run(context -> {
                    assertThat(context).hasBean("l1CacheProvider");
                    assertThat(context).hasBean("l2CacheProvider");
                    assertThat(context).hasBean("cacheService");
                    assertThat(context).hasSingleBean(CacheService.class);
                });
    }

    @Test
    void shouldNotCreateL1CacheWhenDisabled() {
        this.contextRunner
                .withPropertyValues("caching.l1.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean("l1CacheProvider");
                    assertThat(context).hasBean("cacheService"); // O serviço principal ainda deve existir
                });
    }

    @Test
    void shouldNotCreateL2CacheWhenDisabled() {
        this.contextRunner
                .withUserConfiguration(MockRedisConfiguration.class, RedisAutoConfiguration.class)
                .withPropertyValues("caching.l2.enabled=false")
                .run(context -> {
                    assertThat(context).hasBean("l1CacheProvider");
                    assertThat(context).doesNotHaveBean("l2CacheProvider");
                    assertThat(context).doesNotHaveBean("redisMessageListenerContainer");
                });
    }

    @Test
    void shouldNotCreateL2CacheWhenRedisIsNotOnClasspath() {
        // Neste teste, não adicionamos a MockRedisConfiguration
        this.contextRunner
                .run(context -> {
                    assertThat(context).hasBean("l1CacheProvider");
                    assertThat(context).doesNotHaveBean("l2CacheProvider");
                });
    }

    @Test
    void shouldNotCreateAnyCacheBeansWhenGloballyDisabled() {
        this.contextRunner
                .withPropertyValues("caching.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean("l1CacheProvider");
                    assertThat(context).doesNotHaveBean("l2CacheProvider");
                    assertThat(context).doesNotHaveBean("cacheService");
                });
    }

    @Test
    void l1CacheShouldUseCustomSpec() {
        String spec = "maximumSize=999,expireAfterWrite=13m";
        this.contextRunner
                .withPropertyValues("caching.l1.spec=" + spec)
                .run(context -> {
                    assertThat(context).hasBean("l1CacheProvider");
                    CacheProvider l1Provider = context.getBean("l1CacheProvider", CacheProvider.class);
                    // Aqui seria ideal ter uma forma de verificar a especificação.
                    // Como não temos, apenas garantimos que o bean foi criado.
                    // Em uma implementação real, o CaffeineCacheAdapter poderia expor seu spec.
                    assertThat(l1Provider).isNotNull();
                });
    }
}