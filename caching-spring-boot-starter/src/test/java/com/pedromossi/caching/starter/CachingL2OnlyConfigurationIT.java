package com.pedromossi.caching.starter;

import com.pedromossi.caching.CacheProvider;
import com.pedromossi.caching.CacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.core.ParameterizedTypeReference;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

@SpringBootTest(
        classes = CachingAutoConfiguration.class,
        properties = {
                "caching.l1.enabled=false",
                "caching.l2.enabled=true",
                "caching.l2.ttl=PT30S",
                "caching.l2.invalidation-topic=test:invalidation"
        }
)
public class CachingL2OnlyConfigurationIT extends IntegrationTest {

    @Autowired
    private CacheService cacheService;

    @MockBean
    @Qualifier("l2CacheProvider")
    private CacheProvider l2CacheProvider;

    @BeforeEach
    void cleanupCaches() {
        reset(l2CacheProvider);
    }

    @Test
    void shouldWorkWithL2CacheOnly() {
        // Given
        String key = "l2-only-key";
        String value = "L2 Only Value - " + UUID.randomUUID();
        Supplier<String> dataLoader = () -> value;
        ParameterizedTypeReference<String> typeRef = new ParameterizedTypeReference<String>() {};

        // Configure mock behavior
        when(l2CacheProvider.get(eq(key), eq(typeRef))).thenReturn(null).thenReturn(value);

        // --- First Call (Cache Miss) ---
        String result1 = cacheService.getOrLoad(key, typeRef, dataLoader);

        assertThat(result1).isEqualTo(value);

        // Wait for async operations
        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> {
            verify(l2CacheProvider, times(1)).put(key, value);
        });

        // --- Second Call (Cache Hit) ---
        String result2 = cacheService.getOrLoad(key, typeRef, dataLoader);

        assertThat(result2).isEqualTo(value);
        verify(l2CacheProvider, times(2)).get(eq(key), eq(typeRef));
    }

    @Test
    void shouldInvalidateL2CacheOnly() {
        // Given
        String key = "l2-invalidate-key";
        String value = "Redis Value to invalidate - " + UUID.randomUUID();
        Supplier<String> dataLoader = () -> value;
        ParameterizedTypeReference<String> typeRef = new ParameterizedTypeReference<String>() {};

        // Configure mock behavior
        when(l2CacheProvider.get(eq(key), eq(typeRef))).thenReturn(null).thenReturn(value).thenReturn(null);

        // Load item into cache
        cacheService.getOrLoad(key, typeRef, dataLoader);

        // --- Invalidation ---
        cacheService.invalidate(key);

        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> {
            verify(l2CacheProvider, times(1)).evict(key);
        });

        // --- Verify it's gone ---
        reset(l2CacheProvider);
        when(l2CacheProvider.get(eq(key), eq(typeRef))).thenReturn(null);

        String reloadedValue = cacheService.getOrLoad(key, typeRef, dataLoader);

        assertThat(reloadedValue).isEqualTo(value);
        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> {
            verify(l2CacheProvider, times(1)).put(key, value);
        });
    }

    @Test
    void shouldHandleComplexObjectsInL2Cache() {
        // Given
        String key = "complex-object-key";
        TestObject complexObject = new TestObject("Redis Test", 42, true);
        Supplier<TestObject> objectLoader = () -> complexObject;
        ParameterizedTypeReference<TestObject> typeRef = new ParameterizedTypeReference<TestObject>() {};

        // Configure mock behavior
        when(l2CacheProvider.get(eq(key), eq(typeRef))).thenReturn(null).thenReturn(complexObject);

        // --- First Call (Cache Miss) ---
        TestObject result1 = cacheService.getOrLoad(key, typeRef, objectLoader);

        assertThat(result1).isEqualTo(complexObject);
        assertThat(result1.getName()).isEqualTo("Redis Test");
        assertThat(result1.getValue()).isEqualTo(42);
        assertThat(result1.isActive()).isTrue();

        // Wait for async operations
        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> {
            verify(l2CacheProvider, times(1)).put(key, complexObject);
        });

        // --- Second Call (Cache Hit) ---
        TestObject result2 = cacheService.getOrLoad(key, typeRef, objectLoader);

        assertThat(result2).isEqualTo(complexObject);
        verify(l2CacheProvider, times(2)).get(eq(key), eq(typeRef));
    }

    // Test object for complex serialization testing
    public static class TestObject {
        private String name;
        private int value;
        private boolean active;

        public TestObject() {}

        public TestObject(String name, int value, boolean active) {
            this.name = name;
            this.value = value;
            this.active = active;
        }

        // Getters and setters
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public int getValue() { return value; }
        public void setValue(int value) { this.value = value; }
        public boolean isActive() { return active; }
        public void setActive(boolean active) { this.active = active; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof TestObject)) return false;
            TestObject that = (TestObject) o;
            return value == that.value && active == that.active &&
                   java.util.Objects.equals(name, that.name);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(name, value, active);
        }
    }
}
