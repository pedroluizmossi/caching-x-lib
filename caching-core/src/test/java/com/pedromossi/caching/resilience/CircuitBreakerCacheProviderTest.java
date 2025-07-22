package com.pedromossi.caching.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pedromossi.caching.CacheProvider;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.ParameterizedTypeReference;

@ExtendWith(MockitoExtension.class)
@DisplayName("CircuitBreakerCacheProvider")
class CircuitBreakerCacheProviderTest {

    private static final String KEY = "test:key";
    private static final String VALUE = "test-value";
    private static final Set<String> KEYS = Set.of("key1", "key2");
    private static final Map<String, Object> ITEMS = Map.of("key1", "val1");
    private static final ParameterizedTypeReference<String> TYPE_REF = new ParameterizedTypeReference<>() {};

    @Mock private CacheProvider delegate;

    private CircuitBreaker circuitBreaker;
    private CircuitBreakerCacheProvider circuitBreakerProvider;

    @BeforeEach
    void setUp() {
        CircuitBreakerConfig config =
                CircuitBreakerConfig.custom()
                        .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                        .slidingWindowSize(3) // Trip after 3 failures
                        .failureRateThreshold(50.0f)
                        .waitDurationInOpenState(Duration.ofMillis(200))
                        .build();

        this.circuitBreaker = CircuitBreaker.of("testCache", config);
        this.circuitBreakerProvider = new CircuitBreakerCacheProvider(delegate, circuitBreaker);
    }

    @Nested
    @DisplayName("when circuit is CLOSED")
    class WhenClosed {

        @Test
        @DisplayName("get() should succeed")
        void get_shouldSucceed() {
            when(delegate.get(KEY, TYPE_REF)).thenReturn(VALUE);
            assertThat(circuitBreakerProvider.get(KEY, TYPE_REF)).isEqualTo(VALUE);
            verify(delegate).get(KEY, TYPE_REF);
        }

        @Test
        @DisplayName("put() should succeed")
        void put_shouldSucceed() {
            circuitBreakerProvider.put(KEY, VALUE);
            verify(delegate).put(KEY, VALUE);
        }

        @Test
        @DisplayName("evict() should succeed")
        void evict_shouldSucceed() {
            circuitBreakerProvider.evict(KEY);
            verify(delegate).evict(KEY);
        }

        @Test
        @DisplayName("getAll() should succeed")
        void getAll_shouldSucceed() {
            when(delegate.getAll(KEYS, TYPE_REF)).thenReturn(Map.of("key1", VALUE));
            Map<String, String> result = circuitBreakerProvider.getAll(KEYS, TYPE_REF);
            assertThat(result).containsEntry("key1", VALUE);
            verify(delegate).getAll(KEYS, TYPE_REF);
        }

        @Test
        @DisplayName("putAll() should succeed")
        void putAll_shouldSucceed() {
            circuitBreakerProvider.putAll(ITEMS);
            verify(delegate).putAll(ITEMS);
        }

        @Test
        @DisplayName("evictAll() should succeed")
        void evictAll_shouldSucceed() {
            circuitBreakerProvider.evictAll(KEYS);
            verify(delegate).evictAll(KEYS);
        }
    }

    @Nested
    @DisplayName("when transitioning to OPEN")
    class WhenOpening {

        @Test
        @DisplayName("getAll() should open circuit on consecutive failures")
        void getAll_shouldOpenCircuitOnFailure() {
            assertCircuitOpensAndBlocksCalls(
                    () -> doThrow(new RuntimeException("Delegate Failed")).when(delegate).getAll(any(), any()),
                    () -> circuitBreakerProvider.getAll(KEYS, TYPE_REF));
            verify(delegate, times(3)).getAll(any(), any());
        }

        @Test
        @DisplayName("putAll() should open circuit on consecutive failures")
        void putAll_shouldOpenCircuitOnFailure() {
            assertCircuitOpensAndBlocksCalls(
                    () -> doThrow(new RuntimeException("Delegate Failed")).when(delegate).putAll(any()),
                    () -> circuitBreakerProvider.putAll(ITEMS));
            verify(delegate, times(3)).putAll(any());
        }

        @Test
        @DisplayName("evictAll() should open circuit on consecutive failures")
        void evictAll_shouldOpenCircuitOnFailure() {
            assertCircuitOpensAndBlocksCalls(
                    () -> doThrow(new RuntimeException("Delegate Failed")).when(delegate).evictAll(any()),
                    () -> circuitBreakerProvider.evictAll(KEYS));
            verify(delegate, times(3)).evictAll(any());
        }
    }

    // This is a shared helper method to avoid repeating the test logic for opening the circuit.
    private void assertCircuitOpensAndBlocksCalls(Runnable mockSetup, Runnable providerCall) {
        // Given: a delegate that will always fail
        mockSetup.run();

        // When: the operation is called enough times to trip the circuit (3 times based on config)
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(providerCall::run).isInstanceOf(RuntimeException.class);
        }

        // Then: the circuit breaker should now be in the OPEN state
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // And: any subsequent call should be blocked immediately without calling the delegate
        assertThatThrownBy(providerCall::run).isInstanceOf(CallNotPermittedException.class);
    }
}