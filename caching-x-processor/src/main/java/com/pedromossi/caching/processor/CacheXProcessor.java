// Dentro do módulo caching-x-processor
package com.pedromossi.caching.processor;

import com.google.auto.service.AutoService;
import com.pedromossi.caching.annotation.CacheX;
import org.springframework.expression.spel.standard.SpelExpressionParser;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.tools.Diagnostic;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Annotation processor for the @CacheX annotation.
 *
 * <p>This processor performs compile-time validation of methods annotated with @CacheX,
 * ensuring they meet the required constraints for proper caching functionality. It validates
 * method modifiers and SpEL expressions used in cache keys.</p>
 *
 * <p>The processor performs the following validations:</p>
 * <ul>
 *   <li>Ensures @CacheX is only applied to methods</li>
 *   <li>Validates that annotated methods are not private, static, or final</li>
 *   <li>Validates SpEL expression syntax in cache keys</li>
 *   <li>Verifies that SpEL parameter references exist in the method signature</li>
 * </ul>
 *
 * @author Pedro Mossi
 * @since 1.0
 */
@AutoService(Processor.class) // Registers the processor automatically
@SupportedAnnotationTypes("com.pedromossi.caching.annotation.CacheX") // Specifies which annotation to process
@SupportedSourceVersion(SourceVersion.RELEASE_21) // Supported Java version
public class CacheXProcessor extends AbstractProcessor {

    /**
     * Messager instance for reporting compilation errors and warnings.
     */
    private Messager messager;

    /**
     * SpEL expression parser for validating cache key expressions.
     */
    private final SpelExpressionParser spelParser = new SpelExpressionParser();

    /**
     * Initializes the processor with the processing environment.
     *
     * @param processingEnv the processing environment providing utilities for processing
     */
    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        this.messager = processingEnv.getMessager();
    }

    /**
     * Processes all elements annotated with @CacheX in the current compilation round.
     *
     * <p>This method is called by the compiler for each round of annotation processing.
     * It validates all methods annotated with @CacheX, checking their modifiers and
     * SpEL expressions for correctness.</p>
     *
     * @param annotations the annotation types requested to be processed
     * @param roundEnv environment for information about the current and prior rounds
     * @return false to allow other processors to process the same annotations
     */
    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        // Iterate over all elements (methods) annotated with @CacheX
        for (Element element : roundEnv.getElementsAnnotatedWith(CacheX.class)) {
            // The @CacheX annotation can only be applied to methods
            if (element.getKind() != ElementKind.METHOD) {
                error(element, "@CacheX can only be applied to methods.");
                continue;
            }

            ExecutableElement method = (ExecutableElement) element;
            validateMethodModifiers(method);
            validateSpelExpression(method);
        }
        return false; // Allows other processors to also act on these annotations
    }

    /**
     * Validates that the annotated method does not have invalid modifiers.
     *
     * <p>Methods annotated with @CacheX cannot be private, static, or final as these
     * modifiers would prevent proper proxy-based caching mechanisms from working.</p>
     *
     * @param method the method to validate
     */
    private void validateMethodModifiers(ExecutableElement method) {
        Set<Modifier> modifiers = method.getModifiers();
        if (modifiers.contains(Modifier.PRIVATE)) {
            error(method, "Methods with @CacheX cannot be 'private'.");
        }
        if (modifiers.contains(Modifier.STATIC)) {
            error(method, "Methods with @CacheX cannot be 'static'.");
        }
        if (modifiers.contains(Modifier.FINAL)) {
            error(method, "Methods with @CacheX cannot be 'final'.");
        }
    }

    /**
     * Validates the SpEL expression syntax and parameter references in the cache key.
     *
     * <p>This method performs two levels of validation:</p>
     * <ol>
     *   <li>Validates the SpEL expression syntax using Spring's SpEL parser</li>
     *   <li>Ensures all parameter references (e.g., #paramName) in the expression
     *       correspond to actual method parameters</li>
     * </ol>
     *
     * @param method the method whose @CacheX annotation contains the SpEL expression
     */
    private void validateSpelExpression(ExecutableElement method) {
        CacheX annotation = method.getAnnotation(CacheX.class);
        String spelKey = annotation.key();

        // 1. Validate SpEL expression syntax
        try {
            spelParser.parseExpression(spelKey);
        } catch (Exception e) {
            error(method, "The SpEL expression in the key is invalid: %s. Error: %s", spelKey, e.getMessage());
            return; // If syntax is invalid, no point in checking parameters
        }

        // 2. Validate that SpEL parameters exist in the method signature
        Set<String> methodParamNames = method.getParameters().stream()
                .map(p -> p.getSimpleName().toString())
                .collect(Collectors.toSet());

        // Simple regex to find variables like #name
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("#(\\w+)");
        java.util.regex.Matcher matcher = pattern.matcher(spelKey);

        while (matcher.find()) {
            String spelParamName = matcher.group(1);
            if (!methodParamNames.contains(spelParamName)) {
                error(method, "The SpEL expression references parameter '#%s', which does not exist in the method signature.", spelParamName);
            }
        }
    }

    /**
     * Helper method to simplify error message logging.
     *
     * <p>Formats and prints error messages using the processor's messager,
     * associating them with the specific code element that caused the error.</p>
     *
     * @param element the code element associated with the error
     * @param msg the error message format string
     * @param args arguments for the format string
     */
    private void error(Element element, String msg, Object... args) {
        messager.printMessage(Diagnostic.Kind.ERROR, String.format(msg, args), element);
    }
}