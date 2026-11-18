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

### 4. CompletableFuture callback exclusions

The `CompletableFuture` instrumentation normally captures a continuation when a completion is created,
then activates it while the callback runs. An agent-owned callback that only finishes a stored span,
or manages its own context, may not need that extra continuation. Register its class under
`ExcludeFilter.ExcludeType.COMPLETABLE_FUTURE_CALLBACK` to skip capture.

Only exclude callbacks whose tracing behavior is independent of the context active at registration.
If a callback invokes application code, ensure it activates the required context itself before
excluding it. The exclusion does not suppress callback execution or finish spans on its behalf.

**Registration:**

Implement `ExcludeFilterProvider` on the owning `InstrumenterModule` and return the callback's binary
class name from `excludedClasses()`. For example, `Resilience4jModule` registers its named helper:

```java
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.COMPLETABLE_FUTURE_CALLBACK;
import static java.util.Collections.singleton;
import static java.util.Collections.singletonMap;

@Override
public Map<ExcludeFilter.ExcludeType, ? extends Collection<String>> excludedClasses() {
  return singletonMap(
      COMPLETABLE_FUTURE_CALLBACK,
      singleton(packageName + ".WrapperWithContext$SpanFinishingCallback"));
}
```

Use a named callback class so its name is stable. Registration matches the callback's exact runtime
class name; subclasses and sibling classes are not implicitly excluded. Register the callback passed
to methods such as `whenComplete` or `thenApply`, rather than the future or a JDK completion class.
This category is separate from `RUNNABLE` and `FORK_JOIN_TASK` exclusions.

The filter is checked after the JDK completion's `fn` field is initialized. Completions without a
callback, such as internal relay nodes, retain their normal capture behavior. Other callbacks,
including application callbacks registered downstream, still capture their own context.

**Verification:**

Check that the excluded callback captures no continuation and still finishes its span correctly.
Cover completion on another thread, success, failure and cancellation, and async variants where used.
Verify that ordinary downstream callbacks retain their registration context and that no scope leaks
remain. The [callback exclusion tests](../dd-java-agent/instrumentation/java/java-concurrent/java-concurrent-1.8/src/test/groovy/CompletableFutureCallbackExclusionTest.groovy)
cover the shared behavior, including exact-name matching and callbacks that throw.
