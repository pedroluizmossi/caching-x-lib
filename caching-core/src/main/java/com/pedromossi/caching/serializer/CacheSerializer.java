package com.pedromossi.caching.serializer;

import org.springframework.core.ParameterizedTypeReference;

/**
 * Interface for serialization and deserialization of cache objects.
 *
 * <p>This interface provides a pluggable serialization mechanism that allows the caching
 * library to be agnostic to the serialization format. Implementations can provide
 * different serialization strategies such as JSON, Protocol Buffers, Avro, or any
 * custom binary format.</p>
 *
 * <p><strong>Thread Safety:</strong> Implementations of this interface should be thread-safe
 * as they may be used concurrently across multiple threads in a caching environment.</p>
 *
 * <p><strong>Performance Considerations:</strong> Since serialization operations are
 * performed frequently in caching scenarios, implementations should be optimized
 * for performance and minimize memory allocation overhead.</p>
 *
 * <p><strong>Type Safety:</strong> The interface leverages {@link ParameterizedTypeReference}
 * to provide compile-time type safety for deserialization operations, preventing
 * {@code ClassCastException} at runtime.</p>
 *
 * <p><strong>Common Use Cases:</strong></p>
 * <ul>
 *   <li>Distributed caching where objects need to be serialized for network transmission</li>
 *   <li>Persistent caching where objects are stored in binary format</li>
 *   <li>Cross-language cache compatibility using standard serialization formats</li>
 * </ul>
 *
 * @since 1.2.0
 * @see ParameterizedTypeReference
 * @see SerializationException
 */
public interface CacheSerializer {

    /**
     * Serializes an object into a byte array.
     *
     * <p>This method converts any object into its binary representation suitable
     * for storage or transmission. The resulting byte array should contain all
     * necessary information to reconstruct the original object during deserialization.</p>
     *
     * <p><strong>Null Handling:</strong> Implementations should clearly document
     * their behavior when handling null objects. Common approaches include returning
     * an empty byte array, a special null marker, or throwing an exception.</p>
     *
     * <p><strong>Object Requirements:</strong> The object being serialized must be
     * compatible with the serialization format implemented. For example, JSON
     * serializers typically require objects to be serializable via reflection,
     * while custom binary formats may have specific requirements.</p>
     *
     * @param object the object to be serialized (may be null depending on implementation)
     * @return the byte array representing the serialized object (never null)
     * @throws SerializationException if an error occurs during serialization, such as:
     *         <ul>
     *           <li>The object contains non-serializable fields</li>
     *           <li>Circular references are detected</li>
     *           <li>I/O errors during serialization processing</li>
     *           <li>Memory allocation failures for large objects</li>
     *         </ul>
     * @throws IllegalArgumentException if the object type is not supported by this serializer
     */
    byte[] serialize(Object object) throws SerializationException;

    /**
     * Deserializes a byte array back into an object of the specified type.
     *
     * <p>This method reconstructs an object from its binary representation, ensuring
     * type safety through the use of {@link ParameterizedTypeReference}. The method
     * guarantees that the returned object is compatible with the requested type or
     * throws an exception if conversion is not possible.</p>
     *
     * <p><strong>Type Safety:</strong> The {@code ParameterizedTypeReference} parameter
     * provides complete type information, including generic types, ensuring that
     * complex objects like {@code List<String>} or {@code Map<String, Integer>}
     * are deserialized correctly without type erasure issues.</p>
     *
     * <p><strong>Validation:</strong> Implementations should validate that the
     * deserialized object matches the expected type before returning. This includes
     * checking for type compatibility and verifying that generic type parameters
     * match the expected structure.</p>
     *
     * <p><strong>Empty Data Handling:</strong> The behavior for empty or null byte
     * arrays should be clearly documented. Common approaches include returning null,
     * returning a default instance, or throwing an exception.</p>
     *
     * @param <T> the target type of the deserialized object
     * @param data the byte array to be deserialized (must not be null unless explicitly supported)
     * @param typeRef the type reference that guides deserialization and ensures type safety
     *                (must not be null)
     * @return the deserialized object of type T, or null if the data represents a null value
     * @throws SerializationException if an error occurs during deserialization, such as:
     *         <ul>
     *           <li>Corrupted or invalid byte array data</li>
     *           <li>Version incompatibility between serialization and deserialization</li>
     *           <li>Type mismatch between data and requested type</li>
     *           <li>Missing required fields or incompatible schema changes</li>
     *         </ul>
     * @throws NullPointerException if data or typeRef is null (unless null data is explicitly supported)
     * @throws IllegalArgumentException if the target type is not supported by this serializer
     * @see ParameterizedTypeReference
     */
    <T> T deserialize(byte[] data, ParameterizedTypeReference<T> typeRef) throws SerializationException;
}