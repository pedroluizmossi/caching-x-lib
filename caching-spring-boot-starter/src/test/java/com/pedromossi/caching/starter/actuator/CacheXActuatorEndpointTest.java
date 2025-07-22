package com.pedromossi.caching.starter.actuator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.pedromossi.caching.CacheProvider;
import com.pedromossi.caching.CacheService;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.ParameterizedTypeReference;

@ExtendWith(MockitoExtension.class)
@DisplayName("CacheXActuatorEndpoint")
class CacheXActuatorEndpointTest {

    @Mock private CacheService cacheService;
    @Mock private CacheProvider l1CacheProvider;
    @Mock private CacheProvider l2CacheProvider;

    private CacheXActuatorEndpoint endpoint;

    @Nested
    @DisplayName("getCacheStatus()")
    class GetCacheStatusTests {

        @Test
        @DisplayName("should return correct status for all cache provider combinations")
        void shouldReturnCorrectStatus() {
            // Both present
            endpoint = new CacheXActuatorEndpoint(cacheService, Optional.of(l1CacheProvider), Optional.of(l2CacheProvider));
            assertThat(endpoint.getCacheStatus()).containsEntry("l1Cache", "ENABLED").containsEntry("l2Cache", "ENABLED");

            // L1 only
            endpoint = new CacheXActuatorEndpoint(cacheService, Optional.of(l1CacheProvider), Optional.empty());
            assertThat(endpoint.getCacheStatus()).containsEntry("l1Cache", "ENABLED").containsEntry("l2Cache", "DISABLED");

            // L2 only
            endpoint = new CacheXActuatorEndpoint(cacheService, Optional.empty(), Optional.of(l2CacheProvider));
            assertThat(endpoint.getCacheStatus()).containsEntry("l1Cache", "DISABLED").containsEntry("l2Cache", "ENABLED");

            // Neither present
            endpoint = new CacheXActuatorEndpoint(cacheService, Optional.empty(), Optional.empty());
            assertThat(endpoint.getCacheStatus()).containsEntry("l1Cache", "DISABLED").containsEntry("l2Cache", "DISABLED");
        }

        @Test
        @DisplayName("should return a response with a consistent structure")
        void shouldReturnConsistentStructure() {
            endpoint = new CacheXActuatorEndpoint(cacheService, Optional.of(l1CacheProvider), Optional.of(l2CacheProvider));
            Map<String, Object> status = endpoint.getCacheStatus();

            assertThat(status).containsKeys("l1Cache", "l2Cache", "actions");
            assertThat(status.get("actions")).isInstanceOf(Map.class);
            @SuppressWarnings("unchecked")
            Map<String, String> actions = (Map<String, String>) status.get("actions");
            assertThat(actions).containsEntry("inspectKey", "GET /actuator/cachex/{key}");
            assertThat(actions).containsEntry("evictKey", "DELETE /actuator/cachex/{key}");
        }
    }

    @Nested
    @DisplayName("inspectKey()")
    class InspectKeyTests {

        @Test
        @DisplayName("should return 'L1' when key is found in L1 cache")
        void shouldReturnL1WhenKeyFoundInL1() {
            endpoint = new CacheXActuatorEndpoint(cacheService, Optional.of(l1CacheProvider), Optional.of(l2CacheProvider));
            String key = "test-key";
            when(l1CacheProvider.get(eq(key), any(ParameterizedTypeReference.class))).thenReturn("value");

            Map<String, Object> details = endpoint.inspectKey(key);

            assertThat(details)
                    .containsEntry("key", key)
                    .containsEntry("foundIn", "L1")
                    .containsEntry("valueType", String.class.getName());
            verifyNoInteractions(l2CacheProvider);
        }

        @Test
        @DisplayName("should return 'L2' when key is found only in L2 cache")
        void shouldReturnL2WhenKeyFoundOnlyInL2() {
            endpoint = new CacheXActuatorEndpoint(cacheService, Optional.of(l1CacheProvider), Optional.of(l2CacheProvider));
            String key = "test-key";
            when(l1CacheProvider.get(eq(key), any(ParameterizedTypeReference.class))).thenReturn(null);
            when(l2CacheProvider.get(eq(key), any(ParameterizedTypeReference.class))).thenReturn(42);

            Map<String, Object> details = endpoint.inspectKey(key);

            assertThat(details)
                    .containsEntry("key", key)
                    .containsEntry("foundIn", "L2")
                    .containsEntry("valueType", Integer.class.getName());
        }

        @Test
        @DisplayName("should return 'NONE' when key is not in any cache")
        void shouldReturnNoneWhenKeyIsMissing() {
            endpoint = new CacheXActuatorEndpoint(cacheService, Optional.of(l1CacheProvider), Optional.of(l2CacheProvider));
            String key = "test-key";
            when(l1CacheProvider.get(eq(key), any(ParameterizedTypeReference.class))).thenReturn(null);
            when(l2CacheProvider.get(eq(key), any(ParameterizedTypeReference.class))).thenReturn(null);

            Map<String, Object> details = endpoint.inspectKey(key);

            assertThat(details)
                    .containsEntry("key", key)
                    .containsEntry("foundIn", "NONE")
                    .doesNotContainKey("valueType");
        }

        @Test
        @DisplayName("should handle missing cache providers gracefully")
        void shouldHandleMissingCacheProviders() {
            // L1 is missing, should check L2
            endpoint = new CacheXActuatorEndpoint(cacheService, Optional.empty(), Optional.of(l2CacheProvider));
            when(l2CacheProvider.get(any(), any())).thenReturn(null);
            assertThat(endpoint.inspectKey("key").get("foundIn")).isEqualTo("NONE");
            verify(l2CacheProvider).get(any(), any());

            // Both are missing
            endpoint = new CacheXActuatorEndpoint(cacheService, Optional.empty(), Optional.empty());
            assertThat(endpoint.inspectKey("key").get("foundIn")).isEqualTo("NONE");
        }
    }

    @Nested
    @DisplayName("evictKey()")
    class EvictKeyTests {

        @Test
        @DisplayName("should call cacheService.invalidate and return success response")
        void shouldCallInvalidateAndReturnSuccess() {
            endpoint = new CacheXActuatorEndpoint(cacheService, Optional.empty(), Optional.empty());
            String key = "evict-key";

            Map<String, String> response = endpoint.evictKey(key);

            assertThat(response)
                    .containsEntry("key", key)
                    .containsEntry("status", "INVALIDATION_SCHEDULED");
            verify(cacheService).invalidate(key);
        }
    }
}