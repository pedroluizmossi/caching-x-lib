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
        /**
         * Enables or disables L2 (Redis) cache.
         */
        private boolean enabled = true;
        /**
         * Redis topic used for publishing and listening to invalidation events.
         */
        private String invalidationTopic = "cache:invalidation";
        /**
         * Default time-to-live (TTL) for items in Redis cache.
         */
        private Duration ttl = Duration.ofHours(1);

        // Getters and Setters
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
    }
}
