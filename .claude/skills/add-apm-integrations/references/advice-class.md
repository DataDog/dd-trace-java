# Writing the Advice Class

> Referenced from `SKILL.md` Step 7. The highest-risk step — every rule in this file exists because someone's PR broke on it.

## Must do

- Advice methods **must** be `static`
- Annotate enter: `@Advice.OnMethodEnter(suppress = Throwable.class)`
- Annotate exit: `@Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)`
  - **Exception**: do NOT use `suppress` when hooking a constructor
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
6. `span.finish()`
7. `scope.close()`

### onExit must be resilient to onEnter throwing

If `onEnter` throws before the scope is set, `onExit` must still decrement the call depth.
A null-check that skips the reset leaks the ThreadLocal:

```java
// RISKY — if onEnter threw, scope is null and reset is skipped
@Advice.OnMethodExit(suppress = Throwable.class)
public static void exit(@Advice.Enter final AgentScope scope) {
    if (scope != null) scope.close();
}

// SAFER — onThrowable = Throwable.class ensures exit fires even on onEnter exception
@Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
public static void exit(@Advice.Enter final AgentScope scope) {
    if (scope != null) {
        scope.close();
    }
}
```

When using `CallDepthThreadLocalMap`, always decrement unconditionally in exit.

### Specify charset explicitly when converting byte[] to String

```java
// WRONG — uses platform default charset
String cmd = new String(commandBytes);

// CORRECT — explicit charset
import java.nio.charset.StandardCharsets;
String cmd = new String(commandBytes, StandardCharsets.UTF_8);
```

### Do NOT catch `NullPointerException`; use null-check guards instead

dd-trace-java enforces SpotBugs rule `DCN_NULLPOINTER_EXCEPTION` (no NPE catch). Defensive `try { ... } catch (NullPointerException e) { ... }` patterns will fail `:spotbugsMain` and block the PR.

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

If your instrumentation needs to apply multiple advices to the same method (e.g. separate context-tracking from tracing logic), use `applyAdvices()` instead of `applyAdvice()`:

```java
@Override
public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvices(
            named("someMethod")
                    .and(takesArgument(0, named("com.example.Request")))
                    .and(takesArgument(1, named("com.example.Response"))),
            getClass().getName() + "$ContextTrackingAdvice",  // Applied first
            getClass().getName() + "$ServiceAdvice"           // Applied second
    );
}
```

Use the `@AppliesOn` annotation to control which target systems each advice applies to:

```java
import datadog.trace.agent.tooling.InstrumenterModule.TargetSystem;
import datadog.trace.agent.tooling.annotation.AppliesOn;

@AppliesOn(TargetSystem.CONTEXT_TRACKING)
public static class ContextTrackingAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void enter(@Advice.Argument(0) Request request) {
        // This advice only runs when CONTEXT_TRACKING is enabled
    }
}

public static class TracingAdvice {
    // Without @AppliesOn, this advice runs for the module's target system
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void enter(@Advice.Argument(0) Request request) {
        // Tracing-specific logic
    }
}
```

**When to use `@AppliesOn`:**

- Separate context-propagation logic from tracing logic
- Different target systems need different instrumentation behaviours
- Multiple advices apply to the same method with different system requirements

See `docs/how_instrumentations_work.md` section "@AppliesOn Annotation" for complete details.

## Must NOT do

- **No logger fields** in the Advice class or the Instrumentation class (loggers only in helpers/decorators)
- **No code in the Advice constructor** — it is never called
- **Do not use lambdas in advice methods** — they create synthetic classes that will be missing from helper declarations
- **No references** to other methods in the same Advice class or in the InstrumenterModule class
- **No `InstrumentationContext.get()`** outside of Advice code
- **No `inline=false`** in production code (only for debugging; must be removed before committing)
- **No `java.util.logging.*`, `java.nio.*`, or `javax.management.*`** in bootstrap instrumentations
