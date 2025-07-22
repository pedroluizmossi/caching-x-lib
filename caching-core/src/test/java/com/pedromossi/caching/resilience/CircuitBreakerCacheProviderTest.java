package com.pedromossi.caching.resilience;

import com.pedromossi.caching.CacheProvider;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.ParameterizedTypeReference;

import java.time.Duration;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CircuitBreakerCacheProviderTest {

    @Mock private CacheProvider delegate;

    private CircuitBreaker circuitBreaker;
    private CircuitBreakerCacheProvider circuitBreakerProvider;

    @BeforeEach
    void setUp() {
        CircuitBreakerConfig config =
                CircuitBreakerConfig.custom()
                        .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                        .slidingWindowSize(3)
                        .failureRateThreshold(50.0f)
                        .slowCallDurationThreshold(Duration.ofMillis(100))
                        .slowCallRateThreshold(50.0f)
                        .waitDurationInOpenState(Duration.ofMillis(200))
                        .permittedNumberOfCallsInHalfOpenState(1)
                        .build();

        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(config);
        this.circuitBreaker = registry.circuitBreaker("testCache");
        this.circuitBreakerProvider = new CircuitBreakerCacheProvider(delegate, circuitBreaker);
    }

    @Test
    @DisplayName("Should allow calls and return value when circuit is CLOSED")
    void shouldAllowCallsWhenCircuitIsClosed() {
        // Given
        String key = "user:1";
        String expectedValue = "John Doe";
        var typeRef = new ParameterizedTypeReference<String>() {};
        when(delegate.get(key, typeRef)).thenReturn(expectedValue);

        // When
        String actualValue = circuitBreakerProvider.get(key, typeRef);

        // Then
        assertThat(actualValue).isEqualTo(expectedValue);
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        verify(delegate, times(1)).get(key, typeRef);
    }

    @Test
    @DisplayName("Should OPEN circuit after consecutive failures and block subsequent calls")
    void shouldOpenCircuitAfterFailuresAndBlockCalls() {

        String key = "user:fail";
        var typeRef = new ParameterizedTypeReference<String>() {};
        doThrow(new RuntimeException("Simulated Redis Failure"))
                .when(delegate)
                .get(key, typeRef);

        IntStream.range(0, 3)
                .forEach(
                        i ->
                                assertThatThrownBy(() -> circuitBreakerProvider.get(key, typeRef))
                                        .isInstanceOf(RuntimeException.class));

        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        assertThatThrownBy(() -> circuitBreakerProvider.get(key, typeRef))
                .isInstanceOf(CallNotPermittedException.class);

        verify(delegate, times(3)).get(key, typeRef);
    }

    @Test
    @DisplayName("Should transition to HALF_OPEN and CLOSE on success")
    void shouldTransitionToHalfOpenAndCloseOnSuccess() {
        circuitBreaker.transitionToOpenState();

        String key = "user:recover";
        String expectedValue = "Recovered Value";
        var typeRef = new ParameterizedTypeReference<String>() {};
        when(delegate.get(key, typeRef)).thenReturn(expectedValue);

        circuitBreaker.transitionToHalfOpenState();

        String value1 = circuitBreakerProvider.get(key, typeRef);
        String value2 = circuitBreakerProvider.get(key, typeRef);

        assertThat(value1).isEqualTo(expectedValue);
        assertThat(value2).isEqualTo(expectedValue);
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        verify(delegate, times(2)).get(key, typeRef);
    }

    @Test
    @DisplayName("Should OPEN circuit due to slow calls")
    void shouldOpenCircuitOnSlowCalls() {
        String key = "user:slow";
        String expectedValue = "Slow Value";
        var typeRef = new ParameterizedTypeReference<String>() {};
        doAnswer(
                invocation -> {
                    Thread.sleep(150);
                    return expectedValue;
                })
                .when(delegate)
                .get(key, typeRef);

        IntStream.range(0, 3)
                .forEach(
                        i -> {
                            String result = circuitBreakerProvider.get(key, typeRef);
                            assertThat(result).isEqualTo(expectedValue);
                        });

        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        assertThatThrownBy(() -> circuitBreakerProvider.get(key, typeRef))
                .isInstanceOf(CallNotPermittedException.class);

        verify(delegate, times(3)).get(key, typeRef);
    }

    @Test
    @DisplayName("Should OPEN circuit for put() after consecutive failures")
    void shouldOpenCircuitForPutAfterFailures() {
        String key = "product:123";
        Object value = "Notebook";
        doThrow(new RuntimeException("Simulated Redis PUT Failure"))
                .when(delegate)
                .put(key, value);

        IntStream.range(0, 3)
                .forEach(
                        i ->
                                assertThatThrownBy(() -> circuitBreakerProvider.put(key, value))
                                        .isInstanceOf(RuntimeException.class));

        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        assertThatThrownBy(() -> circuitBreakerProvider.put(key, value))
                .isInstanceOf(CallNotPermittedException.class);

        verify(delegate, times(3)).put(key, value);
    }

    @Test
    @DisplayName("Should OPEN circuit for evict() after consecutive failures")
    void shouldOpenCircuitForEvictAfterFailures() {
        String key = "user:456";
        doThrow(new RuntimeException("Simulated Redis EVICT Failure"))
                .when(delegate)
                .evict(key);

        IntStream.range(0, 3)
                .forEach(
                        i ->
                                assertThatThrownBy(() -> circuitBreakerProvider.evict(key))
                                        .isInstanceOf(RuntimeException.class));

        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        assertThatThrownBy(() -> circuitBreakerProvider.evict(key))
                .isInstanceOf(CallNotPermittedException.class);

        verify(delegate, times(3)).evict(key);
    }
}