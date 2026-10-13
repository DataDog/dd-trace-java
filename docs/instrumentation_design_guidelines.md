# Instrumentation and Advice Design Guidelines

This document outlines design constraints and best practices when writing or refactoring instrumentation and advice code.
Following these guidelines will help avoid constant-pool bloat and subtle span/scope lifecycle bugs.

## Background

Instrumentation classes wire up type/method matchers and advice once, when the framework loads them. Advice methods
are inlined into the instrumented application's bytecode and run on every invocation of the matched method, so their
ordering directly affects when spans are visible to nested work.

## Constraints to Follow

### 1. Instrumentation one-shot methods

**Why to avoid extracting to constants:**

- `triggerClasses()`, `contextStore()`, `classLoaderMatcher()`, and `methodAdvice()` are called exactly once by the
  framework when the instrumentation is registered
- Extracting their return values into static constants adds constant-pool bloat with no runtime benefit

**What to do instead:**

Return the values directly from the method; don't cache them in a field.

```java
// BAD - unnecessary static constant
private static final ElementMatcher.Junction<ClassLoader> CL_MATCHER = hasClassesNamed("foo.Bar");

@Override
public ElementMatcher.Junction<ClassLoader> classLoaderMatcher() {
  return CL_MATCHER;
}

// GOOD - construct directly in the one-shot method
@Override
public ElementMatcher.Junction<ClassLoader> classLoaderMatcher() {
  return hasClassesNamed("foo.Bar");
}
```

### 2. Scope lifecycle order

**Why to avoid closing the scope early:**

- Keeping the scope open until all work needing the current active span is done ensures decorator calls and async
  callback registration see the span as active
- Closing the scope before that work runs can make nested code lose track of the current span

**What to do instead:**

Required order: decorator calls and callback registration, then `scope.close()`, then `span.finish()`.

```java
// GOOD
DECORATE.onOperation(span, request);
registerCallback(span);
scope.close();
span.finish();
```

### 3. Static interface methods in advice

**Why to avoid calling them directly:**

- Advice methods (`@Advice.OnMethodEnter`/`@Advice.OnMethodExit`) are inlined into the instrumented class's bytecode
- Calling a static interface method there, such as `Context.current()` or `Context.root()`, can cause a
  `VerifyError` at runtime

**What to use instead:**

Use `Java8BytecodeBridge` (`currentContext()`, `rootContext()`, etc.) in place of the static interface method,
static-imported for readability.

```java
import static datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge.currentContext;

// BAD - static interface method call, can cause VerifyError when inlined
Context ctx = Context.current();

// GOOD - use the bytecode bridge, static-imported
Context ctx = currentContext();
```
