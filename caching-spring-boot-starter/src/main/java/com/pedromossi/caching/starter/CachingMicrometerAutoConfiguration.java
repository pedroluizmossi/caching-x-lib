package com.pedromossi.caching.starter;

import com.pedromossi.caching.CacheProvider;
import com.pedromossi.caching.caffeine.CaffeineCacheAdapter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.Collections;

/**
 * Auto-configuration for Micrometer metrics integration with the caching library.
 * This configuration is separated from the main CachingAutoConfiguration to provide
 * better modularity and allow easier customization of metrics configuration.
 */
@Configuration
@ConditionalOnBean(MeterRegistry.class)
@ConditionalOnProperty(name = "caching.l1.recordStats", havingValue = "true", matchIfMissing = true)
@AutoConfigureAfter(CachingAutoConfiguration.class)
@EnableConfigurationProperties(CachingProperties.class)
@ConditionalOnClass(CaffeineCacheMetrics.class)
public class CachingMicrometerAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(CachingMicrometerAutoConfiguration.class);

    private final CacheProvider l1CacheProvider;
    private final MeterRegistry meterRegistry;
    private final CachingProperties properties;

    public CachingMicrometerAutoConfiguration(
            @Qualifier("l1CacheProvider") CacheProvider l1CacheProvider,
            MeterRegistry meterRegistry,
            CachingProperties properties) {
        this.l1CacheProvider = l1CacheProvider;
        this.meterRegistry = meterRegistry;
        this.properties = properties;
    }

    /**
     * Binds the L1 cache to Micrometer metrics if the spec contains "recordStats".
     * This method is called after the bean is constructed to ensure all dependencies are available.
     */
    @PostConstruct
    public void bindL1CacheToMicrometer() {
        String spec = properties.getL1().getSpec();
        if (spec != null && spec.contains("recordStats")) {
            if (l1CacheProvider instanceof CaffeineCacheAdapter adapter) {
                CaffeineCacheMetrics.monitor(meterRegistry, adapter.getNativeCache(), "l1Cache", Collections.emptyList());
                log.info("Metrics for L1 cache are now being recorded. " +
                        "You can view them in your monitoring system under the 'l1Cache' name.");
            }
        } else {
            log.warn("Metrics for L1 cache are not enabled. " +
                    "To enable, ensure your Caffeine spec includes 'recordStats'. " +
                    "Current spec: {}", spec);
        }
    }
}
