package com.pedromossi.caching.redis.serializer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.pedromossi.caching.serializer.CacheSerializer;
import com.pedromossi.caching.serializer.SerializationException;
import java.io.IOException;
import org.springframework.core.ParameterizedTypeReference;

/**
 * Jackson-based implementation of {@link CacheSerializer} for JSON serialization.
 *
 * <p>This implementation provides JSON-based serialization and deserialization using
 * Jackson ObjectMapper. It's particularly well-suited for caching complex objects,
 * collections, and generic types while maintaining human-readable cache data.</p>
 *
 * <p><strong>Key Features:</strong></p>
 * <ul>
 *   <li>Full support for generic types through {@link ParameterizedTypeReference}</li>
 *   <li>Human-readable JSON format for debugging and monitoring</li>
 *   <li>Configurable ObjectMapper for custom serialization behavior</li>
 *   <li>Efficient binary output through Jackson's optimized JSON processing</li>
 * </ul>
 *
 * <p><strong>Performance Characteristics:</strong></p>
 * <ul>
 *   <li>Good balance between performance and readability</li>
 *   <li>Efficient for medium-sized objects and collections</li>
 *   <li>Memory overhead is moderate due to JSON format</li>
 *   <li>Supports streaming for large objects (when configured)</li>
 * </ul>
 *
 * <p><strong>Configuration:</strong> The behavior can be customized by providing
 * a pre-configured ObjectMapper with specific modules, date formats, or
 * serialization features enabled.</p>
 *
 * @since 1.2.0
 * @see CacheSerializer
 * @see ObjectMapper
 * @see ParameterizedTypeReference
 */
public class JacksonCacheSerializer implements CacheSerializer {

    private final ObjectMapper objectMapper;

    /**
     * Creates a new JacksonCacheSerializer with the specified ObjectMapper.
     *
     * <p>The provided ObjectMapper will be used for all serialization and
     * deserialization operations. This allows for complete control over
     * Jackson's behavior, including custom modules, serializers, and
     * configuration options.</p>
     *
     * <p><strong>Thread Safety:</strong> The ObjectMapper should be thread-safe
     * (which it is by default) as this serializer may be used concurrently.</p>
     *
     * <p><strong>Recommended Configuration:</strong></p>
     * <ul>
     *   <li>Enable JavaTimeModule for Java 8 time types</li>
     *   <li>Enable Jdk8Module for Optional and other Java 8 features</li>
     *   <li>Configure appropriate date/time formats</li>
     *   <li>Set desired inclusion policies (e.g., NON_NULL)</li>
     * </ul>
     *
     * @param objectMapper the Jackson ObjectMapper to use for serialization
     *                     (must not be null and should be thread-safe)
     * @throws NullPointerException if objectMapper is null
     */
    public JacksonCacheSerializer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Serializes an object to JSON format as a byte array.
     *
     * <p>This method converts any object to its JSON representation using
     * the configured ObjectMapper. The resulting byte array contains UTF-8
     * encoded JSON data suitable for storage in distributed caches.</p>
     *
     * <p><strong>Null Handling:</strong> Null objects are supported and will
     * result in JSON null representation, which serializes to a small byte array.</p>
     *
     * <p><strong>Complex Types:</strong> Supports all types that Jackson can
     * serialize, including collections, maps, custom objects, and nested
     * generic types.</p>
     *
     * @param object the object to serialize (may be null)
     * @return UTF-8 encoded JSON byte array representing the object
     * @throws SerializationException if Jackson fails to process the object, which can occur due to:
     *         <ul>
     *           <li>Circular references in the object graph</li>
     *           <li>Non-serializable fields without proper Jackson annotations</li>
     *           <li>Custom serializers throwing exceptions</li>
     *           <li>Memory allocation failures for very large objects</li>
     *         </ul>
     */
    @Override
    public byte[] serialize(Object object) {
        try {
            return objectMapper.writeValueAsBytes(object);
        } catch (JsonProcessingException e) {
            throw new SerializationException("Failed to serialize object to JSON", e);
        }
    }

    /**
     * Deserializes JSON byte array back to an object of the specified type.
     *
     * <p>This method reconstructs an object from its JSON representation with
     * full type safety. It leverages Jackson's type system to handle complex
     * generic types correctly, preventing type erasure issues common with
     * JSON deserialization.</p>
     *
     * <p><strong>Type Safety:</strong> The {@link ParameterizedTypeReference}
     * provides complete type information, enabling correct deserialization
     * of complex types like {@code List<Map<String, Integer>>}.</p>
     *
     * <p><strong>Null Handling:</strong> Empty or null byte arrays return null.
     * JSON null values also deserialize to null.</p>
     *
     * <p><strong>Error Recovery:</strong> Malformed JSON or type mismatches
     * result in SerializationException, allowing calling code to implement
     * fallback strategies.</p>
     *
     * @param <T> the target type for deserialization
     * @param data the UTF-8 encoded JSON byte array (null or empty arrays return null)
     * @param typeRef complete type information for safe deserialization (must not be null)
     * @return the deserialized object of type T, or null if data is null/empty or represents JSON null
     * @throws SerializationException if deserialization fails, including:
     *         <ul>
     *           <li>Malformed or invalid JSON data</li>
     *           <li>Type incompatibility between JSON and target type</li>
     *           <li>Missing required fields in JSON</li>
     *           <li>Custom deserializers throwing exceptions</li>
     *           <li>Schema evolution issues</li>
     *         </ul>
     * @throws NullPointerException if typeRef is null
     */
    @Override
    public <T> T deserialize(byte[] data, ParameterizedTypeReference<T> typeRef) {
        if (data == null || data.length == 0) {
            return null;
        }
        try {
            JavaType javaType = TypeFactory.defaultInstance().constructType(typeRef.getType());
            return objectMapper.readValue(data, javaType);
        } catch (IOException e) {
            throw new SerializationException("Failed to deserialize JSON to object", e);
        }
    }
}