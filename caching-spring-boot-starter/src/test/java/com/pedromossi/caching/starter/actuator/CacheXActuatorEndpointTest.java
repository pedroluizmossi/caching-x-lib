package com.pedromossi.caching.starter.actuator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pedromossi.caching.CacheProvider;
import com.pedromossi.caching.CacheService;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.ParameterizedTypeReference;

/**
 * Unit tests for {@link CacheXActuatorEndpoint}.
 *
 * <p>This test class verifies the Spring Boot Actuator endpoint functionality
 * for cache inspection and management operations. It tests the endpoint's ability
 * to provide cache status, inspect cached keys, and evict cache entries.</p>
 */
@ExtendWith(MockitoExtension.class)
class CacheXActuatorEndpointTest {

    @Mock private CacheService cacheService;
    @Mock private CacheProvider l1CacheProvider;
    @Mock private CacheProvider l2CacheProvider;

    private CacheXActuatorEndpoint endpoint;

    @BeforeEach
    void setUp() {
        endpoint = new CacheXActuatorEndpoint(
                cacheService,
                Optional.of(l1CacheProvider),
                Optional.of(l2CacheProvider)
        );
    }

    @Test
    @DisplayName("getCacheStatus should return enabled status when both L1 and L2 are present")
    void getCacheStatus_shouldReturnEnabledStatusWhenBothCachesPresent() {
        // When
        Map<String, Object> status = endpoint.getCacheStatus();

        // Then
        assertThat(status).containsEntry("l1Cache", "ENABLED");
        assertThat(status).containsEntry("l2Cache", "ENABLED");
        assertThat(status).containsKey("actions");

        @SuppressWarnings("unchecked")
        Map<String, String> actions = (Map<String, String>) status.get("actions");
        assertThat(actions).containsEntry("inspectKey", "GET /actuator/cachex/{key}");
        assertThat(actions).containsEntry("evictKey", "DELETE /actuator/cachex/{key}");
    }

    @Test
    @DisplayName("getCacheStatus should return disabled status when L1 cache is not present")
    void getCacheStatus_shouldReturnDisabledStatusWhenL1CacheNotPresent() {
        // Given
        endpoint = new CacheXActuatorEndpoint(
                cacheService,
                Optional.empty(),
                Optional.of(l2CacheProvider)
        );

        // When
        Map<String, Object> status = endpoint.getCacheStatus();

        // Then
        assertThat(status).containsEntry("l1Cache", "DISABLED");
        assertThat(status).containsEntry("l2Cache", "ENABLED");
    }

    @Test
    @DisplayName("getCacheStatus should return disabled status when L2 cache is not present")
    void getCacheStatus_shouldReturnDisabledStatusWhenL2CacheNotPresent() {
        // Given
        endpoint = new CacheXActuatorEndpoint(
                cacheService,
                Optional.of(l1CacheProvider),
                Optional.empty()
        );

        // When
        Map<String, Object> status = endpoint.getCacheStatus();

        // Then
        assertThat(status).containsEntry("l1Cache", "ENABLED");
        assertThat(status).containsEntry("l2Cache", "DISABLED");
    }

    @Test
    @DisplayName("getCacheStatus should return disabled status when both caches are not present")
    void getCacheStatus_shouldReturnDisabledStatusWhenBothCachesNotPresent() {
        // Given
        endpoint = new CacheXActuatorEndpoint(
                cacheService,
                Optional.empty(),
                Optional.empty()
        );

        // When
        Map<String, Object> status = endpoint.getCacheStatus();

        // Then
        assertThat(status).containsEntry("l1Cache", "DISABLED");
        assertThat(status).containsEntry("l2Cache", "DISABLED");
        assertThat(status).containsKey("actions");
    }

    @Test
    @DisplayName("inspectKey should return L1 when value found in L1 cache")
    void inspectKey_shouldReturnL1WhenValueFoundInL1Cache() {
        // Given
        String key = "test-key";
        String cachedValue = "test-value";
        when(l1CacheProvider.get(eq(key), any(ParameterizedTypeReference.class)))
                .thenReturn(cachedValue);

        // When
        Map<String, Object> details = endpoint.inspectKey(key);

        // Then
        assertThat(details).containsEntry("key", key);
        assertThat(details).containsEntry("foundIn", "L1");
        assertThat(details).containsEntry("valueType", "java.lang.String");

        verify(l1CacheProvider).get(eq(key), any(ParameterizedTypeReference.class));
    }

    @Test
    @DisplayName("inspectKey should return L2 when value not found in L1 but found in L2")
    void inspectKey_shouldReturnL2WhenValueNotFoundInL1ButFoundInL2() {
        // Given
        String key = "test-key";
        Integer cachedValue = 42;
        when(l1CacheProvider.get(eq(key), any(ParameterizedTypeReference.class)))
                .thenReturn(null);
        when(l2CacheProvider.get(eq(key), any(ParameterizedTypeReference.class)))
                .thenReturn(cachedValue);

        // When
        Map<String, Object> details = endpoint.inspectKey(key);

        // Then
        assertThat(details).containsEntry("key", key);
        assertThat(details).containsEntry("foundIn", "L2");
        assertThat(details).containsEntry("valueType", "java.lang.Integer");

        verify(l1CacheProvider).get(eq(key), any(ParameterizedTypeReference.class));
        verify(l2CacheProvider).get(eq(key), any(ParameterizedTypeReference.class));
    }

    @Test
    @DisplayName("inspectKey should return NONE when value not found in either cache")
    void inspectKey_shouldReturnNoneWhenValueNotFoundInEitherCache() {
        // Given
        String key = "missing-key";
        when(l1CacheProvider.get(eq(key), any(ParameterizedTypeReference.class)))
                .thenReturn(null);
        when(l2CacheProvider.get(eq(key), any(ParameterizedTypeReference.class)))
                .thenReturn(null);

        // When
        Map<String, Object> details = endpoint.inspectKey(key);

        // Then
        assertThat(details).containsEntry("key", key);
        assertThat(details).containsEntry("foundIn", "NONE");
        assertThat(details).doesNotContainKey("valueType");

        verify(l1CacheProvider).get(eq(key), any(ParameterizedTypeReference.class));
        verify(l2CacheProvider).get(eq(key), any(ParameterizedTypeReference.class));
    }

    @Test
    @DisplayName("inspectKey should handle L1 cache not present gracefully")
    void inspectKey_shouldHandleL1CacheNotPresentGracefully() {
        // Given
        endpoint = new CacheXActuatorEndpoint(
                cacheService,
                Optional.empty(),
                Optional.of(l2CacheProvider)
        );
        String key = "test-key";
        String cachedValue = "test-value";
        when(l2CacheProvider.get(eq(key), any(ParameterizedTypeReference.class)))
                .thenReturn(cachedValue);

        // When
        Map<String, Object> details = endpoint.inspectKey(key);

        // Then
        assertThat(details).containsEntry("key", key);
        assertThat(details).containsEntry("foundIn", "L2");
        assertThat(details).containsEntry("valueType", "java.lang.String");

        verify(l2CacheProvider).get(eq(key), any(ParameterizedTypeReference.class));
    }

    @Test
    @DisplayName("inspectKey should handle L2 cache not present gracefully")
    void inspectKey_shouldHandleL2CacheNotPresentGracefully() {
        // Given
        endpoint = new CacheXActuatorEndpoint(
                cacheService,
                Optional.of(l1CacheProvider),
                Optional.empty()
        );
        String key = "test-key";
        when(l1CacheProvider.get(eq(key), any(ParameterizedTypeReference.class)))
                .thenReturn(null);

        // When
        Map<String, Object> details = endpoint.inspectKey(key);

        // Then
        assertThat(details).containsEntry("key", key);
        assertThat(details).containsEntry("foundIn", "NONE");
        assertThat(details).doesNotContainKey("valueType");

        verify(l1CacheProvider).get(eq(key), any(ParameterizedTypeReference.class));
    }

    @Test
    @DisplayName("inspectKey should handle both caches not present gracefully")
    void inspectKey_shouldHandleBothCachesNotPresentGracefully() {
        // Given
        endpoint = new CacheXActuatorEndpoint(
                cacheService,
                Optional.empty(),
                Optional.empty()
        );
        String key = "test-key";

        // When
        Map<String, Object> details = endpoint.inspectKey(key);

        // Then
        assertThat(details).containsEntry("key", key);
        assertThat(details).containsEntry("foundIn", "NONE");
        assertThat(details).doesNotContainKey("valueType");
    }

    @Test
    @DisplayName("inspectKey should handle complex object types correctly")
    void inspectKey_shouldHandleComplexObjectTypesCorrectly() {
        // Given
        String key = "complex-key";
        Map<String, Object> cachedValue = Map.of("name", "John", "age", 30);
        when(l1CacheProvider.get(eq(key), any(ParameterizedTypeReference.class)))
                .thenReturn(cachedValue);

        // When
        Map<String, Object> details = endpoint.inspectKey(key);

        // Then
        assertThat(details).containsEntry("key", key);
        assertThat(details).containsEntry("foundIn", "L1");
        assertThat(details).containsEntry("valueType", "java.util.ImmutableCollections$MapN");
    }

    @Test
    @DisplayName("evictKey should call cache service invalidate and return success response")
    void evictKey_shouldCallCacheServiceInvalidateAndReturnSuccessResponse() {
        // Given
        String key = "evict-key";

        // When
        Map<String, String> response = endpoint.evictKey(key);

        // Then
        assertThat(response).containsEntry("key", key);
        assertThat(response).containsEntry("status", "INVALIDATION_SCHEDULED");

        verify(cacheService).invalidate(key);
    }

    @Test
    @DisplayName("constructor should handle all cache provider combinations")
    void constructor_shouldHandleAllCacheProviderCombinations() {
        // Test with both present
        CacheXActuatorEndpoint endpoint1 = new CacheXActuatorEndpoint(
                cacheService, Optional.of(l1CacheProvider), Optional.of(l2CacheProvider));
        assertThat(endpoint1).isNotNull();

        // Test with only L1 present
        CacheXActuatorEndpoint endpoint2 = new CacheXActuatorEndpoint(
                cacheService, Optional.of(l1CacheProvider), Optional.empty());
        assertThat(endpoint2).isNotNull();

        // Test with only L2 present
        CacheXActuatorEndpoint endpoint3 = new CacheXActuatorEndpoint(
                cacheService, Optional.empty(), Optional.of(l2CacheProvider));
        assertThat(endpoint3).isNotNull();

        // Test with neither present
        CacheXActuatorEndpoint endpoint4 = new CacheXActuatorEndpoint(
                cacheService, Optional.empty(), Optional.empty());
        assertThat(endpoint4).isNotNull();
    }

    @Test
    @DisplayName("inspectKey should handle null values from cache providers")
    void inspectKey_shouldHandleNullValuesFromCacheProviders() {
        // Given
        String key = "null-value-key";
        when(l1CacheProvider.get(eq(key), any(ParameterizedTypeReference.class)))
                .thenReturn(null);
        when(l2CacheProvider.get(eq(key), any(ParameterizedTypeReference.class)))
                .thenReturn(null);

        // When
        Map<String, Object> details = endpoint.inspectKey(key);

        // Then
        assertThat(details).containsEntry("key", key);
        assertThat(details).containsEntry("foundIn", "NONE");
        assertThat(details).doesNotContainKey("valueType");
    }

    @Test
    @DisplayName("getCacheStatus should maintain consistent structure")
    void getCacheStatus_shouldMaintainConsistentStructure() {
        // When
        Map<String, Object> status = endpoint.getCacheStatus();

        // Then - Verify structure
        assertThat(status).hasSize(3);
        assertThat(status).containsKeys("l1Cache", "l2Cache", "actions");

        // Verify l1Cache and l2Cache values are strings
        assertThat(status.get("l1Cache")).isInstanceOf(String.class);
        assertThat(status.get("l2Cache")).isInstanceOf(String.class);

        // Verify actions is a map with expected entries
        @SuppressWarnings("unchecked")
        Map<String, String> actions = (Map<String, String>) status.get("actions");
        assertThat(actions).hasSize(2);
        assertThat(actions).containsKeys("inspectKey", "evictKey");
    }

    @Test
    @DisplayName("inspectKey should maintain consistent response structure")
    void inspectKey_shouldMaintainConsistentResponseStructure() {
        // Given
        String key = "structure-test-key";
        when(l1CacheProvider.get(eq(key), any(ParameterizedTypeReference.class)))
                .thenReturn("test-value");

        // When
        Map<String, Object> details = endpoint.inspectKey(key);

        // Then - Verify required fields are always present
        assertThat(details).containsKey("key");
        assertThat(details).containsKey("foundIn");
        assertThat(details.get("key")).isEqualTo(key);
        assertThat(details.get("foundIn")).isInstanceOf(String.class);

        // When value is found, valueType should be present
        if (!"NONE".equals(details.get("foundIn"))) {
            assertThat(details).containsKey("valueType");
            assertThat(details.get("valueType")).isInstanceOf(String.class);
        }
    }

    @Test
    @DisplayName("evictKey should maintain consistent response structure")
    void evictKey_shouldMaintainConsistentResponseStructure() {
        // Given
        String key = "evict-structure-test";

        // When
        Map<String, String> response = endpoint.evictKey(key);

        // Then
        assertThat(response).hasSize(2);
        assertThat(response).containsKeys("key", "status");
        assertThat(response.get("key")).isEqualTo(key);
        assertThat(response.get("status")).isEqualTo("INVALIDATION_SCHEDULED");
    }
}
