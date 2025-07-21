package com.pedromossi.caching.serializer;

/**
 * Exception thrown when an error occurs during cache serialization or deserialization operations.
 *
 * <p>This runtime exception encapsulates various types of serialization failures that can
 * occur during cache operations, providing a unified exception handling mechanism for
 * serialization-related errors. It extends {@code RuntimeException} to avoid forcing
 * explicit exception handling in cache operations, allowing for cleaner cache API design.</p>
 *
 * <p><strong>Common Scenarios:</strong></p>
 * <ul>
 *   <li>Object serialization failures due to non-serializable fields</li>
 *   <li>Byte array deserialization failures due to corrupted data</li>
 *   <li>Type conversion errors during deserialization</li>
 *   <li>Schema version incompatibilities</li>
 *   <li>Memory allocation failures for large objects</li>
 *   <li>I/O errors during serialization processing</li>
 * </ul>
 *
 * <p><strong>Error Handling Strategy:</strong> Applications using the caching library
 * can catch this exception to implement fallback strategies, such as bypassing the
 * cache and loading data directly from the source, or using alternative serialization
 * formats.</p>
 *
 * <p><strong>Debugging Support:</strong> This exception always includes the underlying
 * cause to facilitate debugging and root cause analysis of serialization issues.</p>
 *
 * @since 1.2.0
 * @see CacheSerializer
 */
public class SerializationException extends RuntimeException {

    /**
     * Constructs a new SerializationException with the specified detail message and cause.
     *
     * <p>This constructor is used to wrap lower-level exceptions that occur during
     * serialization or deserialization operations, providing context about the
     * specific failure while preserving the original exception information.</p>
     *
     * <p><strong>Message Guidelines:</strong> The message should provide clear context
     * about what operation was being performed when the error occurred, such as
     * "Failed to serialize object of type User" or "Unable to deserialize data to List&lt;String&gt;".</p>
     *
     * @param message the detail message explaining the serialization failure
     *                (should be descriptive and help with debugging)
     * @param cause the underlying exception that caused this serialization failure
     *              (must not be null to ensure proper error traceability)
     * @throws NullPointerException if cause is null (depending on superclass implementation)
     */
    public SerializationException(String message, Throwable cause) {
        super(message, cause);
    }
}