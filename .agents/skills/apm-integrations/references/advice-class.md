# Writing the Advice Class

> Referenced from `SKILL.md` Step 7. The highest-risk step — every rule in this file exists because someone's PR broke on it.

## Must do

- Advice methods **must** be `static`
- Annotate enter: `@Advice.OnMethodEnter(suppress = Throwable.class)`
- Annotate exit: `@Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)`
  - **Exception**: do NOT use `onThrowable` when hooking a constructor — if the constructor throws, `@Advice.This` is a partially initialized object
- Use `@Advice.Local("...")` for values shared between enter and exit (span, scope)
- Use the correct parameter annotations:
  - `@Advice.This` — the receiver object
  - `@Advice.Argument(N)` — a method argument by index
  - `@Advice.Return` — the return value (exit only)
  - `@Advice.Thrown` — the thrown exception (exit only)
  - `@Advice.Enter` — the return value of the enter method (exit only)
- Use `CallDepthThreadLocalMap` to guard against recursive instrumentation of the same method
- **Instrument the single delegate method, not all overloads**: when a library has multiple overloads of the same operation (e.g. `executeMethod(String)`, `executeMethod(HostConfig)`, `executeMethod(HostConfig, HttpMethod)`), check if they all delegate to a single internal method. If yes, instrument ONLY the delegate — not each overload. Instrumenting all overloads without a proper reentrancy guard creates **duplicate spans per request** (one per overload in the call chain) and injects context propagation headers multiple times. Use `CallDepthThreadLocalMap` when you must instrument at a higher level.

## Span lifecycle (in order)

Enter method:
1. `AgentSpan span = startSpan(DECORATE.operationName(), ...)`
2. `DECORATE.afterStart(span)` + set domain-specific tags
3. `AgentScope scope = activateSpan(span)` — return or store via `@Advice.Local`

Exit method:
4. `DECORATE.onError(span, throwable)` — only if throwable is non-null
5. `DECORATE.beforeFinish(span)`
6. `scope.close()`
7. `span.finish()`

### onExit handling when the target method throws

The `onThrowable = Throwable.class` attribute on `@Advice.OnMethodExit` controls whether the exit advice fires when the **instrumented target method** throws. You **must** set it explicitly to `Throwable.class` for any exit advice that closes a scope or finishes a span — the default skips exceptional termination, which leaks active scopes when the instrumented method throws.

```java
// Standard pattern — exit fires whether the target method returned or threw
@Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
public static void exit(
    @Advice.Enter final AgentScope scope,
    @Advice.Thrown final Throwable thrown) {
  if (scope != null) {
    AgentSpan span = scope.span();
    DECORATE.onError(span, thrown);
    DECORATE.beforeFinish(span);
    scope.close();
    span.finish();
  }
}
```

**`onThrowable` does NOT compensate for `onEnter` throwing.** Per `docs/how_instrumentations_work.md`: "If the `Advice.OnMethodEnter` method throws an exception, the `Advice.OnMethodExit` method is not invoked" — this is unconditional. To keep `onEnter` from throwing in the first place, use `suppress = Throwable.class` on the enter advice.

When using `CallDepthThreadLocalMap`, only the outermost call (the one where `incrementCallDepth` returned 0) should reset the counter. Recursive inner calls that returned early on enter must also return early on exit without resetting — otherwise an inner exit clears the counter while the outer call is still active, allowing subsequent nested calls to create duplicate spans. The exit guard must mirror the enter guard exactly.

### Specify charset explicitly when converting byte[] to String

```java
// WRONG — uses platform default charset
String cmd = new String(commandBytes);

// CORRECT — explicit charset
import java.nio.charset.StandardCharsets;
String cmd = new String(commandBytes, StandardCharsets.UTF_8);
```

### Do NOT catch `NullPointerException`; use null-check guards instead

Catching `NullPointerException` is always a sign of an unguarded precondition — fix the root cause with an explicit null check instead. dd-trace-java enforces this via SpotBugs rule `DCN_NULLPOINTER_EXCEPTION`; violations fail `:spotbugsMain` and block the PR.

```java
// WRONG — SpotBugs DCN_NULLPOINTER_EXCEPTION
@Override
protected int status(final HttpMethod httpMethod) {
  try {
    return httpMethod.getStatusCode();
  } catch (NullPointerException e) {
    // getStatusCode() throws NPE when statusLine is null
    return 0;
  }
}

// CORRECT — null-check guard (this is the canonical master pattern)
@Override
protected int status(final HttpMethod httpMethod) {
  final StatusLine statusLine = httpMethod.getStatusLine();
  return statusLine == null ? 0 : statusLine.getStatusCode();
}
```

**How to discover**: when implementing a method that calls library code which may NPE on null internal state, READ the master module's analogous method for the canonical null-check pattern. The master typically exposes the nullable intermediate (e.g. `getStatusLine()`) so you can guard it.

### Do not double-span async HTTP clients

If the target method delegates to a sync client that is already instrumented (common in async-wrapper classes like `AsyncFeignClient`, `AsyncHttpClient`, etc.), do NOT open a second span in the async wrapper. The sync client's advice already opens the client span; wrapping again produces two spans per request, with the outer span holding no additional context.

Before adding advice to an async wrapper, trace the call path to the sync delegate. If the delegate is already instrumented for span emission, the async wrapper only needs context-propagation advice — not a second span. But note that "context-propagation only" is more nuanced than a completion-callback:

**If the sync delegate runs on a worker/executor thread** (the common shape for `AsyncXxxClient`), completion-only propagation is insufficient. The sync client's advice creates its HTTP span while the worker executes — BEFORE the future completes — so a "restore context on completion" callback runs too late; the sync span would emit as a root or under the wrong context. The wrapper's propagation advice needs to either:

1. **Rely on executor instrumentation** — if the worker is scheduled via a `java.util.concurrent.Executor`/`ExecutorService` and the toolkit's `java-concurrent-1.8` module wraps it, context propagates automatically. No additional wrapper advice needed. Verify by reading the wrapper's submission code and confirming the executor is one the toolkit instruments.

2. **Reactivate around the delegate submission** — wrap the `Runnable`/`Callable` submitted to the worker so it opens a scope with the captured context before invoking the sync call. This is the pattern used by `java-concurrent-1.8`'s wrappers. Do NOT reinvent this per client — factor into a shared helper.

The completion callback advice (whenComplete-style) is still useful for span-close cleanup on the caller's future, but it does not by itself guarantee the sync client's advice sees the right parent. See `context-tracking.md` for the propagation patterns and the specific `readOnly`/lambda constraints.

## Multiple advice classes and `@AppliesOn`

If your instrumentation needs to apply multiple advices to the same method (e.g. separate context-tracking from tracing logic), use `applyAdvices()` inside `methodAdvice()`. Use the `@AppliesOn` annotation to control which target systems each advice applies to.

See the `@AppliesOn Annotation` section of `docs/how_instrumentations_work.md` for the full API and examples.

## Must NOT do

- **No logger fields** in the Advice class or the Instrumentation class (loggers only in helpers/decorators)
- **No code in the Advice constructor** — it is never called
- **Do not use lambdas in advice methods** — they create synthetic classes that will be missing from helper declarations
- **No references** to other methods in the same Advice class or in the InstrumenterModule class
- **No `InstrumentationContext.get()`** outside of Advice code
- **No `inline=false`** in production code (only for debugging; must be removed before committing)
- **No `java.util.logging.*`, `java.nio.file.*`, or `javax.management.*`** in bootstrap instrumentations
- **Do not extract advice logic into a helper class just to shorten the advice body.** Advice methods are inlined by ByteBuddy; extracting into `SomethingHelper.doTheThing(...)` adds a static-method hop, an extra file, and misleads reviewers into thinking the helper is shared when it is used by exactly one advice. Keep advice inline unless the same logic is genuinely shared across multiple advice classes. When it IS shared, the helper belongs in `helperClassNames()` and named accordingly (e.g. `TracingUtils`, not `FooBarHelper`). The CallDepth helper-class carveout (see `instrumenter-module.md`) is a separate case for multi-type instrumentations.

### Route-only enrichers: enrich the outer span, do not replace it

**Scope:** this rule applies specifically to instrumentations that only observe **route matching / dispatch decisions** inside an outer HTTP server — the SparkJava case. It does NOT apply to frameworks that own a handler/controller span in addition to the outer server span.

**Applies to (route-only enrichers):**
- SparkJava — see `dd-java-agent/instrumentation/spark/sparkjava-2.3/.../RoutesInstrumentation.java`

**Does NOT apply to (frameworks that own a handler/controller span):**
- JAX-RS annotations — `dd-java-agent/instrumentation/rs/jax-rs/jax-rs-annotations/jax-rs-annotations-2.0/.../JaxRsAnnotationsInstrumentation.java:128` legitimately calls `startSpan(JAX_RS_CONTROLLER.toString(), ...)`
- Ratpack — `dd-java-agent/instrumentation/ratpack-1.5/.../TracingHandler.java:41` legitimately calls `startSpan("ratpack", ...)` and relies on executor instrumentation to keep the outer Netty span as its parent
- Any other framework that owns a per-handler span (Spring MVC controllers, Vert.x routes, Micronaut route handlers, etc.)

**How to tell:** if the framework's expected trace shape has a per-request/per-handler span in addition to the outer HTTP server span, it OWNS that span — do not apply this rule. If the framework only decorates the outer span with a matched-route tag, it is a route-only enricher — apply this rule.

**For route-only enrichers only:** the outer server's instrumentation already opened the request span (typically `servlet.request` or `jetty-server`). Do NOT create a new `Decorator`, rename the active span, or overwrite its component tag from inside the route-matcher's advice. Enrich the active span with the matched route only. `HTTP_RESOURCE_DECORATOR.withRoute(...)` does NOT guard against a null span, so you MUST null-check before calling it — otherwise the advice NPEs when there is no active span (rare but possible under certain execution paths):

```java
// CORRECT — matches the pattern in dd-java-agent/instrumentation/spark/sparkjava-2.3/.../RoutesInstrumentation.java
final AgentSpan span = activeSpan();
if (span != null && routeMatch != null) {
  HTTP_RESOURCE_DECORATOR.withRoute(span, method.name(), routeMatch.getMatchUri());
}
```

For route-only enrichers: no `AgentScope`, no `startSpan()`, no `decorator.afterStart()`. This rule does NOT apply to standalone HTTP clients (which own their own span identity) or to handler-owning frameworks like JAX-RS / Ratpack (which legitimately create controller spans).

### Advice classes must not declare non-constant static fields

`*Advice.java` classes are inlined at instrumentation sites; non-constant static fields (fields that aren't `static final` primitives or string literals) get pulled into every instrumented callsite and violate muzzle's assumptions. Keep only `static final` constants — no logger references, no cached decorators, no state. If you need shared state, put it on a helper class registered via `helperClassNames()`, not on the advice.
