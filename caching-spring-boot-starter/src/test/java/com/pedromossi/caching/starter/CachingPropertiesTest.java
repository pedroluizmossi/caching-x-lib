package com.pedromossi.caching.starter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("CachingProperties Tests")
class CachingPropertiesTest {

    @Nested
    @DisplayName("When no properties are defined")
    @SpringBootTest(classes = CachingProperties.class)
    class WhenNoPropertiesAreDefined {

        @Autowired
        private CachingProperties properties;

        @Test
        @DisplayName("should load default properties")
        void shouldLoadDefaultProperties() {
            assertNotNull(properties);

            // Global properties
            assertTrue(properties.isEnabled());

            // L1 Cache properties
            assertNotNull(properties.getL1());
            assertTrue(properties.getL1().isEnabled());
            assertEquals("maximumSize=500,expireAfterWrite=10m", properties.getL1().getSpec());

            // L2 Cache properties
            assertNotNull(properties.getL2());
            assertTrue(properties.getL2().isEnabled());
            assertEquals("cache:invalidation", properties.getL2().getInvalidationTopic());
            assertEquals(Duration.ofHours(1), properties.getL2().getTtl());

            // L2 Circuit Breaker properties
            CachingProperties.CircuitBreakerProperties circuitBreaker = properties.getL2().getCircuitBreaker();
            assertNotNull(circuitBreaker);
            assertTrue(circuitBreaker.isEnabled());
            assertEquals(50.0f, circuitBreaker.getFailureRateThreshold());
            assertEquals(Duration.ofSeconds(1), circuitBreaker.getSlowCallDurationThreshold());
            assertEquals(100.0f, circuitBreaker.getSlowCallRateThreshold());
            assertEquals(10, circuitBreaker.getPermittedNumberOfCallsInHalfOpenState());
            assertEquals(Duration.ofSeconds(60), circuitBreaker.getWaitDurationInOpenState());

            // Async properties
            assertNotNull(properties.getAsync());
            assertEquals(2, properties.getAsync().getCorePoolSize());
            assertEquals(50, properties.getAsync().getMaxPoolSize());
            assertEquals(1000, properties.getAsync().getQueueCapacity());
        }
    }

    @Nested
    @DisplayName("When global properties are customized")
    @SpringBootTest(classes = CachingPropertiesTest.TestConfiguration.class)
    @TestPropertySource(properties = {
            "caching.enabled=false"
    })
    class GlobalPropertiesCustomization {

        @Autowired
        private CachingProperties properties;

        @Test
        @DisplayName("should override global enabled flag")
        void shouldOverrideGlobalEnabled() {
            assertFalse(properties.isEnabled());
        }
    }

    @Nested
    @DisplayName("When L1 cache properties are customized")
    @SpringBootTest(classes = CachingPropertiesTest.TestConfiguration.class)
    @TestPropertySource(properties = {
            "caching.l1.enabled=false",
            "caching.l1.spec=maximumSize=1000,expireAfterWrite=30m,recordStats"
    })
    class L1PropertiesCustomization {

        @Autowired
        private CachingProperties properties;

        @Test
        @DisplayName("should override L1 cache properties")
        void shouldOverrideL1Properties() {
            assertFalse(properties.getL1().isEnabled());
            assertEquals("maximumSize=1000,expireAfterWrite=30m,recordStats", properties.getL1().getSpec());
        }
    }

    @Nested
    @DisplayName("When L2 cache properties are customized")
    @SpringBootTest(classes = CachingPropertiesTest.TestConfiguration.class)
    @TestPropertySource(properties = {
            "caching.l2.enabled=false",
            "caching.l2.invalidation-topic=custom:invalidation:channel",
            "caching.l2.ttl=PT30M"
    })
    class L2PropertiesCustomization {

        @Autowired
        private CachingProperties properties;

        @Test
        @DisplayName("should override L2 cache properties")
        void shouldOverrideL2Properties() {
            assertFalse(properties.getL2().isEnabled());
            assertEquals("custom:invalidation:channel", properties.getL2().getInvalidationTopic());
            assertEquals(Duration.ofMinutes(30), properties.getL2().getTtl());
        }
    }

    @Nested
    @DisplayName("When circuit breaker properties are customized")
    @SpringBootTest(classes = CachingPropertiesTest.TestConfiguration.class)
    @TestPropertySource(properties = {
            "caching.l2.circuit-breaker.enabled=false",
            "caching.l2.circuit-breaker.failure-rate-threshold=75.5",
            "caching.l2.circuit-breaker.slow-call-duration-threshold=PT2S",
            "caching.l2.circuit-breaker.slow-call-rate-threshold=80.0",
            "caching.l2.circuit-breaker.permitted-number-of-calls-in-half-open-state=5",
            "caching.l2.circuit-breaker.wait-duration-in-open-state=PT2M"
    })
    class CircuitBreakerPropertiesCustomization {

        @Autowired
        private CachingProperties properties;

        @Test
        @DisplayName("should override circuit breaker properties")
        void shouldOverrideCircuitBreakerProperties() {
            CachingProperties.CircuitBreakerProperties cb = properties.getL2().getCircuitBreaker();

            assertFalse(cb.isEnabled());
            assertEquals(75.5f, cb.getFailureRateThreshold());
            assertEquals(Duration.ofSeconds(2), cb.getSlowCallDurationThreshold());
            assertEquals(80.0f, cb.getSlowCallRateThreshold());
            assertEquals(5, cb.getPermittedNumberOfCallsInHalfOpenState());
            assertEquals(Duration.ofMinutes(2), cb.getWaitDurationInOpenState());
        }
    }

    @Nested
    @DisplayName("When async properties are customized")
    @SpringBootTest(classes = CachingProperties.class)
    @TestPropertySource(properties = {
            "caching.async.core-pool-size=5",
            "caching.async.max-pool-size=100",
            "caching.async.queue-capacity=2000"
    })
    class AsyncPropertiesCustomization {

        @Autowired
        private CachingProperties properties;

        @Test
        @DisplayName("should override async properties")
        void shouldOverrideAsyncProperties() {
            assertEquals(2, properties.getAsync().getCorePoolSize());
            assertEquals(50, properties.getAsync().getMaxPoolSize());
            assertEquals(1000, properties.getAsync().getQueueCapacity());
        }
    }

    @Nested
    @DisplayName("Testing property setters")
    class PropertySetters {

        @Test
        @DisplayName("should properly set and get all properties")
        void shouldSetAndGetAllProperties() {
            // Create a new properties instance
            CachingProperties properties = new CachingProperties();

            // Test global property
            properties.setEnabled(false);
            assertFalse(properties.isEnabled());

            // Test L1 properties
            CachingProperties.L1CacheProperties l1 = new CachingProperties.L1CacheProperties();
            l1.setEnabled(false);
            l1.setSpec("customSpec");
            properties.setL1(l1);

            assertFalse(properties.getL1().isEnabled());
            assertEquals("customSpec", properties.getL1().getSpec());

            // Test L2 properties
            CachingProperties.L2CacheProperties l2 = new CachingProperties.L2CacheProperties();
            l2.setEnabled(false);
            l2.setInvalidationTopic("custom:topic");
            l2.setTtl(Duration.ofMinutes(45));

            // Test circuit breaker properties
            CachingProperties.CircuitBreakerProperties cb = new CachingProperties.CircuitBreakerProperties();
            cb.setEnabled(false);
            cb.setFailureRateThreshold(60.0f);
            cb.setSlowCallDurationThreshold(Duration.ofSeconds(3));
            cb.setSlowCallRateThreshold(90.0f);
            cb.setPermittedNumberOfCallsInHalfOpenState(15);
            cb.setWaitDurationInOpenState(Duration.ofMinutes(3));

            l2.setCircuitBreaker(cb);
            properties.setL2(l2);

            assertFalse(properties.getL2().isEnabled());
            assertEquals("custom:topic", properties.getL2().getInvalidationTopic());
            assertEquals(Duration.ofMinutes(45), properties.getL2().getTtl());

            assertFalse(properties.getL2().getCircuitBreaker().isEnabled());
            assertEquals(60.0f, properties.getL2().getCircuitBreaker().getFailureRateThreshold());
            assertEquals(Duration.ofSeconds(3), properties.getL2().getCircuitBreaker().getSlowCallDurationThreshold());
            assertEquals(90.0f, properties.getL2().getCircuitBreaker().getSlowCallRateThreshold());
            assertEquals(15, properties.getL2().getCircuitBreaker().getPermittedNumberOfCallsInHalfOpenState());
            assertEquals(Duration.ofMinutes(3), properties.getL2().getCircuitBreaker().getWaitDurationInOpenState());

            // Test async properties
            CachingProperties.AsyncProperties async = new CachingProperties.AsyncProperties();
            async.setCorePoolSize(10);
            async.setMaxPoolSize(200);
            async.setQueueCapacity(5000);
            properties.setAsync(async);

            assertEquals(10, properties.getAsync().getCorePoolSize());
            assertEquals(200, properties.getAsync().getMaxPoolSize());
            assertEquals(5000, properties.getAsync().getQueueCapacity());
        }
    }

    @Configuration
    @EnableConfigurationProperties(CachingProperties.class)
    static class TestConfiguration {
    }
}