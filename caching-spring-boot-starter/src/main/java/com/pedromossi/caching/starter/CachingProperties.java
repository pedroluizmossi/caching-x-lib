package com.pedromossi.caching.starter;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Configuration properties for the caching library.
 * Allows configuration via application.yml or application.properties.
 *
 * @since 1.0.0
 */
@ConfigurationProperties(prefix = "caching")
public class CachingProperties {

    /**
     * Enables or disables the caching library globally.
     */
    private boolean enabled = true;

    private L1CacheProperties l1 = new L1CacheProperties();
    private L2CacheProperties l2 = new L2CacheProperties();
    private AsyncProperties async = new AsyncProperties();


    // Getters and Setters
    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public L1CacheProperties getL1() {
        return l1;
    }

    public void setL1(L1CacheProperties l1) {
        this.l1 = l1;
    }

    public L2CacheProperties getL2() {
        return l2;
    }

    public void setL2(L2CacheProperties l2) {
        this.l2 = l2;
    }

    public AsyncProperties getAsync() {
        return async;
    }

    public void setAsync(AsyncProperties async) {
        this.async = async;
    }

    /**
     * Configuration properties for L1 (Caffeine) cache.
     */
    public static class L1CacheProperties {
        /**
         * Enables or disables L1 (Caffeine) cache.
         */
        private boolean enabled = true;
        /**
         * Caffeine cache specification string.
         * Examples: "maximumSize=500,expireAfterWrite=10m"
         */
        private String spec = "maximumSize=500,expireAfterWrite=10m";

        // Getters and Setters
        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getSpec() {
            return spec;
        }

        public void setSpec(String spec) {
            this.spec = spec;
        }
    }

    /**
     * Configuration properties for L2 (Redis) cache.
     */
    public static class L2CacheProperties {
        private boolean enabled = true;
        private String invalidationTopic = "cache:invalidation";
        private Duration ttl = Duration.ofHours(1);

        private CircuitBreakerProperties circuitBreaker = new CircuitBreakerProperties();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getInvalidationTopic() {
            return invalidationTopic;
        }

        public void setInvalidationTopic(String topic) {
            this.invalidationTopic = topic;
        }

        public Duration getTtl() {
            return ttl;
        }

        public void setTtl(Duration ttl) {
            this.ttl = ttl;
        }

        public CircuitBreakerProperties getCircuitBreaker() {
            return circuitBreaker;
        }

        public void setCircuitBreaker(CircuitBreakerProperties circuitBreaker) {
            this.circuitBreaker = circuitBreaker;
        }
    }

    /**
     * Configuration for the asynchronous task executor.
     */
    public static class AsyncProperties {
        /**
         * Core number of threads.
         */
        private int corePoolSize = 2;
        /**
         * Maximum allowed number of threads.
         */
        private int maxPoolSize = 50;
        /**
         * Queue capacity.
         */
        private int queueCapacity = 1000;

        // Getters and Setters
        public int getCorePoolSize() { return corePoolSize; }
        public void setCorePoolSize(int corePoolSize) { this.corePoolSize = corePoolSize; }
        public int getMaxPoolSize() { return maxPoolSize; }
        public void setMaxPoolSize(int maxPoolSize) { this.maxPoolSize = maxPoolSize; }
        public int getQueueCapacity() { return queueCapacity; }
        public void setQueueCapacity(int queueCapacity) { this.queueCapacity = queueCapacity; }
    }

    public static class CircuitBreakerProperties {
        private boolean enabled = true;
        private float failureRateThreshold = 50.0f;
        private Duration slowCallDurationThreshold = Duration.ofSeconds(1);
        private float slowCallRateThreshold = 100.0f;
        private int permittedNumberOfCallsInHalfOpenState = 10;
        private Duration waitDurationInOpenState = Duration.ofSeconds(60);

        // Getters e Setters
        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public float getFailureRateThreshold() {
            return failureRateThreshold;
        }

        public void setFailureRateThreshold(float failureRateThreshold) {
            this.failureRateThreshold = failureRateThreshold;
        }

        public Duration getSlowCallDurationThreshold() {
            return slowCallDurationThreshold;
        }

        public void setSlowCallDurationThreshold(Duration slowCallDurationThreshold) {
            this.slowCallDurationThreshold = slowCallDurationThreshold;
        }

        public float getSlowCallRateThreshold() {
            return slowCallRateThreshold;
        }

        public void setSlowCallRateThreshold(float slowCallRateThreshold) {
            this.slowCallRateThreshold = slowCallRateThreshold;
        }

        public int getPermittedNumberOfCallsInHalfOpenState() {
            return permittedNumberOfCallsInHalfOpenState;
        }

        public void setPermittedNumberOfCallsInHalfOpenState(int calls) {
            this.permittedNumberOfCallsInHalfOpenState = calls;
        }

        public Duration getWaitDurationInOpenState() {
            return waitDurationInOpenState;
        }

        public void setWaitDurationInOpenState(Duration waitDuration) {
            this.waitDurationInOpenState = waitDuration;
        }
    }
}
