package com.pedromossi.caching.annotation;

import com.pedromossi.caching.aspect.CacheXAspect;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declarative caching annotation for method-level cache operations using Spring AOP.
 *
 * <p>This annotation provides a convenient way to add caching behavior to methods without
 * requiring explicit cache service calls. It supports both caching (GET) and cache
 * invalidation (EVICT) operations through Spring's Aspect-Oriented Programming (AOP).</p>
 *
 * <p><strong>Key Features:</strong></p>
 * <ul>
 * <li><strong>Spring Expression Language (SpEL):</strong> Dynamic cache key generation</li>
 * <li><strong>Type Safety:</strong> Automatic type preservation from method return types</li>
 * <li><strong>Multi-Level Caching:</strong> Integrates with L1 and L2 cache layers</li>
 * <li><strong>Operation Types:</strong> Supports both caching and invalidation operations</li>
 * </ul>
 *
 * <p><strong>Usage Examples:</strong></p>
 *
 * <pre>{@code
 * // Basic caching with simple key
 * @CacheX(key = "'user:' + #userId")
 * public User getUser(Long userId) {
 *     return userRepository.findById(userId).orElse(null);
 * }
 *
 * // Cache invalidation after method execution
 * @CacheX(key = "'user:' + #user.id", operation = CacheX.Operation.EVICT)
 * public void updateUser(User user) {
 *     userRepository.save(user);
 * }
 *
 * // Complex key with multiple parameters
 * @CacheX(key = "'search:' + #query.hashCode() + ':page:' + #page")
 * public List<Result> search(SearchQuery query, int page) {
 *     return searchService.performSearch(query, page);
 * }
 * }</pre>
 *
 * <p><strong>SpEL Expression Support:</strong></p>
 * <p>The {@code key} attribute supports Spring Expression Language for dynamic key generation:</p>
 * <ul>
 * <li><strong>Method Parameters:</strong> {@code #paramName}</li>
 * <li><strong>Object Properties:</strong> {@code #object.property}</li>
 * <li><strong>Method Calls:</strong> {@code #param.method()}</li>
 * <li><strong>Conditional Logic:</strong> {@code #condition ? 'value1' : 'value2'}</li>
 * <li><strong>String Literals:</strong> {@code 'static:value'}</li>
 * </ul>
 *
 * <p><strong>Cache Flow for GET Operations:</strong></p>
 * <ol>
 * <li>Intercept method call via AOP</li>
 * <li>Evaluate SpEL expression to generate cache key</li>
 * <li>Check L1 cache (local) for cached value</li>
 * <li>On L1 miss, check L2 cache (distributed)</li>
 * <li>On complete miss, execute original method</li>
 * <li>Store result in cache layers for future access</li>
 * <li>Return cached or computed value</li>
 * </ol>
 *
 * <p><strong>Cache Flow for EVICT Operations:</strong></p>
 * <ol>
 * <li>Execute the original method first</li>
 * <li>On successful completion, evaluate cache key</li>
 * <li>Invalidate key from all cache layers (L1 and L2)</li>
 * <li>Publish invalidation event to other application instances</li>
 * <li>Return method result</li>
 * </ol>
 *
 * <p><strong>AOP Requirements:</strong></p>
 * <ul>
 * <li>Methods must be {@code public} (AOP limitation)</li>
 * <li>Classes must be Spring-managed beans ({@code @Service}, {@code @Component}, etc.)</li>
 * <li>Avoid self-invocation (call annotated methods from other beans)</li>
 * <li>Spring AOP must be enabled (automatic with Spring Boot)</li>
 * </ul>
 *
 * <p><strong>Type Safety:</strong></p>
 * <p>The annotation automatically preserves the method's return type for cache operations,
 * ensuring type-safe deserialization of cached objects. This works for both simple types
 * and complex generic types like {@code List<User>} or {@code Map<String, Object>}.</p>
 *
 * <p><strong>Null Value Handling:</strong></p>
 * <p>Methods that return {@code null} values are handled correctly. The cache will store
 * a sentinel value to distinguish between "cache miss" and "cached null value", preventing
 * repeated execution for legitimately null results.</p>
 *
 * <p><strong>Error Handling:</strong></p>
 * <p>If cache operations fail, the annotation ensures that the original method is still
 * executed, maintaining application functionality even when cache layers are unavailable.</p>
 *
 * @since 1.1.0
 * @see CacheXAspect
 * @see org.springframework.expression.Expression
 * @see org.springframework.core.ParameterizedTypeReference
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface CacheX {

    /**
     * Spring Expression Language (SpEL) expression for generating the cache key.
     *
     * <p>This expression is evaluated at runtime to produce a unique cache key for
     * the method invocation. The expression has access to:</p>
     * <ul>
     * <li><strong>Method parameters:</strong> Referenced by name using {@code #paramName}</li>
     * <li><strong>Method context:</strong> Target object, method info, and argument array</li>
     * <li><strong>SpEL functions:</strong> All standard SpEL operators and functions</li>
     * </ul>
     *
     * <p><strong>Examples:</strong></p>
     * <pre>{@code
     * // Simple parameter reference
     * key = "'user:' + #userId"
     *
     * // Object property access
     * key = "'order:' + #order.id + ':status'"
     *
     * // Method invocation on parameter
     * key = "'search:' + #query.toLowerCase()"
     *
     * // Conditional logic
     * key = "'data:' + (#useCache ? #id : 'nocache')"
     *
     * // Multiple parameters
     * key = "'report:' + #year + '-' + #month + '-' + #type"
     *
     * // Static key (no parameters)
     * key = "'global:config'"
     * }</pre>
     *
     * <p><strong>Best Practices:</strong></p>
     * <ul>
     * <li>Use hierarchical key patterns: {@code 'entity:type:id'}</li>
     * <li>Include null checks for nullable parameters</li>
     * <li>Keep keys human-readable for debugging</li>
     * <li>Avoid keys that change frequently</li>
     * <li>Use consistent naming conventions</li>
     * </ul>
     *
     * @return the SpEL expression for cache key generation
     */
    String key();

    /**
     * The cache operation to perform when the method is invoked.
     *
     * <p>Determines whether the annotation should cache the method result (GET)
     * or invalidate cached entries (EVICT) after method execution.</p>
     *
     * @return the cache operation type
     * @see Operation
     */
    Operation operation() default Operation.GET;

    /**
     * Enumeration of supported cache operations.
     *
     * <p>Defines the behavior of the {@code @CacheX} annotation when applied to methods.
     * Each operation type has different execution semantics and use cases.</p>
     */
    enum Operation {
        /**
         * Cache the method's return value for future access.
         *
         * <p><strong>Execution Flow:</strong></p>
         * <ol>
         * <li>Generate cache key from SpEL expression</li>
         * <li>Check cache layers for existing value</li>
         * <li>If found, return cached value (skip method execution)</li>
         * <li>If not found, execute method and cache the result</li>
         * <li>Return the computed value</li>
         * </ol>
         *
         * <p><strong>Use Cases:</strong></p>
         * <ul>
         * <li>Expensive database queries</li>
         * <li>Complex computations</li>
         * <li>External API calls</li>
         * <li>Report generation</li>
         * </ul>
         *
         * <p><strong>Example:</strong></p>
         * <pre>{@code
         * @CacheX(key = "'product:' + #productId", operation = Operation.GET)
         * public Product getProduct(Long productId) {
         *     return productRepository.findById(productId).orElse(null);
         * }
         * }</pre>
         */
        GET,

        /**
         * Invalidate cached entries after successful method execution.
         *
         * <p><strong>Execution Flow:</strong></p>
         * <ol>
         * <li>Execute the original method first</li>
         * <li>If method completes successfully, generate cache key</li>
         * <li>Remove key from all cache layers (L1 and L2)</li>
         * <li>Publish invalidation event to other instances</li>
         * <li>Return the method's result</li>
         * </ol>
         *
         * <p><strong>Use Cases:</strong></p>
         * <ul>
         * <li>Data update operations</li>
         * <li>Cache maintenance tasks</li>
         * <li>Manual cache refresh triggers</li>
         * <li>Batch invalidation operations</li>
         * </ul>
         *
         * <p><strong>Example:</strong></p>
         * <pre>{@code
         * @CacheX(key = "'product:' + #product.id", operation = Operation.EVICT)
         * public void updateProduct(Product product) {
         *     productRepository.save(product);
         * }
         * }</pre>
         *
         * <p><strong>Note:</strong> Invalidation only occurs if the method completes
         * without throwing an exception. This ensures cache consistency by only
         * invalidating when data changes are successfully persisted.</p>
         */
        EVICT
    }
}