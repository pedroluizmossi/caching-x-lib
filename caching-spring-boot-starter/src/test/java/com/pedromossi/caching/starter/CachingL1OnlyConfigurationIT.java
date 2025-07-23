package com.pedromossi.caching.starter;

import com.pedromossi.caching.CacheProvider;
import com.pedromossi.caching.CacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

@SpringBootTest(
        classes = CachingAutoConfiguration.class,
        properties = {
                "caching.l1.enabled=true",
                "caching.l1.spec=maximumSize=50,expireAfterWrite=5s",
                "caching.l2.enabled=false"
        }
)
class CachingL1OnlyConfigurationIT extends IntegrationTest {

    @Autowired
    private CacheService cacheService;

    @MockitoBean
    @Qualifier("l1CacheProvider")
    private CacheProvider l1CacheProvider;

    @BeforeEach
    void cleanupCaches() {
        reset(l1CacheProvider);
    }

    @Test
    void shouldWorkWithL1CacheOnly() {
        // Given
        String key = "l1-only-key";
        String value = "L1 Only Value - " + UUID.randomUUID();
        Supplier<String> dataLoader = () -> value;
        ParameterizedTypeReference<String> typeRef = new ParameterizedTypeReference<String>() {};

        // Configure mock behavior
        when(l1CacheProvider.get(key, eq(typeRef))).thenReturn(null).thenReturn(value);

        // --- First Call (Cache Miss) ---
        String result1 = cacheService.getOrLoad(key, typeRef, dataLoader);

        assertThat(result1).isEqualTo(value);

        // Wait for async operations
        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> {
            verify(l1CacheProvider, times(1)).put(key, value);
        });

        // --- Second Call (Cache Hit) ---
        String result2 = cacheService.getOrLoad(key, typeRef, dataLoader);

        assertThat(result2).isEqualTo(value);
        verify(l1CacheProvider, times(2)).get(key, eq(typeRef));
    }

    @Test
    void shouldInvalidateL1CacheOnly() {
        // Given
        String key = "l1-invalidate-key";
        String value = "Value to invalidate - " + UUID.randomUUID();
        Supplier<String> dataLoader = () -> value;
        ParameterizedTypeReference<String> typeRef = new ParameterizedTypeReference<String>() {};

        // Configure mock behavior
        when(l1CacheProvider.get(key, eq(typeRef))).thenReturn(null).thenReturn(value).thenReturn(null);

        // Load item into cache
        cacheService.getOrLoad(key, typeRef, dataLoader);

        // --- Invalidation ---
        cacheService.invalidate(key);

        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> {
            verify(l1CacheProvider, times(1)).evict(key);
        });

        // --- Verify it's gone ---
        reset(l1CacheProvider);
        when(l1CacheProvider.get(key, eq(typeRef))).thenReturn(null);

        String reloadedValue = cacheService.getOrLoad(key, typeRef, dataLoader);

        assertThat(reloadedValue).isEqualTo(value);
        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> {
            verify(l1CacheProvider, times(1)).put(key, value);
        });
    }

    @Test
    void shouldHandleMultipleKeysInL1Cache() {
        // Given
        String key1 = "multi-key-1";
        String key2 = "multi-key-2";
        String value1 = "Value 1 - " + UUID.randomUUID();
        String value2 = "Value 2 - " + UUID.randomUUID();
        ParameterizedTypeReference<String> typeRef = new ParameterizedTypeReference<String>() {};

        // Configure mock behavior
        when(l1CacheProvider.get(key1, eq(typeRef))).thenReturn(null).thenReturn(value1);
        when(l1CacheProvider.get(key2, eq(typeRef))).thenReturn(null).thenReturn(value2);

        // --- Load both values ---
        String result1 = cacheService.getOrLoad(key1, typeRef, () -> value1);
        String result2 = cacheService.getOrLoad(key2, typeRef, () -> value2);

        assertThat(result1).isEqualTo(value1);
        assertThat(result2).isEqualTo(value2);

        // Wait for async operations
        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> {
            verify(l1CacheProvider, times(1)).put(key1, value1);
            verify(l1CacheProvider, times(1)).put(key2, value2);
        });

        // --- Verify cache hits ---
        String cachedResult1 = cacheService.getOrLoad(key1, typeRef, () -> "should-not-be-called");
        String cachedResult2 = cacheService.getOrLoad(key2, typeRef, () -> "should-not-be-called");

        assertThat(cachedResult1).isEqualTo(value1);
        assertThat(cachedResult2).isEqualTo(value2);
    }
}
