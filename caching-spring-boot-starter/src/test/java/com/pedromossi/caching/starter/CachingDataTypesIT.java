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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

@SpringBootTest(
        classes = CachingAutoConfiguration.class,
        properties = {
                "caching.l1.spec=maximumSize=100,expireAfterWrite=15s",
                "caching.l2.ttl=PT2M",
                "caching.l2.invalidation-topic=custom:topic"
        }
)
public class CachingDataTypesIT extends IntegrationTest {

    @Autowired
    private CacheService cacheService;

    @MockBean
    @Qualifier("l1CacheProvider")
    private CacheProvider l1CacheProvider;

    @MockBean
    @Qualifier("l2CacheProvider")
    private CacheProvider l2CacheProvider;

    @BeforeEach
    void cleanupCaches() {
        reset(l1CacheProvider, l2CacheProvider);
    }

    @Test
    void shouldHandleStringValues() {
        // Given
        String key = "string-key";
        String value = "Simple String Value - " + UUID.randomUUID();
        Supplier<String> loader = () -> value;
        ParameterizedTypeReference<String> typeRef = new ParameterizedTypeReference<String>() {};

        // Configure mock behavior
        when(l1CacheProvider.get(eq(key), eq(typeRef))).thenReturn(null).thenReturn(value);
        when(l2CacheProvider.get(eq(key), eq(typeRef))).thenReturn(null);

        // When
        String result = cacheService.getOrLoad(key, typeRef, loader);

        // Then
        assertThat(result).isEqualTo(value);
        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> {
            verify(l1CacheProvider, times(1)).put(key, value);
            verify(l2CacheProvider, times(1)).put(key, value);
        });
    }

    @Test
    void shouldHandleIntegerValues() {
        // Given
        String key = "integer-key";
        Integer value = 42;
        Supplier<Integer> loader = () -> value;
        ParameterizedTypeReference<Integer> typeRef = new ParameterizedTypeReference<Integer>() {};

        // Configure mock behavior
        when(l1CacheProvider.get(eq(key), eq(typeRef))).thenReturn(null).thenReturn(value);
        when(l2CacheProvider.get(eq(key), eq(typeRef))).thenReturn(null);

        // When
        Integer result = cacheService.getOrLoad(key, typeRef, loader);

        // Then
        assertThat(result).isEqualTo(value);
        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> {
            verify(l1CacheProvider, times(1)).put(key, value);
            verify(l2CacheProvider, times(1)).put(key, value);
        });
    }

    @Test
    void shouldHandleListValues() {
        // Given
        String key = "list-key";
        List<String> value = List.of("item1", "item2", "item3", UUID.randomUUID().toString());
        Supplier<List<String>> loader = () -> value;
        ParameterizedTypeReference<List<String>> typeRef = new ParameterizedTypeReference<List<String>>() {};

        // Configure mock behavior
        when(l1CacheProvider.get(eq(key), eq(typeRef))).thenReturn(null).thenReturn(value);
        when(l2CacheProvider.get(eq(key), eq(typeRef))).thenReturn(null);

        // When
        List<String> result = cacheService.getOrLoad(key, typeRef, loader);

        // Then
        assertThat(result).isNotNull();
        assertThat(result).isEqualTo(value);
        assertThat(result).hasSize(4);
        assertThat(result).containsExactly("item1", "item2", "item3", value.get(3));

        // Verify async cache operations
        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> {
            verify(l1CacheProvider, times(1)).put(key, value);
            verify(l2CacheProvider, times(1)).put(key, value);
        });
    }

    @Test
    void shouldHandleMapValues() {
        // Given
        String key = "map-key";
        Map<String, Object> value = new HashMap<>();
        value.put("name", "John Doe");
        value.put("age", 30);
        value.put("active", true);
        value.put("id", UUID.randomUUID().toString());

        Supplier<Map<String, Object>> loader = () -> value;
        ParameterizedTypeReference<Map<String, Object>> typeRef = new ParameterizedTypeReference<>() {};

        // Configure mock behavior
        when(l1CacheProvider.get(eq(key), eq(typeRef))).thenReturn(null).thenReturn(value);
        when(l2CacheProvider.get(eq(key), eq(typeRef))).thenReturn(null);

        // When
        Map<String, Object> result = cacheService.getOrLoad(key, typeRef, loader);

        // Then
        assertThat(result).isNotNull();
        assertThat(result).isEqualTo(value);
        assertThat(result).containsKeys("name", "age", "active", "id");

        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> {
            verify(l1CacheProvider, times(1)).put(key, value);
            verify(l2CacheProvider, times(1)).put(key, value);
        });
    }

    @Test
    void shouldHandleCustomObjectValues() {
        // Given
        String key = "custom-object-key";
        CustomDataObject value = new CustomDataObject(
                "Custom Object " + UUID.randomUUID(),
                100,
                List.of("tag1", "tag2"),
                Map.of("meta1", "value1", "meta2", "value2")
        );
        Supplier<CustomDataObject> loader = () -> value;
        ParameterizedTypeReference<CustomDataObject> typeRef = new ParameterizedTypeReference<CustomDataObject>() {};

        // Configure mock behavior
        when(l1CacheProvider.get(eq(key), eq(typeRef))).thenReturn(null).thenReturn(value);
        when(l2CacheProvider.get(eq(key), eq(typeRef))).thenReturn(null);

        // When
        CustomDataObject result = cacheService.getOrLoad(key, typeRef, loader);

        // Then
        assertThat(result).isEqualTo(value);
        assertThat(result.getName()).isEqualTo(value.getName());
        assertThat(result.getScore()).isEqualTo(100);
        assertThat(result.getTags()).containsExactly("tag1", "tag2");
        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> {
            verify(l1CacheProvider, times(1)).put(key, value);
            verify(l2CacheProvider, times(1)).put(key, value);
        });
    }

    @Test
    void shouldHandleEmptyCollections() {
        // Given
        String key = "empty-list-key";
        List<String> emptyList = new ArrayList<>();
        Supplier<List<String>> loader = () -> emptyList;
        ParameterizedTypeReference<List<String>> typeRef = new ParameterizedTypeReference<List<String>>() {};

        // Configure mock behavior
        when(l1CacheProvider.get(eq(key), eq(typeRef))).thenReturn(null).thenReturn(emptyList);
        when(l2CacheProvider.get(eq(key), eq(typeRef))).thenReturn(null);

        // When
        List<String> result = cacheService.getOrLoad(key, typeRef, loader);

        // Then
        assertThat(result).isEqualTo(emptyList);
        assertThat(result).isEmpty();
        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> {
            verify(l1CacheProvider, times(1)).put(key, emptyList);
            verify(l2CacheProvider, times(1)).put(key, emptyList);
        });
    }

    @Test
    void shouldNotCacheNullValues() {
        // Given
        String key = "null-value-key";
        Supplier<String> loader = () -> null;
        ParameterizedTypeReference<String> typeRef = new ParameterizedTypeReference<String>() {};

        // Configure mock behavior
        when(l1CacheProvider.get(eq(key), eq(typeRef))).thenReturn(null);
        when(l2CacheProvider.get(eq(key), eq(typeRef))).thenReturn(null);

        // When
        String result = cacheService.getOrLoad(key, typeRef, loader);

        // Then
        assertThat(result).isNull();

        // Wait a bit to ensure no async operations are triggered
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Verify that null values are not cached
        verify(l1CacheProvider, never()).put(anyString(), any());
        verify(l2CacheProvider, never()).put(anyString(), any());
    }

    @Test
    void shouldHandleLoaderExceptions() {
        // Given
        String key = "exception-key";
        RuntimeException expectedException = new RuntimeException("Simulated loader failure");
        Supplier<String> failingLoader = () -> {
            throw expectedException;
        };
        ParameterizedTypeReference<String> typeRef = new ParameterizedTypeReference<String>() {};

        // Configure mock behavior
        when(l1CacheProvider.get(eq(key), eq(typeRef))).thenReturn(null);
        when(l2CacheProvider.get(eq(key), eq(typeRef))).thenReturn(null);

        // When & Then
        assertThatThrownBy(() -> cacheService.getOrLoad(key, typeRef, failingLoader))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Simulated loader failure");

        // Verify no caching occurred
        verify(l1CacheProvider, never()).put(anyString(), any());
        verify(l2CacheProvider, never()).put(anyString(), any());
    }

    // Custom data object for testing complex serialization
    public static class CustomDataObject {
        private String name;
        private int score;
        private List<String> tags;
        private Map<String, String> metadata;

        public CustomDataObject() {}

        public CustomDataObject(String name, int score, List<String> tags, Map<String, String> metadata) {
            this.name = name;
            this.score = score;
            this.tags = tags;
            this.metadata = metadata;
        }

        // Getters and setters
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public int getScore() { return score; }
        public void setScore(int score) { this.score = score; }
        public List<String> getTags() { return tags; }
        public void setTags(List<String> tags) { this.tags = tags; }
        public Map<String, String> getMetadata() { return metadata; }
        public void setMetadata(Map<String, String> metadata) { this.metadata = metadata; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof CustomDataObject)) return false;
            CustomDataObject that = (CustomDataObject) o;
            return score == that.score &&
                   java.util.Objects.equals(name, that.name) &&
                   java.util.Objects.equals(tags, that.tags) &&
                   java.util.Objects.equals(metadata, that.metadata);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(name, score, tags, metadata);
        }
    }
}
