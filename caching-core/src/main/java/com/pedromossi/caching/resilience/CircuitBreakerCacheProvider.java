package com.pedromossi.caching.resilience;

import com.pedromossi.caching.CacheProvider;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;

public class CircuitBreakerCacheProvider implements CacheProvider {
    private static final Logger log = LoggerFactory.getLogger(CircuitBreakerCacheProvider.class);

    private final CacheProvider delegate;
    private final CircuitBreaker circuitBreaker;

    public CircuitBreakerCacheProvider(CacheProvider delegate, CircuitBreaker circuitBreaker) {
        this.delegate = delegate;
        this.circuitBreaker = circuitBreaker;
        this.circuitBreaker.getEventPublisher()
                .onStateTransition(event -> log.warn("L2 Cache Circuit Breaker state changed: {}", event));
    }

    @Override
    public <T> T get(String key, ParameterizedTypeReference<T> typeRef) {
        Supplier<T> decoratedSupplier = () -> delegate.get(key, typeRef);
        return circuitBreaker.executeSupplier(decoratedSupplier);
    }

    @Override
    public void put(String key, Object value) {
        Runnable decoratedRunnable = () -> delegate.put(key, value);
        circuitBreaker.executeRunnable(decoratedRunnable);
    }

    @Override
    public void evict(String key) {
        Runnable decoratedRunnable = () -> delegate.evict(key);
        circuitBreaker.executeRunnable(decoratedRunnable);
    }
}