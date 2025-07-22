package com.pedromossi.caching.starter;

import com.pedromossi.caching.CacheProvider;
import com.pedromossi.caching.CacheService;
import com.pedromossi.caching.caffeine.CaffeineCacheAdapter;
import com.pedromossi.caching.redis.RedisCacheAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

public class CachingAutoConfigurationConditionsIT {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    CachingAutoConfiguration.class,
                    RedisAutoConfiguration.class
            ));

    @Test
    void shouldCreateAllDefaultBeansWhenEnabled() {
        contextRunner
                .withPropertyValues(
                        "caching.enabled=true",
                        "spring.data.redis.host=localhost",
                        "spring.data.redis.port=6379"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(CacheService.class);
                    assertThat(context).hasBean("l1CacheProvider");
                    assertThat(context).hasBean("l2CacheProvider");

                    assertThat(context.getBean("l1CacheProvider")).isInstanceOf(CaffeineCacheAdapter.class);
                    assertThat(context.getBean("l2CacheProvider")).isInstanceOf(RedisCacheAdapter.class);
                });
    }

    @Test
    void shouldOnlyCreateL1CacheWhenL2IsDisabled() {
        contextRunner
                .withPropertyValues("caching.l2.enabled=false")
                .run(context -> {
                    assertThat(context).hasSingleBean(CacheService.class);
                    assertThat(context).hasBean("l1CacheProvider");
                    assertThat(context).doesNotHaveBean("l2CacheProvider");
                });
    }

    @Test
    void shouldOnlyCreateL2CacheWhenL1IsDisabled() {
        contextRunner
                .withPropertyValues(
                        "caching.l1.enabled=false",
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
    void shouldNotCreateAnyCacheBeansWhenGloballyDisabled() {
        contextRunner
                .withPropertyValues("caching.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(CacheService.class);
                    assertThat(context).doesNotHaveBean(CacheProvider.class);
                });
    }
}