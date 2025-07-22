package com.pedromossi.caching.processor;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;
import org.junit.jupiter.api.Test;

import javax.tools.JavaFileObject;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.google.testing.compile.Compiler.javac;

/**
 * Unit tests for {@link CacheXProcessor}.
 * These tests use the Google Compile Testing library to simulate the annotation processing phase.
 */
class CacheXProcessorTest {

    private static final String CACHEX_ANNOTATION_SOURCE =
            "package com.pedromossi.caching.annotation;\n" +
                    "import java.lang.annotation.*;\n" +
                    "@Target(ElementType.METHOD)\n" +
                    "@Retention(RetentionPolicy.RUNTIME)\n" +
                    "public @interface CacheX {\n" +
                    "    String key();\n" +
                    "    Operation operation() default Operation.GET;\n" +
                    "    enum Operation { GET, EVICT }\n" +
                    "}";

    @Test
    void shouldCompileSuccessfully_whenMethodIsValid() {
        JavaFileObject source = JavaFileObjects.forSourceString("com.example.CacheableService",
                "package com.example;\n" +
                        "import com.pedromossi.caching.annotation.CacheX;\n" +
                        "public class CacheableService {\n" +
                        "    @CacheX(key = \"'user:' + #id\")\n" +
                        "    public String getUser(String id) {\n" +
                        "        return \"user-data\";\n" +
                        "    }\n" +
                        "}\n"
        );

        Compilation compilation = javac()
                .withProcessors(new CacheXProcessor())
                .compile(JavaFileObjects.forSourceString("com.pedromossi.caching.annotation.CacheX", CACHEX_ANNOTATION_SOURCE), source);

        assertThat(compilation).succeeded();
    }

    @Test
    void shouldFailCompilation_whenMethodIsPrivate() {
        JavaFileObject source = JavaFileObjects.forSourceString("com.example.CacheableService",
                "package com.example;\n" +
                        "import com.pedromossi.caching.annotation.CacheX;\n" +
                        "public class CacheableService {\n" +
                        "    @CacheX(key = \"'key'\")\n" +
                        "    private String getUser(String id) { return null; }\n" +
                        "}\n"
        );

        Compilation compilation = compileWithProcessor(source);
        assertThat(compilation).failed();
        assertThat(compilation)
                .hadErrorContaining("Methods with @CacheX cannot be 'private'.")
                .inFile(source)
                .onLine(5);
    }

    @Test
    void shouldFailCompilation_whenMethodIsStatic() {
        JavaFileObject source = JavaFileObjects.forSourceString("com.example.CacheableService",
                "package com.example;\n" +
                        "import com.pedromossi.caching.annotation.CacheX;\n" +
                        "public class CacheableService {\n" +
                        "    @CacheX(key = \"'key'\")\n" +
                        "    public static String getUser(String id) { return null; }\n" +
                        "}\n"
        );

        Compilation compilation = compileWithProcessor(source);
        assertThat(compilation).failed();
        assertThat(compilation)
                .hadErrorContaining("Methods with @CacheX cannot be 'static'.")
                .inFile(source)
                .onLine(5);
    }

    @Test
    void shouldFailCompilation_whenMethodIsFinal() {
        JavaFileObject source = JavaFileObjects.forSourceString("com.example.CacheableService",
                "package com.example;\n" +
                        "import com.pedromossi.caching.annotation.CacheX;\n" +
                        "public class CacheableService {\n" +
                        "    @CacheX(key = \"'key'\")\n" +
                        "    public final String getUser(String id) { return null; }\n" +
                        "}\n"
        );

        Compilation compilation = compileWithProcessor(source);
        assertThat(compilation).failed();
        assertThat(compilation)
                .hadErrorContaining("Methods with @CacheX cannot be 'final'.")
                .inFile(source)
                .onLine(5);
    }

    @Test
    void shouldFailCompilation_whenEnclosingClassIsFinal() {
        JavaFileObject source = JavaFileObjects.forSourceString("com.example.CacheableService",
                "package com.example;\n" +
                        "import com.pedromossi.caching.annotation.CacheX;\n" +
                        "public final class CacheableService {\n" +
                        "    @CacheX(key = \"'key'\")\n" +
                        "    public String getUser(String id) { return null; }\n" +
                        "}\n"
        );

        Compilation compilation = compileWithProcessor(source);
        assertThat(compilation).failed();
        assertThat(compilation)
                .hadErrorContaining("Methods with @CacheX cannot be in a final class. The enclosing class 'CacheableService' is final.")
                .inFile(source)
                .onLine(5);
    }

    @Test
    void shouldFailCompilation_whenSpelIsInvalid() {
        JavaFileObject source = JavaFileObjects.forSourceString("com.example.CacheableService",
                "package com.example;\n" +
                        "import com.pedromossi.caching.annotation.CacheX;\n" +
                        "public class CacheableService {\n" +
                        "    @CacheX(key = \"'user:' + #id +\")\n" + // Invalid SpEL
                        "    public String getUser(String id) { return null; }\n" +
                        "}\n"
        );

        Compilation compilation = compileWithProcessor(source);
        assertThat(compilation).failed();
        assertThat(compilation)
                .hadErrorContaining("The SpEL expression in the key is invalid: 'user:' + #id +.")
                .inFile(source)
                .onLine(5);
    }

    @Test
    void shouldFailCompilation_whenSpelParamDoesNotExist() {
        JavaFileObject source = JavaFileObjects.forSourceString("com.example.CacheableService",
                "package com.example;\n" +
                        "import com.pedromossi.caching.annotation.CacheX;\n" +
                        "public class CacheableService {\n" +
                        "    @CacheX(key = \"'user:' + #userId\")\n" + // #userId does not exist
                        "    public String getUser(String id) { return null; }\n" +
                        "}\n"
        );

        Compilation compilation = compileWithProcessor(source);
        assertThat(compilation).failed();
        assertThat(compilation)
                .hadErrorContaining("The SpEL expression references parameter '#userId', which does not exist in the method signature.")
                .inFile(source)
                .onLine(5);
    }

    @Test
    void shouldFailCompilation_whenGetOperationReturnsVoid() {
        JavaFileObject source = JavaFileObjects.forSourceString("com.example.CacheableService",
                "package com.example;\n" +
                        "import com.pedromossi.caching.annotation.CacheX;\n" +
                        "public class CacheableService {\n" +
                        "    @CacheX(key = \"'key'\", operation = CacheX.Operation.GET)\n" +
                        "    public void doSomething() {}\n" +
                        "}\n"
        );

        Compilation compilation = compileWithProcessor(source);
        assertThat(compilation).failed();
        assertThat(compilation)
                .hadErrorContaining("Methods with @CacheX operation GET must return a non-void type. The method 'doSomething' returns void.")
                .inFile(source)
                .onLine(5);
    }

    @Test
    void shouldCompileSuccessfully_whenEvictOperationReturnsVoid() {
        JavaFileObject source = JavaFileObjects.forSourceString("com.example.CacheableService",
                "package com.example;\n" +
                        "import com.pedromossi.caching.annotation.CacheX;\n" +
                        "public class CacheableService {\n" +
                        "    @CacheX(key = \"'key'\", operation = CacheX.Operation.EVICT)\n" +
                        "    public void evictUser(String id) {}\n" +
                        "}\n"
        );

        Compilation compilation = compileWithProcessor(source);
        assertThat(compilation).succeeded();
    }

    @Test
    void shouldFailCompilation_whenAnnotationIsOnNonMethodElement() {
        JavaFileObject source = JavaFileObjects.forSourceString("com.example.InvalidUsage",
                "package com.example;\n" +
                        "import com.pedromossi.caching.annotation.CacheX;\n" +
                        "@CacheX(key = \"'key'\")\n" +
                        "public class InvalidUsage {\n" +
                        "}\n"
        );

        Compilation compilation = compileWithProcessor(source);
        assertThat(compilation).failed();
        assertThat(compilation)
                .hadErrorContaining("annotation interface not applicable to this kind of declaration")
                .inFile(source)
                .onLine(3);

    }

    /**
     * Helper method to compile a source file with the CacheXProcessor.
     * It includes a mock of the @CacheX annotation to make tests self-contained.
     *
     * @param source The source file to compile.
     * @return The result of the compilation.
     */
    private Compilation compileWithProcessor(JavaFileObject source) {
        JavaFileObject cacheXAnnotation = JavaFileObjects.forSourceString(
                "com.pedromossi.caching.annotation.CacheX",
                CACHEX_ANNOTATION_SOURCE
        );

        return javac()
                .withProcessors(new CacheXProcessor())
                .compile(cacheXAnnotation, source);
    }
}