package com.pedromossi.caching.starter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
}