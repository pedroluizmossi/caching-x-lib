package com.pedromossi.caching.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface CacheX {

    String key();

    Operation operation() default Operation.GET;

    enum Operation {
        GET,
        EVICT
    }
}