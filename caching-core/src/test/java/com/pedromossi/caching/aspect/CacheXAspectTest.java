package com.pedromossi.caching.aspect;

import com.pedromossi.caching.CacheService;
import com.pedromossi.caching.annotation.CacheX;
import com.pedromossi.caching.annotation.CacheX.Operation;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.ParameterizedTypeReference;

import java.lang.reflect.Method;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CacheXAspectTest {

    @Mock private CacheService cacheService;
    @Mock private ProceedingJoinPoint joinPoint;
    @Mock private MethodSignature methodSignature;

    private CacheXAspect cacheXAspect;

    // A dummy class with methods to be used in tests
    private static class TestService {
        public String getUser(Long userId) {
            return "User " + userId;
        }

        public void updateUser(Long userId, String name) {
            // No-op
        }
    }

    @BeforeEach
    void setUp() {
        cacheXAspect = new CacheXAspect(cacheService);
        // Common mock setup that applies to all tests
        when(joinPoint.getSignature()).thenReturn(methodSignature);
    }

    @Test
    @DisplayName("should call cacheService.getOrLoad for GET operation on cache miss")
    void handleCacheX_shouldExecuteLoaderOnMiss() throws Throwable {
        // Given
        // FIX: Use argument index #a0 instead of name #userId for robustness
        String keyExpression = "'user:' + #a0";
        String expectedKey = "user:123";
        String freshResult = "Fresh User from DB";
        Method method = TestService.class.getMethod("getUser", Long.class);
        CacheX cacheX = mockCacheX(keyExpression, Operation.GET);

        when(methodSignature.getMethod()).thenReturn(method);
        when(joinPoint.getArgs()).thenReturn(new Object[] {123L});
        when(joinPoint.getTarget()).thenReturn(new TestService());
        when(joinPoint.proceed()).thenReturn(freshResult);

        // Simulate a cache miss by making getOrLoad execute its loader lambda
        when(cacheService.getOrLoad(eq(expectedKey), any(ParameterizedTypeReference.class), any()))
                .thenAnswer(
                        invocation -> {
                            Supplier<?> loader = invocation.getArgument(2);
                            return loader.get(); // Execute the loader
                        });

        // When
        Object result = cacheXAspect.handleCacheX(joinPoint, cacheX);

        // Then
        assertThat(result).isEqualTo(freshResult);
        verify(cacheService).getOrLoad(eq(expectedKey), any(ParameterizedTypeReference.class), any());
        verify(joinPoint).proceed(); // Verify the original method was executed
    }

    @Test
    @DisplayName("should return from cache and NOT proceed for GET operation on cache hit")
    void handleCacheX_shouldReturnFromCacheOnHit() throws Throwable {
        // Given
        String keyExpression = "'user:' + #a0";
        String expectedKey = "user:123";
        String cachedResult = "Cached User";
        Method method = TestService.class.getMethod("getUser", Long.class);
        CacheX cacheX = mockCacheX(keyExpression, Operation.GET);

        when(methodSignature.getMethod()).thenReturn(method);
        when(joinPoint.getArgs()).thenReturn(new Object[] {123L});
        when(joinPoint.getTarget()).thenReturn(new TestService());

        // Simulate a cache hit
        when(cacheService.getOrLoad(eq(expectedKey), any(ParameterizedTypeReference.class), any()))
                .thenReturn(cachedResult);

        // When
        Object result = cacheXAspect.handleCacheX(joinPoint, cacheX);

        // Then
        assertThat(result).isEqualTo(cachedResult);
        // Verify that the original method was NOT called because of the cache hit
        verify(joinPoint, never()).proceed();
    }

    @Test
    @DisplayName("should call invalidate for EVICT operation after method success")
    void handleCacheX_shouldDelegateToInvalidateOnEvict() throws Throwable {
        // Given
        // FIX: Use argument index #a0 instead of name #userId
        String keyExpression = "'user:' + #a0";
        String expectedKey = "user:456";
        Method method = TestService.class.getMethod("updateUser", Long.class, String.class);
        CacheX cacheX = mockCacheX(keyExpression, Operation.EVICT);

        when(methodSignature.getMethod()).thenReturn(method);
        when(joinPoint.getArgs()).thenReturn(new Object[] {456L, "New Name"});
        when(joinPoint.getTarget()).thenReturn(new TestService());
        when(joinPoint.proceed()).thenReturn(null); // Simulate void method returning

        // When
        cacheXAspect.handleCacheX(joinPoint, cacheX);

        // Then
        // Verify that proceed() was called BEFORE invalidate()
        var inOrder = org.mockito.Mockito.inOrder(joinPoint, cacheService);
        inOrder.verify(joinPoint).proceed();
        inOrder.verify(cacheService).invalidate(expectedKey);
    }

    @Test
    @DisplayName("should NOT call invalidate for EVICT if method throws exception")
    void handleCacheX_shouldNotInvalidateOnEvictFailure() throws Throwable {
        // Given
        String keyExpression = "'user:' + #a0";
        Method method = TestService.class.getMethod("updateUser", Long.class, String.class);
        CacheX cacheX = mockCacheX(keyExpression, Operation.EVICT);
        var exception = new IllegalStateException("Database update failed");

        when(methodSignature.getMethod()).thenReturn(method);
        when(joinPoint.getArgs()).thenReturn(new Object[] {456L, "New Name"});
        when(joinPoint.getTarget()).thenReturn(new TestService());
        doThrow(exception).when(joinPoint).proceed(); // Simulate method failure

        // When / Then
        assertThatThrownBy(() -> cacheXAspect.handleCacheX(joinPoint, cacheX)).isSameAs(exception);

        // Verify that invalidate was never called
        verify(cacheService, never()).invalidate(anyString());
    }

    /** Helper method to create a mock {@link CacheX} annotation instance. */
    private CacheX mockCacheX(String key, Operation operation) {
        CacheX cacheX = mock(CacheX.class);
        when(cacheX.key()).thenReturn(key);
        when(cacheX.operation()).thenReturn(operation);
        return cacheX;
    }
}