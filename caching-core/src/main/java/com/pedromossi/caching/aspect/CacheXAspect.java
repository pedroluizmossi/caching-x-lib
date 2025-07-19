package com.pedromossi.caching.aspect;

import com.pedromossi.caching.CacheService;
import com.pedromossi.caching.annotation.CacheX;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
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

    private final CacheService cacheService;
    private final ExpressionParser parser = new SpelExpressionParser();
    private final ParameterNameDiscoverer parameterNameDiscoverer = new DefaultParameterNameDiscoverer();

    public CacheXAspect(CacheService cacheService) {
        this.cacheService = cacheService;
    }

    @Around("@annotation(cacheX)")
    public Object handleCacheX(ProceedingJoinPoint joinPoint, CacheX cacheX) throws Throwable {
        String key = parseKey(cacheX.key(), joinPoint);

        if (cacheX.operation() == CacheX.Operation.GET) {
            return handleGet(joinPoint, key);
        }

        if (cacheX.operation() == CacheX.Operation.EVICT) {
            return handleEvict(joinPoint, key);
        }

        return joinPoint.proceed();
    }

    private Object handleGet(ProceedingJoinPoint joinPoint, String key) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        ParameterizedTypeReference<Object> typeRef = ParameterizedTypeReference.forType(method.getGenericReturnType());

        return cacheService.getOrLoad(key, typeRef, () -> {
            try {
                return joinPoint.proceed();
            } catch (Throwable e) {
                throw new RuntimeException("Error executing loader for cache key: " + key, e);
            }
        });
    }

    private Object handleEvict(ProceedingJoinPoint joinPoint, String key) throws Throwable {
        Object result = joinPoint.proceed();
        cacheService.invalidate(key);
        return result;
    }

    private String parseKey(String keyExpression, ProceedingJoinPoint joinPoint) {
        Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
        StandardEvaluationContext context = new MethodBasedEvaluationContext(joinPoint.getTarget(), method, joinPoint.getArgs(), this.parameterNameDiscoverer);
        return parser.parseExpression(keyExpression).getValue(context, String.class);
    }
}
