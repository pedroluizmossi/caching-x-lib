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
                "caching.l1.spec=maximumSize=100,expireAfterWrite=10s",
                "caching.l2.ttl=PT20S"
        }
)
class CachingAutoConfigurationIT extends IntegrationTest {

    @Autowired
    private CacheService cacheService;

    @MockitoBean
    @Qualifier("l1CacheProvider")
    private CacheProvider l1CacheProvider;

    @MockitoBean
    @Qualifier("l2CacheProvider")
    private CacheProvider l2CacheProvider;

    @BeforeEach
    void cleanupCaches() {
        reset(l1CacheProvider, l2CacheProvider);
    }

    @Test
    void shouldLoadFromSourceOnMissAndThenHitCache() {
        // Given
        String key = "user:1";
        String value = "John Doe - " + UUID.randomUUID();
        Supplier<String> dataLoader = () -> value;
        ParameterizedTypeReference<String> typeRef = new ParameterizedTypeReference<String>() {};

        // Configure mock behavior
        when(l1CacheProvider.get(key, eq(typeRef))).thenReturn(null).thenReturn(value);
        when(l2CacheProvider.get(key, eq(typeRef))).thenReturn(null);

        // --- 1. First Call (Cache Miss) ---
        String result1 = cacheService.getOrLoad(key, typeRef, dataLoader);

        assertThat(result1).isEqualTo(value);

        // Wait for async operations
        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> {
            verify(l1CacheProvider, times(1)).put(key, value);
            verify(l2CacheProvider, times(1)).put(key, value);
        });

        // --- 2. Second Call (Cache Hit) ---
        String result2 = cacheService.getOrLoad(key, typeRef, dataLoader);

        assertThat(result2).isEqualTo(value);
        verify(l1CacheProvider, times(2)).get(key, eq(typeRef));
    }

    @Test
    void shouldInvalidateCacheAcrossAllLevels() {
        // Given
        String key = "product:123";
        String value = "Notebook - " + UUID.randomUUID();
        Supplier<String> dataLoader = () -> value;
        ParameterizedTypeReference<String> typeRef = new ParameterizedTypeReference<String>() {};

        // Configure mock behavior for initial load
        when(l1CacheProvider.get(key, eq(typeRef))).thenReturn(null).thenReturn(value).thenReturn(null);
        when(l2CacheProvider.get(key, eq(typeRef))).thenReturn(null);

        // Load item into cache
        cacheService.getOrLoad(key, typeRef, dataLoader);

        // --- Invalidation ---
        cacheService.invalidate(key);

        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> {
            verify(l1CacheProvider, times(1)).evict(key);
            verify(l2CacheProvider, times(1)).evict(key);
        });

        // --- Verify it's gone ---
        reset(l1CacheProvider, l2CacheProvider);
        when(l1CacheProvider.get(key, eq(typeRef))).thenReturn(null);
        when(l2CacheProvider.get(key, eq(typeRef))).thenReturn(null);

        cacheService.getOrLoad(key, typeRef, dataLoader);

        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> {
            verify(l1CacheProvider, times(1)).put(key, value);
            verify(l2CacheProvider, times(1)).put(key, value);
        });
    }
}