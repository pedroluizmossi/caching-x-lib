package com.pedromossi.caching.impl;

/**
 * Exception thrown when a cache loading operation fails.
 *
 * <p>This exception is thrown when the MultiLevelCacheService encounters
 * an error while loading data from the data source or when concurrent
 * loading operations are interrupted.</p>
 *
 * @since 0.0.4
 */
public class CacheLoadingException extends RuntimeException {

    /**
     * Creates a new CacheLoadingException with the specified detail message.
     *
     * @param message the detail message explaining the cause of the exception
     */
    public CacheLoadingException(String message) {
        super(message);
    }

    /**
     * Creates a new CacheLoadingException with the specified detail message and cause.
     *
     * @param message the detail message explaining the cause of the exception
     * @param cause the underlying cause of this exception
     */
    public CacheLoadingException(String message, Throwable cause) {
        super(message, cause);
    }
}
