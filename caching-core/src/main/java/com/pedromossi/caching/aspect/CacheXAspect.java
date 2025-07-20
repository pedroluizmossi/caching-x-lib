package com.pedromossi.caching.aspect;

import com.pedromossi.caching.CacheService;
import com.pedromossi.caching.annotation.CacheX;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

@Aspect
@Component
public class CacheXAspect {

    /**
     * Logger instance for debugging and monitoring aspect operations.
     */
    private static final Logger log = LoggerFactory.getLogger(CacheXAspect.class);

    /**
     * The cache service that performs actual cache operations.
     */
    private final CacheService cacheService;

    /**
     * SpEL expression parser for evaluating cache key expressions.
     */
    private final ExpressionParser parser = new SpelExpressionParser();

    /**
     * Parameter name discoverer for SpEL context creation.
     */
    private final ParameterNameDiscoverer parameterNameDiscoverer = new DefaultParameterNameDiscoverer();

    /**
     * Creates a new CacheXAspect with the specified cache service.
     *
     * @param cacheService the cache service to use for cache operations (must not be null)
     * @throws NullPointerException if cacheService is null
     */
    public CacheXAspect(CacheService cacheService) {
        this.cacheService = cacheService;
    }

    /**
     * Around advice that intercepts method calls to {@code @CacheX} annotated methods.
     *
     * <p>This method is automatically invoked by Spring AOP when a method annotated
     * with {@code @CacheX} is called. It performs the following operations:</p>
     * <ol>
     * <li>Evaluates the SpEL expression to generate a cache key</li>
     * <li>Determines the operation type (GET or EVICT)</li>
     * <li>Delegates to the appropriate operation handler</li>
     * <li>Returns the result to the caller</li>
     * </ol>
     *
     * <p><strong>Pointcut Expression:</strong> {@code @annotation(cacheX)}</p>
     * <p>This advice matches any method annotated with {@code @CacheX}, regardless
     * of the class or method name.</p>
     *
     * @param joinPoint the AOP join point representing the intercepted method call
     * @param cacheX the {@code @CacheX} annotation instance with configuration
     * @return the result of the cache operation or original method execution
     * @throws Throwable if the underlying method or cache operation throws an exception
     * @see CacheX
     * @see CacheX.Operation
     */
    @Around("@annotation(cacheX)")
    public Object handleCacheX(ProceedingJoinPoint joinPoint, CacheX cacheX) throws Throwable {
        String key = parseKey(cacheX.key(), joinPoint);

        log.debug("@CacheX aspect intercepted method: {} with operation: {} and key: {}",
                  joinPoint.getSignature().getName(), cacheX.operation(), key);

        if (cacheX.operation() == CacheX.Operation.GET) {
            log.debug("@CacheX GET operation for key: {}", key);
            Object result = handleGet(joinPoint, key);
            log.debug("@CacheX GET operation completed for key: {}, result type: {}",
                      key, result != null ? result.getClass().getSimpleName() : "null");
            return result;
        }

        if (cacheX.operation() == CacheX.Operation.EVICT) {
            log.debug("@CacheX EVICT operation for key: {}", key);
            Object result = handleEvict(joinPoint, key);
            log.debug("@CacheX EVICT operation completed for key: {}", key);
            return result;
        }

        // Se novas operações forem adicionadas no futuro
        log.debug("@CacheX unknown operation: {}, proceeding with method execution", cacheX.operation());
        return joinPoint.proceed();
    }

    /**
     * Handles GET operations by implementing the cache-aside pattern.
     *
     * <p>This method implements the following cache-aside flow:</p>
     * <ol>
     * <li>Extract method return type for type-safe cache operations</li>
     * <li>Delegate to {@code CacheService.getOrLoad()} with the original method as loader</li>
     * <li>The cache service handles L1/L2 lookup and method execution if needed</li>
     * <li>Return the cached or computed result</li>
     * </ol>
     *
     * <p><strong>Type Safety:</strong> This method automatically preserves the generic
     * return type of the intercepted method, ensuring that complex types like
     * {@code List<User>} are correctly handled by the cache service.</p>
     *
     * <p><strong>Error Handling:</strong> If the original method throws an exception,
     * it's wrapped in a {@code RuntimeException} to comply with the {@code Supplier}
     * interface expected by the cache service.</p>
     *
     * @param joinPoint the AOP join point for the intercepted method
     * @param key the evaluated cache key
     * @return the cached value or the result of executing the original method
     * @throws RuntimeException if the original method throws an exception
     */
    private Object handleGet(ProceedingJoinPoint joinPoint, String key) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        ParameterizedTypeReference<Object> typeRef = ParameterizedTypeReference.forType(method.getGenericReturnType());

        return cacheService.getOrLoad(key, typeRef, () -> {
            try {
                return joinPoint.proceed();
            } catch (Throwable e) {
                // Lançar uma exceção não verificada para evitar 'throws Throwable'
                throw new RuntimeException("Error executing loader for cache key: " + key, e);
            }
        });
    }

    /**
     * Handles EVICT operations by executing the method first, then invalidating the cache.
     *
     * <p>This method implements cache invalidation with the following guarantees:</p>
     * <ol>
     * <li>Execute the original method first</li>
     * <li>Only invalidate cache if method completes successfully</li>
     * <li>Cache invalidation is performed across all layers (L1 and L2)</li>
     * <li>Invalidation events are propagated to other application instances</li>
     * <li>Return the original method result</li>
     * </ol>
     *
     * <p><strong>Consistency Guarantee:</strong> Cache invalidation only occurs after
     * successful method execution. If the method throws an exception, the cache remains
     * unchanged, ensuring data consistency.</p>
     *
     * <p><strong>Distributed Invalidation:</strong> The cache service handles propagation
     * of invalidation events to other application instances via messaging systems like
     * Redis pub/sub.</p>
     *
     * @param joinPoint the AOP join point for the intercepted method
     * @param key the evaluated cache key to invalidate
     * @return the result of the original method execution
     * @throws Throwable if the original method throws an exception
     */
    private Object handleEvict(ProceedingJoinPoint joinPoint, String key) throws Throwable {
        // A invalidação deve ocorrer APÓS o método ser executado com sucesso
        Object result = joinPoint.proceed();
        cacheService.invalidate(key);
        return result;
    }

    /**
     * Parses a SpEL expression in the context of the intercepted method to generate a cache key.
     *
     * <p>This method creates a Spring Expression Language evaluation context that includes:</p>
     * <ul>
     * <li><strong>Method Parameters:</strong> Available by parameter name (e.g., {@code #userId})</li>
     * <li><strong>Target Object:</strong> The object instance on which the method is called</li>
     * <li><strong>Method Metadata:</strong> Method signature and reflection information</li>
     * <li><strong>Arguments Array:</strong> Method arguments for advanced expressions</li>
     * </ul>
     *
     * <p><strong>Expression Examples:</strong></p>
     * <pre>{@code
     * // Simple parameter reference
     * "'user:' + #userId" → "user:123"
     *
     * // Object property access
     * "'order:' + #order.id" → "order:456"
     *
     * // Method call on parameter
     * "'search:' + #query.toLowerCase()" → "search:java caching"
     *
     * // Conditional logic
     * "'data:' + (#useCache ? #id : 'nocache')" → "data:789" or "data:nocache"
     * }</pre>
     *
     * <p><strong>Parameter Name Discovery:</strong> Uses Spring's
     * {@link DefaultParameterNameDiscoverer} to resolve parameter names from method signatures.
     * This works with compiled code when parameter names are preserved (Java 8+ with
     * {@code -parameters} compiler flag) or through debug information.</p>
     *
     * <p><strong>Error Handling:</strong> If expression evaluation fails, the exception
     * is propagated to the caller. Common causes include:</p>
     * <ul>
     * <li>Syntax errors in the SpEL expression</li>
     * <li>Reference to non-existent parameters</li>
     * <li>Null pointer access in object navigation</li>
     * <li>Type conversion failures</li>
     * </ul>
     *
     * @param keyExpression the SpEL expression to evaluate
     * @param joinPoint the method execution context
     * @return the evaluated cache key as a string
     * @throws org.springframework.expression.ExpressionException if expression evaluation fails
     * @see org.springframework.expression.spel.standard.SpelExpressionParser
     * @see org.springframework.context.expression.MethodBasedEvaluationContext
     */
    private String parseKey(String keyExpression, ProceedingJoinPoint joinPoint) {
        Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
        StandardEvaluationContext context = new MethodBasedEvaluationContext(joinPoint.getTarget(), method, joinPoint.getArgs(), this.parameterNameDiscoverer);
        return parser.parseExpression(keyExpression).getValue(context, String.class);
    }
}
