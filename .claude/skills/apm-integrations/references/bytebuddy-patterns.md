# ByteBuddy Advice Patterns (R1-R14)

Complete reference for ByteBuddy Advice class constraints and patterns in dd-trace-java. Each rule below is enforced — violations cause muzzle failures, runtime errors, or silent breakage. For each rule, read the cited reference file in `dd-java-agent/instrumentation/` to see the rule applied in production code.

## Critical Constraints (R1-R5)

### R1: No Lambdas in Advice Classes

Lambda expressions inside Advice methods create synthetic classes via `invokedynamic`. These classes are not declared in `helperClassNames()` and break when the Advice is inlined into the target method, causing runtime `NoClassDefFoundError`.

**Use plain `for` loops or anonymous inner classes (declared in `helperClassNames()`)** instead. Reference: any reference Advice in `dd-java-agent/instrumentation/okhttp-3/src/main/java/.../` — none use lambdas.

### R2: No Logger Fields in Advice

Loggers must only be declared in helper classes or decorators, never in Advice classes themselves. A `Logger` field on an Advice class causes NPE or class-loading issues at instrumentation time because the Advice class is inlined into the target.

**Pattern**: declare the logger on a helper class (e.g., `FooHelper`), call `FooHelper.logEntry(...)` from the Advice. Reference: `dd-java-agent/instrumentation/jdbc/src/main/java/.../JDBCDecorator.java` — logger lives on the decorator, not the advice.

### R3: One InstrumenterModule Per Integration

Each integration has exactly one `InstrumenterModule`. For multiple incompatible library versions, create separate submodules (`okhttp-2/`, `okhttp-3/`, `apache-httpclient-4.0/`, `apache-httpclient-5.0/`). Each submodule has its own `InstrumenterModule`, registered in `settings.gradle.kts`.

Reference: `dd-java-agent/instrumentation/okhttp-2/` and `dd-java-agent/instrumentation/okhttp-3/` show the version-split layout.

### R4: Declare All Helpers

Every class referenced from Advice code must be declared in `InstrumenterModule.helperClassNames()`, including:
- Inner classes — `com.example.Outer$Inner`
- Anonymous classes — `com.example.Foo$1`
- Synthetic enum classes
- All transitively referenced classes

**Symptom of violation**: muzzle fails with "missing type", or runtime `NoClassDefFoundError`. **Fix**: trace every type the Advice + its helpers touch and add it.

Reference: `helperClassNames()` in any reference InstrumenterModule (e.g., `dd-java-agent/instrumentation/kafka-clients-0.11/.../KafkaProducerInstrumentation.java`).

### R5: Thread Safety with CallDepthThreadLocalMap

Use `CallDepthThreadLocalMap` to prevent duplicate spans on recursive or reentrant calls. The pattern: increment depth on enter, return early if depth > 0, reset on exit.

```java
int callDepth = CallDepthThreadLocalMap.incrementCallDepth(MyClass.class);
if (callDepth > 0) return null;  // already instrumenting; bail out
// ... start span, return scope ...
```

On exit: `CallDepthThreadLocalMap.reset(MyClass.class)` before finishing the span.

Reference: `dd-java-agent/instrumentation/jdbc/src/main/java/.../StatementInstrumentation.java` — full enter/exit pattern with CallDepth, span lifecycle, and error handling.

**Caveat**: do NOT use `CallDepthThreadLocalMap` across async boundaries — it's a thread-local, the async continuation runs on a different thread.

## Span Lifecycle Rules (R6-R8)

### R6: Always Finish Spans

Every span created in `@Advice.OnMethodEnter` must be finished in `@Advice.OnMethodExit`. Missing `span.finish()` causes memory leaks and the spans never reach the agent.

The exit-method pattern is fixed:

```java
DECORATE.beforeFinish(span);
span.finish();
scope.close();
```

Reference: any `*Advice.java` in the okhttp-3 / jdbc / kafka-clients-0.11 reference integrations — the order is the same in all of them.

### R7: Activate Spans for Context Propagation

Spans must be activated via `activateSpan(span)` and the returned `AgentScope` returned from `@Advice.OnMethodEnter`. Without activation, distributed tracing context (trace-id, parent-id) doesn't propagate to nested operations.

Pattern: `startSpan() → DECORATE.afterStart() → activateSpan() → return scope`. See R8 for the full enter+exit flow.

### R8: Span Lifecycle Order

Strict ordering — violating it causes spans without errors, errors without spans, or both:

**Enter**: `startSpan()` → `DECORATE.afterStart(span)` → `activateSpan(span)`
**Exit**: `DECORATE.onError(span, throwable)` (if non-null) → `DECORATE.beforeFinish(span)` → `span.finish()` → `scope.close()`

Always call `DECORATE.onError(span, throwable)` BEFORE `beforeFinish` when `@Advice.Thrown Throwable throwable` is non-null. Tagging errors after `beforeFinish` is too late — the span is already being prepared for export.

Reference: `dd-java-agent/instrumentation/okhttp-3/src/main/java/.../OkHttp3Advice.java` — canonical lifecycle in production.

## Muzzle and Type Safety (R9)

### R9: Correct Muzzle References

Every type, field, and method referenced in Advice code must exist in the muzzle-validated version range. Otherwise: runtime `NoSuchMethodError` or `NoSuchFieldError` when the integration loads against a real version.

In `build.gradle`:

```groovy
muzzle {
  pass { group = "..."; module = "..."; versions = "[1.0,)"; assertInverse = true }
  fail { group = "..."; module = "..."; versions = "[,1.0)" }  // for incompatible older versions
}
```

`assertInverse = true` ensures versions outside the range FAIL muzzle, catching range mistakes early.

Reference: `dd-java-agent/instrumentation/okhttp-3/build.gradle`, `apache-httpclient-4.0/build.gradle` for typical muzzle blocks.

## Test and Build Rules (R10-R14)

### R10: Test Recursive Call Protection

If your integration uses `CallDepthThreadLocalMap` (R5), include a test that exercises a recursive/reentrant call path and asserts a single span (not duplicates). Without this test, R5 violations regress silently.

Reference: search reference integrations' `*Test.groovy` for `recursive` or `nested` test cases.

### R11: Declare Helpers or Runtime Fails

A common false-pass: tests pass (testing-jar provides the helper) but the integration fails in real apps with `NoClassDefFoundError`. Cause: helper class not declared in `helperClassNames()`. Fix: add ALL transitively referenced classes including inner/anonymous/synthetic classes (R4).

The most reliable check: enable muzzle's strict mode locally and run `./gradlew :dd-java-agent:instrumentation:<your-module>:muzzle`. Missing helpers fail muzzle here.

### R12: Correct Span Kind

| Integration type | Span kind |
|---|---|
| HTTP clients | `Span.CLIENT` |
| HTTP servers | `Span.SERVER` |
| Databases | `Span.CLIENT` |
| Messaging producers | `Span.PRODUCER` |
| Messaging consumers | `Span.CONSUMER` |
| Internal | `Span.INTERNAL` |

Set in the decorator's `spanKind()` override. Reference: each base decorator (`HttpClientDecorator`, `DatabaseClientDecorator`, etc.) in `dd-java-agent/agent-bootstrap/src/main/java/.../decorator/`.

### R13: Required Tags Per APM Feature

| Feature | Required tags |
|---|---|
| Service Catalog | `component`, `span.kind` |
| HTTP clients | `http.method`, `http.url` or `http.route`, `http.status_code`, `peer.service` |
| Databases | `db.type`, `db.instance`, `db.statement` (if DBM enabled) |
| Messaging | `messaging.system`, `messaging.destination`, `messaging.operation` |

Tags are usually set by the base decorator (`HttpClientDecorator.onRequest()`, `DatabaseClientDecorator.onConnection()`, etc.) — your job is to choose the right base class (R12) and override the data-extraction methods.

### R14: Spotless Formatting

```bash
./gradlew spotlessCheck   # verify
./gradlew spotlessApply   # auto-fix
```

CI fails on formatting violations. Run `spotlessApply` before committing.
