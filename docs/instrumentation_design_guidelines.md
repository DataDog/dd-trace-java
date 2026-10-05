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

### 4. Values bound into advice

**Why not to assume they are set:**

In advice, treat every bound value except `@Advice.This` as possibly `null`, including in exit advice:

- `@Advice.Enter` and `@Advice.Local`: the enter advice may have returned `null` on purpose (a call-depth guard on a
  nested call, a trace that isn't sampled, a disabled feature), or it may have thrown, in which case
  `suppress = Throwable.class` swallowed the exception and the value kept its default, `null`
- `@Advice.Return`: `null` (or `0`) when the instrumented method threw
- `@Advice.Thrown`: `null` when the method returned normally
- `@Advice.Argument` and `@Advice.FieldValue`: the application can pass or store `null`, whatever the library's
  contract says

`@Advice.This` is the exception, and even it is only partially initialized in a constructor (see
[how instrumentations work](how_instrumentations_work.md)).

Suppression hides the resulting `NullPointerException`, but not its effect: everything after it in the advice is
skipped. A span that is never finished, a scope that is never closed (leaking context into whatever the thread does
next), or a call depth that is never reset (which can silently turn the instrumentation off for that thread) all
follow from one unexpected `null` or one throwing decorator.

**What to do instead:**

- Check `@Advice.Enter` and `@Advice.Local` values for `null` before using them, and return early.
- Keep the order from rule 2, but put the bookkeeping (`scope.close()`, `span.finish()`, resetting a call depth) in a
  `finally`, so a failure in a decorator can't skip it.
- If the enter advice increments a call depth before work that can fail, make sure the exit advice still resets it
  when the enter advice failed, not only when it produced a scope.

```java
// BAD - assumes the enter advice ran and produced a scope, and lets a decorator failure skip the cleanup
@Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
public static void onExit(
    @Advice.Enter final ContextScope scope, @Advice.Thrown final Throwable throwable) {
  final AgentSpan span = spanFromScope(scope);
  DECORATE.onError(span, throwable);
  DECORATE.beforeFinish(scope.context());
  scope.close();
  span.finish();
}

// GOOD - returns early when there's nothing to close, and always closes and finishes what was opened
@Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
public static void onExit(
    @Advice.Enter final ContextScope scope, @Advice.Thrown final Throwable throwable) {
  if (scope == null) {
    return;
  }
  final AgentSpan span = spanFromScope(scope);
  try {
    DECORATE.onError(span, throwable);
    DECORATE.beforeFinish(scope.context());
  } finally {
    scope.close();
    span.finish();
  }
}
```
