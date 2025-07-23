package com.pedromossi.caching.processor;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.google.testing.compile.Compiler.javac;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Optimized unit tests for {@link CacheXProcessor} using Google Compile Testing.
 */
@DisplayName("CacheX Annotation Processor")
class CacheXProcessorTest {

    private static final String CACHEX_ANNOTATION_SOURCE = """
            package com.pedromossi.caching.annotation;
            import java.lang.annotation.*;
            @Target(ElementType.METHOD)
            @Retention(RetentionPolicy.RUNTIME)
            public @interface CacheX {
                String key();
                Operation operation() default Operation.GET;
                enum Operation { GET, EVICT }
            }
            """;

    @Test
    @DisplayName("should compile successfully for a valid annotated method")
    void shouldCompileSuccessfullyForValidMethod() {
        JavaFileObject source = generateSource(
                "public String getUser(String id) { return \"user-data\"; }",
                "@CacheX(key = \"'user:' + #id\")"
        );
        Compilation compilation = compileWithProcessor(source);
        assertThat(compilation).succeeded();
    }

    @Nested
    @DisplayName("Modifier Validation")
    class ModifierValidation {

        @ParameterizedTest(name = "should fail for ''{0}'' method")
        @CsvSource({
                "private, Methods with @CacheX cannot be 'private'.",
                "static,  Methods with @CacheX cannot be 'static'.",
                "final,   Methods with @CacheX cannot be 'final'."
        })
        void shouldFailForInvalidMethodModifiers(String modifier, String expectedError) {
            JavaFileObject source = generateSource(
                    modifier + " String getUser(String id) { return null; }",
                    "@CacheX(key = \"'key'\")"
            );
            Compilation compilation = compileWithProcessor(source);
            assertThat(compilation).failed();
            assertThat(compilation)
                    .hadErrorContaining(expectedError)
                    .inFile(source)
                    .onLine(5);
        }

        @Test
        @DisplayName("should fail when the enclosing class is final")
        void shouldFailForFinalEnclosingClass() {
            JavaFileObject source = JavaFileObjects.forSourceString("com.example.CacheableService", """
                    package com.example;
                    import com.pedromossi.caching.annotation.CacheX;
                    public final class CacheableService {
                        @CacheX(key = "'key'")
                        public String getUser(String id) { return null; }
                    }
                    """);
            Compilation compilation = compileWithProcessor(source);
            assertThat(compilation).failed();
            assertThat(compilation)
                    .hadErrorContaining("Methods with @CacheX cannot be in a final class.")
                    .inFile(source)
                    .onLine(5);
        }
    }

    @Nested
    @DisplayName("SpEL Validation")
    class SpelValidation {

        @ParameterizedTest(name = "should fail for invalid SpEL: {0}")
        @CsvSource({
                "'user:' + #id +, The SpEL expression in the key is invalid",
                "'user:' + #userId, The SpEL expression references parameter '#userId', which does not exist"
        })
        void shouldFailForInvalidSpel(String keyExpression, String expectedError) {
            JavaFileObject source = generateSource(
                    "public String getUser(String id) { return null; }",
                    "@CacheX(key = \"" + keyExpression + "\")"
            );
            Compilation compilation = compileWithProcessor(source);
            assertThat(compilation).failed();
            assertThat(compilation).hadErrorContaining(expectedError)
                    .inFile(source)
                    .onLine(5);
        }
    }

    @Nested
    @DisplayName("Operation Validation")
    class OperationValidation {

        @Test
        @DisplayName("CacheX can only be applied to methods")
        void shouldFailWhenAppliedToNonMethod() {
            JavaFileObject source = JavaFileObjects.forSourceString("com.example.CacheableService", """
                    package com.example;
                    import com.pedromossi.caching.annotation.CacheX;
                    @CacheX(key = "'key'")
                    public class CacheableService {
                    }
                    """);
            Compilation compilation = compileWithProcessor(source);
            assertThat(compilation).failed();
            assertThat(compilation)
                    .hadErrorContaining("annotation interface not applicable to this kind of declaration")
                    .inFile(source)
                    .onLine(3);
        }

        @Test
        @DisplayName("should fail when GET operation returns void")
        void shouldFailWhenGetReturnsVoid() {
            JavaFileObject source = generateSource(
                    "public void doSomething() {}",
                    "@CacheX(key = \"'key'\")"
            );
            Compilation compilation = compileWithProcessor(source);
            assertThat(compilation).failed();
            assertThat(compilation)
                    .hadErrorContaining("Methods with @CacheX operation GET must return a non-void type.")
                    .inFile(source)
                    .onLine(5);
        }

        @Test
        @DisplayName("should compile successfully when EVICT operation returns void")
        void shouldSucceedWhenEvictReturnsVoid() {
            JavaFileObject source = generateSource(
                    "public void evictUser(String id) {}",
                    "@CacheX(key = \"'key'\", operation = CacheX.Operation.EVICT)"
            );
            Compilation compilation = compileWithProcessor(source);
            assertThat(compilation).succeeded();
        }
    }

    /** Helper to generate a standard service class with a custom method definition. */
    private JavaFileObject generateSource(String methodSignature, String annotation) {
        return JavaFileObjects.forSourceString("com.example.CacheableService", """
                package com.example;
                import com.pedromossi.caching.annotation.CacheX;
                public class CacheableService {
                    %s
                    %s
                }
                """.formatted(annotation, methodSignature));
    }

    /** Helper to compile a source file with the CacheXProcessor. */
    private Compilation compileWithProcessor(JavaFileObject source) {
        JavaFileObject cacheXAnnotation = JavaFileObjects.forSourceString(
                "com.pedromossi.caching.annotation.CacheX", CACHEX_ANNOTATION_SOURCE);
        return javac().withProcessors(new CacheXProcessor()).compile(cacheXAnnotation, source);
    }
}