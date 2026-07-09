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
