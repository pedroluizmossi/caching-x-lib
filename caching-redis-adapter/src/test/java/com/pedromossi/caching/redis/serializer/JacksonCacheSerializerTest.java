package com.pedromossi.caching.redis.serializer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pedromossi.caching.serializer.SerializationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.ParameterizedTypeReference;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JacksonCacheSerializerTest {

    private ObjectMapper objectMapper;
    @Mock private ObjectMapper mockObjectMapper;
    private JacksonCacheSerializer serializer;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        serializer = new JacksonCacheSerializer(objectMapper);
    }

    @Test
    @DisplayName("serialize should convert simple object to JSON bytes")
    void serialize_shouldConvertSimpleObjectToJsonBytes() {
        String value = "Hello World";

        byte[] result = serializer.serialize(value);

        assertThat(result).isNotNull();
        assertThat(new String(result)).isEqualTo("\"Hello World\"");
    }

    @Test
    @DisplayName("serialize should handle null object")
    void serialize_shouldHandleNullObject() {
        byte[] result = serializer.serialize(null);

        assertThat(result).isNotNull();
        assertThat(new String(result)).isEqualTo("null");
    }

    @Test
    @DisplayName("serialize should convert complex object to JSON bytes")
    void serialize_shouldConvertComplexObjectToJsonBytes() {
        TestObject obj = new TestObject("John", 30, Arrays.asList("tag1", "tag2"));

        byte[] result = serializer.serialize(obj);

        assertThat(result).isNotNull();
        String json = new String(result);
        assertThat(json).contains("\"name\":\"John\"");
        assertThat(json).contains("\"age\":30");
        assertThat(json).contains("\"tags\":[\"tag1\",\"tag2\"]");
    }

    @Test
    @DisplayName("serialize should convert collection to JSON bytes")
    void serialize_shouldConvertCollectionToJsonBytes() {
        List<String> list = Arrays.asList("item1", "item2", "item3");

        byte[] result = serializer.serialize(list);

        assertThat(result).isNotNull();
        assertThat(new String(result)).isEqualTo("[\"item1\",\"item2\",\"item3\"]");
    }

    @Test
    @DisplayName("serialize should convert map to JSON bytes")
    void serialize_shouldConvertMapToJsonBytes() {
        Map<String, Integer> map = new HashMap<>();
        map.put("key1", 100);
        map.put("key2", 200);

        byte[] result = serializer.serialize(map);

        assertThat(result).isNotNull();
        String json = new String(result);
        assertThat(json).contains("\"key1\":100");
        assertThat(json).contains("\"key2\":200");
    }

    @Test
    @DisplayName("serialize should throw SerializationException when Jackson fails")
    void serialize_shouldThrowSerializationExceptionWhenJacksonFails() throws JsonProcessingException {
        JacksonCacheSerializer mockSerializer = new JacksonCacheSerializer(mockObjectMapper);
        when(mockObjectMapper.writeValueAsBytes(any())).thenThrow(new JsonProcessingException("Mock error") {});

        assertThatThrownBy(() -> mockSerializer.serialize("test"))
                .isInstanceOf(SerializationException.class)
                .hasMessage("Failed to serialize object to JSON")
                .hasCauseInstanceOf(JsonProcessingException.class);
    }

    @Test
    @DisplayName("deserialize should return null for null input")
    void deserialize_shouldReturnNullForNullInput() {
        var typeRef = new ParameterizedTypeReference<String>() {};

        String result = serializer.deserialize(null, typeRef);

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("deserialize should return null for empty byte array")
    void deserialize_shouldReturnNullForEmptyByteArray() {
        var typeRef = new ParameterizedTypeReference<String>() {};

        String result = serializer.deserialize(new byte[0], typeRef);

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("deserialize should convert JSON bytes to simple object")
    void deserialize_shouldConvertJsonBytesToSimpleObject() {
        byte[] data = "\"Hello World\"".getBytes();
        var typeRef = new ParameterizedTypeReference<String>() {};

        String result = serializer.deserialize(data, typeRef);

        assertThat(result).isEqualTo("Hello World");
    }

    @Test
    @DisplayName("deserialize should convert JSON null to null object")
    void deserialize_shouldConvertJsonNullToNullObject() {
        byte[] data = "null".getBytes();
        var typeRef = new ParameterizedTypeReference<String>() {};

        String result = serializer.deserialize(data, typeRef);

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("deserialize should convert JSON bytes to complex object")
    void deserialize_shouldConvertJsonBytesToComplexObject() {
        String json = "{\"name\":\"John\",\"age\":30,\"tags\":[\"tag1\",\"tag2\"]}";
        byte[] data = json.getBytes();
        var typeRef = new ParameterizedTypeReference<TestObject>() {};

        TestObject result = serializer.deserialize(data, typeRef);

        assertThat(result).isNotNull();
        assertThat(result.getName()).isEqualTo("John");
        assertThat(result.getAge()).isEqualTo(30);
        assertThat(result.getTags()).containsExactly("tag1", "tag2");
    }

    @Test
    @DisplayName("deserialize should convert JSON bytes to list")
    void deserialize_shouldConvertJsonBytesToList() {
        byte[] data = "[\"item1\",\"item2\",\"item3\"]".getBytes();
        var typeRef = new ParameterizedTypeReference<List<String>>() {};

        List<String> result = serializer.deserialize(data, typeRef);

        assertThat(result).containsExactly("item1", "item2", "item3");
    }

    @Test
    @DisplayName("deserialize should convert JSON bytes to map")
    void deserialize_shouldConvertJsonBytesToMap() {
        byte[] data = "{\"key1\":100,\"key2\":200}".getBytes();
        var typeRef = new ParameterizedTypeReference<Map<String, Integer>>() {};

        Map<String, Integer> result = serializer.deserialize(data, typeRef);

        assertThat(result).containsEntry("key1", 100).containsEntry("key2", 200);
    }

    @Test
    @DisplayName("deserialize should handle complex generic types")
    void deserialize_shouldHandleComplexGenericTypes() {
        String json = "{\"users\":[{\"name\":\"John\",\"age\":30,\"tags\":[\"admin\"]},{\"name\":\"Jane\",\"age\":25,\"tags\":[\"user\"]}]}";
        byte[] data = json.getBytes();
        var typeRef = new ParameterizedTypeReference<Map<String, List<TestObject>>>() {};

        Map<String, List<TestObject>> result = serializer.deserialize(data, typeRef);

        assertThat(result).containsKey("users");
        assertThat(result.get("users")).hasSize(2);
        assertThat(result.get("users").get(0).getName()).isEqualTo("John");
        assertThat(result.get("users").get(1).getName()).isEqualTo("Jane");
    }

    @Test
    @DisplayName("deserialize should throw SerializationException for malformed JSON")
    void deserialize_shouldThrowSerializationExceptionForMalformedJson() {
        byte[] data = "{invalid json}".getBytes();
        var typeRef = new ParameterizedTypeReference<String>() {};

        assertThatThrownBy(() -> serializer.deserialize(data, typeRef))
                .isInstanceOf(SerializationException.class)
                .hasMessage("Failed to deserialize JSON to object")
                .hasCauseInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("deserialize should throw SerializationException for type mismatch")
    void deserialize_shouldThrowSerializationExceptionForTypeMismatch() {
        byte[] data = "\"string value\"".getBytes();
        var typeRef = new ParameterizedTypeReference<Integer>() {};

        assertThatThrownBy(() -> serializer.deserialize(data, typeRef))
                .isInstanceOf(SerializationException.class)
                .hasMessage("Failed to deserialize JSON to object")
                .hasCauseInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("deserialize should throw SerializationException when Jackson fails")
    void deserialize_shouldThrowSerializationExceptionWhenJacksonFails() throws IOException {
        JacksonCacheSerializer mockSerializer = new JacksonCacheSerializer(mockObjectMapper);
        byte[] data = "\"test\"".getBytes();
        var typeRef = new ParameterizedTypeReference<String>() {};
        when(mockObjectMapper.readValue(eq(data), any(JavaType.class))).thenThrow(new IOException("Mock error"));

        assertThatThrownBy(() -> mockSerializer.deserialize(data, typeRef))
                .isInstanceOf(SerializationException.class)
                .hasMessage("Failed to deserialize JSON to object")
                .hasCauseInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("roundtrip serialization should preserve object integrity")
    void roundtripSerialization_shouldPreserveObjectIntegrity() {
        TestObject original = new TestObject("Alice", 28, Arrays.asList("developer", "manager"));
        var typeRef = new ParameterizedTypeReference<TestObject>() {};

        byte[] serialized = serializer.serialize(original);
        TestObject deserialized = serializer.deserialize(serialized, typeRef);

        assertThat(deserialized).isNotNull();
        assertThat(deserialized.getName()).isEqualTo(original.getName());
        assertThat(deserialized.getAge()).isEqualTo(original.getAge());
        assertThat(deserialized.getTags()).isEqualTo(original.getTags());
    }

    @Test
    @DisplayName("roundtrip serialization should preserve collection integrity")
    void roundtripSerialization_shouldPreserveCollectionIntegrity() {
        List<Integer> original = Arrays.asList(1, 2, 3, 4, 5);
        var typeRef = new ParameterizedTypeReference<List<Integer>>() {};

        byte[] serialized = serializer.serialize(original);
        List<Integer> deserialized = serializer.deserialize(serialized, typeRef);

        assertThat(deserialized).isEqualTo(original);
    }

    @Test
    @DisplayName("roundtrip serialization should preserve map integrity")
    void roundtripSerialization_shouldPreserveMapIntegrity() {
        Map<String, Object> original = new HashMap<>();
        original.put("string", "value");
        original.put("number", 42);
        original.put("boolean", true);
        var typeRef = new ParameterizedTypeReference<Map<String, Object>>() {};

        byte[] serialized = serializer.serialize(original);
        Map<String, Object> deserialized = serializer.deserialize(serialized, typeRef);

        assertThat(deserialized).containsEntry("string", "value");
        assertThat(deserialized).containsEntry("number", 42);
        assertThat(deserialized).containsEntry("boolean", true);
    }

    @Test
    @DisplayName("should handle edge case with empty string")
    void shouldHandleEdgeCaseWithEmptyString() {
        String original = "";
        var typeRef = new ParameterizedTypeReference<String>() {};

        byte[] serialized = serializer.serialize(original);
        String deserialized = serializer.deserialize(serialized, typeRef);

        assertThat(deserialized).isEqualTo(original);
    }

    @Test
    @DisplayName("should handle edge case with very large string")
    void shouldHandleEdgeCaseWithVeryLargeString() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10000; i++) {
            sb.append("This is a very long string that will test the serializer with large data. ");
        }
        String original = sb.toString();
        var typeRef = new ParameterizedTypeReference<String>() {};

        byte[] serialized = serializer.serialize(original);
        String deserialized = serializer.deserialize(serialized, typeRef);

        assertThat(deserialized).isEqualTo(original);
    }

    @Test
    @DisplayName("should handle special characters and unicode")
    void shouldHandleSpecialCharactersAndUnicode() {
        String original = "Special chars: !@#$%^&*()_+{}|:<>?[]\\;'\",./ Unicode: αβγδε 中文 🚀🎉";
        var typeRef = new ParameterizedTypeReference<String>() {};

        byte[] serialized = serializer.serialize(original);
        String deserialized = serializer.deserialize(serialized, typeRef);

        assertThat(deserialized).isEqualTo(original);
    }

    private static class TestObject {
        private String name;
        private int age;
        private List<String> tags;

        public TestObject() {}

        public TestObject(String name, int age, List<String> tags) {
            this.name = name;
            this.age = age;
            this.tags = tags;
        }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public int getAge() { return age; }
        public void setAge(int age) { this.age = age; }
        public List<String> getTags() { return tags; }
        public void setTags(List<String> tags) { this.tags = tags; }
    }
}
