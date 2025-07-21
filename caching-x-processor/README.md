# Caching-X-Processor Module

The **CacheXProcessor** is a compile-time annotation processor that validates the usage of the `@CacheX` annotation, providing early error detection and ensuring proper caching implementation.

## Overview

This processor automatically validates methods annotated with `@CacheX` during compilation, catching common mistakes before runtime and providing clear error messages to developers.

## Features

- **Compile-Time Validation**: Catches errors during compilation, not at runtime
- **Method Modifier Validation**: Ensures methods can be properly proxied by Spring AOP
- **SpEL Expression Validation**: Validates syntax and parameter references in cache keys
- **Clear Error Messages**: Provides descriptive compilation errors with specific guidance

## How It Works

The processor is automatically registered and runs during compilation when the `@CacheX` annotation is detected. It performs the following validations:

### 1. Annotation Target Validation
Ensures `@CacheX` is only applied to methods (not classes, fields, etc.).

### 2. Method Modifier Validation
Validates that annotated methods are not:
- `private` - Cannot be proxied by Spring AOP
- `static` - No instance context for caching
- `final` - Cannot be overridden by proxies

### 3. SpEL Expression Validation
- **Syntax Validation**: Ensures the SpEL expression can be parsed
- **Parameter Validation**: Verifies that all referenced parameters exist in the method signature

## Usage

The processor is automatically included when you use the Caching-X library. No additional configuration is required.

```xml
<dependency>
    <groupId>com.pedromossi</groupId>
    <artifactId>caching-x</artifactId>
    <version>0.0.3</version>
</dependency>
```

## Benefits

1. **Early Error Detection**: Catches issues at compile time instead of runtime
2. **Better Developer Experience**: Clear error messages guide proper usage
3. **Prevents Common Mistakes**: Automatically validates method modifiers and SpEL expressions
4. **Zero Configuration**: Works automatically when the library is included
5. **IDE Integration**: Errors appear directly in your IDE during development

## Technical Details

- **Processor**: `com.pedromossi.caching.processor.CacheXProcessor`
- **Supported Java Version**: Java 21+
- **Dependencies**: Spring Expression Language (SpEL) for validation
- **Registration**: Automatic via `@AutoService` annotation

The processor is built using the Java Annotation Processing API and integrates seamlessly with Maven, Gradle, and IDEs that support annotation processing.
