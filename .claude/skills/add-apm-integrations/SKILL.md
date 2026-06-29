---
name: add-apm-integrations
description: Write a new library instrumentation end-to-end. Use when the user ask to add a new APM integration or a library instrumentation.
context: fork
allowed-tools:
  - Bash
  - Read
  - Write
  - Edit
  - Glob
  - Grep
---

Write a new APM end-to-end integration for dd-trace-java, based on library instrumentations, following all project conventions.

## Step 1 – Read the authoritative docs and sync this skill (mandatory, always first)

Before writing any code, read all three files in full:

1. [`docs/how_instrumentations_work.md`](docs/how_instrumentations_work.md) — full reference (types, methods, advice, helpers, context stores, decorators)
2. [`docs/add_new_instrumentation.md`](docs/add_new_instrumentation.md) — step-by-step walkthrough
3. [`docs/how_to_test.md`](docs/how_to_test.md) — test types and how to run them

These files are the single source of truth. Reference them while implementing.

**After reading the docs, sync this skill with them:**

Compare the content of the three docs against the rules encoded in Steps 2–11 of this skill file. Look for:
- Patterns, APIs, or conventions described in the docs but absent or incorrect here
- Steps that are out of date relative to the current docs (e.g. renamed methods, new base classes)
- Advice constraints or test requirements that have changed

For every discrepancy found, edit this file (`.claude/skills/apm-integrations/SKILL.md`) to correct it using the
`Edit` tool before continuing. Keep changes targeted: fix what diverged, add what is missing, remove what is wrong.
Do not touch content that already matches the docs.

## Step 2 – Clarify the task

If the user has not already provided all of the following, ask before proceeding:

- **Framework name** and **minimum supported version** (e.g. `okhttp-3.0`)
- **Target class(es) and method(s)** to instrument (fully qualified class names preferred)
- **Target system**: one of `Tracing`, `Profiling`, `AppSec`, `Iast`, `CiVisibility`, `Usm`, `ContextTracking`
- **Whether this is a bootstrap instrumentation** (affects allowed imports)

## Step 3 – Find a reference instrumentation

Search `dd-java-agent/instrumentation/` for a structurally similar integration:
- Same target system
- Comparable type-matching strategy (single type, hierarchy, known types)

Read the reference integration's `InstrumenterModule`, Advice, Decorator, and test files to understand the established
pattern before writing new code. Use it as a template.

## Step 4 – Set up the module

1. Create directory: `dd-java-agent/instrumentation/$framework/$framework-$minVersion/`
2. Under it, create the standard Maven source layout:
   - `src/main/java/` — instrumentation code
   - `src/test/groovy/` — Spock tests
3. Create `build.gradle` with:
   - `compileOnly` dependencies for the target framework
   - `testImplementation` dependencies for tests
   - `muzzle { pass { } }` directives (see Step 9)
4. Register the new module in `settings.gradle.kts` in **alphabetical order**
5. Register the integration name in `metadata/supported-configurations.json` (see R29 below in Step 9), or
   `checkInstrumenterModuleConfigurations` fails. The name in `super(...)` maps to env var
   `DD_TRACE_<NAME>_ENABLED` (`.` and `-` become `_`, uppercased — `couchbase-3` →
   `DD_TRACE_COUCHBASE_3_ENABLED`). Add a `"type": "boolean"` entry, in alphabetical order, with
   aliases `DD_TRACE_INTEGRATION_<NAME>_ENABLED` and `DD_INTEGRATION_<NAME>_ENABLED`. Set `default`
   to the module's real default — `"true"`, or `"false"` if it overrides `defaultEnabled()` (e.g.
   OpenTelemetry, Hazelcast). Declaring several names (`super("a", "b")`) means one entry each.

#### R32 — Module directory name must end with a version OR an allowed suffix

dd-trace-java's `dd-gitlab/check-instrumentation-naming` plugin
(`buildSrc/.../naming/InstrumentationNamingPlugin.kt`) enforces:

> Module name must end with a version (e.g., `2.0`, `3.1`) OR one of: `-common`, `-stubs`, `-iast`

Pick a directory name like `$framework-$minVersion` (e.g. `okhttp-3.0`, `jedis-3.0`). For shared
helpers/stubs/iast support code factored out across version-specific modules, use the documented
suffixes above.

## Step 4.4 – Library category: span-creating vs context-propagation (read BEFORE picking targets)

**Before picking instrumentation targets**, classify the library along the `target_kind` axis:

**Category A — span-creating** (most libraries): performs I/O, makes calls, runs queries. The instrumentation creates spans around those operations.

- HTTP clients/servers, DB clients, messaging clients, RPC frameworks
- Targets: methods that perform the I/O (e.g., `Connection.sendCommand`, `Client.execute`)
- Tests: assert spans exist with correct tags

**Category B — context-propagation** (reactive, async, threading, executor, fiber libraries): does NOT perform I/O directly. It coordinates work that other code performs. Instrumentation captures the active trace context at boundary creation and restores it at boundary crossing — **no spans are created by the module**, it only bridges trace context for spans created by other instrumentations or by user code.

- RxJava, Reactor, CompletableFuture, ListenableFuture, executors, pekko/akka, lettuce async-command queue, ZIO, virtual threads
- Targets: one boundary-crossing type (e.g. `Observable`, `Flowable`, `Single`, `Maybe`, `Completable` for RxJava-shaped libs; `Runnable`/`Callable` for executor-shaped libs)
- Tests: assert that a span created in operation X is still ACTIVE when a callback scheduled by Y runs (parent-child bridging of *user-created* spans, NOT span tags on a target span)

**Reference implementation for Category B:**

`dd-java-agent/instrumentation/rxjava/rxjava-2.0/` — uses `InstrumenterModule.ContextTracking`. 5 type-instrumenters (Observable, Flowable, Single, Maybe, Completable), 5 wrappers (Tracing{Observer,Subscriber,SingleObserver,MaybeObserver,CompletableObserver}), 1 `RxJavaModule.java`, 1 `RxJavaAsyncResultExtension.java`. ~600 LOC total.

### Category B target shape

For each boundary-crossing type, capture:

- `library_class` — FQN of the boundary-crossing type (e.g. `io.reactivex.rxjava3.core.Observable`).
- `capture_method` — capture point (usually the constructor — `<init>` or `isConstructor()`).
- `restore_method` — restore point (usually `subscribe(Observer)` / `subscribe(Subscriber)`).
- `wrapped_argument_type` — FQN of the user callback argument that must be wrapped (e.g. `io.reactivex.rxjava3.core.Observer`).
- `context_key_class` — FQN to key the `contextStore` on (almost always equals `library_class`).
- `wrapper_class_name` — class name of the wrapper to generate (e.g. `TracingObserver`).
- `wrapper_methods` — methods on the wrapped type that must reattach context before delegating (e.g. `["onNext", "onError", "onComplete"]`).

### Tests for Category B

Context-propagation tests assert that **a span created by user code inside the wrapped callback becomes a child of the parent span active when the boundary was created**:

```java
runUnderTrace("parent", () -> {
    constructBoundary()                                  // capture happens here
        .subscribe(item -> userCodeStartsAChildSpan());  // restore happens around the lambda
});
// Assert: 1 trace, 2 spans — child has parent's spanId as parentId.
```

Do NOT assert span kinds, operation names, span tags, or span types on the *target* methods — there are no spans on those methods.

### Choosing between method overloads (Category B)

When a reactive boundary type exposes multiple overloads of the subscribe / invoke method — e.g. `Flowable.subscribe(Subscriber)` vs `Flowable.subscribe(FlowableSubscriber)`, or `Mono.subscribe(Subscriber)` vs `Mono.subscribe(CoreSubscriber)` — hook the **most specific framework-internal interface**, not the public wrapper.

**Why:** the public overload typically delegates to the internal one. Hooking the public overload causes double-wrapping (every subscription flows through the wrapper twice) and the internal overload sees a wrapped argument of the wrong runtime type.

**How to identify the right overload:** read the framework source. The public method usually calls a `subscribeActual(...)` or similar protected method that takes the framework-internal interface. If the framework documents one of the overloads as "for internal use only" or marks it `public final`, that's the implementation method — hook it.

**Reference:** dd-trace-java's `rxjava-2.0` hooks `subscribe(Observer)` (the implementation). The RxJava 3 reference instrumentation hooks `subscribe(FlowableSubscriber)`, not `subscribe(Subscriber)`.

### When NOT to use Category B

If the library DOES perform I/O — sends HTTP requests, runs DB queries, makes RPC calls, talks to a broker, reads/writes a cache — it is **span-creating**, not context-propagation.

Hybrid libraries that BOTH coordinate work AND perform I/O usually get one span-creating instrumentation for the I/O path and (optionally) one context-propagation instrumentation for the coordination path. `lettuce-5.0` is an example: there is a span-creating instrumentation for Redis commands and a separate context-propagation instrumentation for the async command queue.

## Step 4.5 – Java naming consistency (CRITICAL — non-negotiable)

The filename and the declared `public class` name MUST match exactly, character-for-character including case. Java will not compile a file where they differ.

**Pick one canonical name per class, then use that exact string everywhere:**

- The filename (e.g., `JMSDecorator.java`)
- The class declaration inside (e.g., `public class JMSDecorator`)
- Every `import static <pkg>.<ClassName>.<member>` across all other files in the module
- Every reference in the form `<ClassName>.<member>` or `<ClassName>.class`

**Convention in dd-trace-java**: acronym classes use **uppercase**:

- CORRECT: `JMSDecorator`, `gRPCInstrumentation`, `JDBCConnection`
- WRONG: `JmsDecorator`, `GrpcInstrumentation`, `JdbcConnection`

This matches the existing module conventions. When in doubt, match a reference instrumentation's casing exactly (see Step 3).

**After generating each module, sanity-check before declaring done:**

```bash
# Every public class declaration must match its filename
cd dd-java-agent/instrumentation/$framework/$framework-$version/src/main/java
for f in $(find . -name '*.java'); do
  CLS=$(grep -oE 'public (final )?(abstract )?class [A-Za-z0-9_]+' "$f" | awk '{print $NF}')
  EXPECTED=$(basename "$f" .java)
  [ "$CLS" = "$EXPECTED" ] || echo "MISMATCH: $f declares '$CLS'"
done
```

If any MISMATCH lines print, fix them before moving on.

## Step 5 – Write the InstrumenterModule

Conventions to enforce:

- Add `@AutoService(InstrumenterModule.class)` annotation — required for auto-discovery
- Extend the correct `InstrumenterModule.*` subclass (never the bare abstract class)
- Implement the **narrowest** `Instrumenter` interface possible:
  - Prefer `ForSingleType` > `ForKnownTypes` > `ForTypeHierarchy`
  - **EXCEPTION — API specification / interface-only libraries**: when the target library is a specification JAR containing only interfaces (no concrete classes), `ForSingleType` does not work because there are no concrete types to instrument directly. You MUST use `ForTypeHierarchy` with `implementsInterface(named("the.interface.Fqn"))`. This is how vendor implementations of the specification (ActiveMQ, IBM MQ, EclipseLink, Hibernate, etc.) get instrumented through the common interface contract.
  - Common API JARs that REQUIRE `ForTypeHierarchy` + `implementsInterface`:
    - **JMS**: `javax.jms:javax.jms-api`, `jakarta.jms:jakarta.jms-api` — see `dd-java-agent/instrumentation/jms/javax-jms-1.1/` for the canonical example. Targets `MessageProducer`, `MessageConsumer`, `Message`, `MessageListener` interfaces.
    - **JPA**: `javax.persistence:javax.persistence-api`, `jakarta.persistence:jakarta.persistence-api`
    - **JDBC**: `java.sql.*` — interfaces like `Driver`, `Connection`, `Statement`, `PreparedStatement`
    - **JCache**: `javax.cache:cache-api`
    - **Bean Validation**: `jakarta.validation:jakarta.validation-api`
    - **JAX-RS**: `jakarta.ws.rs:jakarta.ws.rs-api`
    - **JAX-WS**: `jakarta.xml.ws:jakarta.xml.ws-api`
    - **Servlet**: `jakarta.servlet:jakarta.servlet-api`
  - **DO NOT classify interface-only API JARs as not_applicable.** They ARE instrumentable via `implementsInterface()`.
- Add `classLoaderMatcher()` if a sentinel class identifies the framework on the classpath
- Declare **all** helper class names in `helperClassNames()`:
  - Include inner classes (`Foo$Bar`), anonymous classes (`Foo$1`), and enum synthetic classes
- Declare `contextStore()` entries if context stores are needed (key class → value class)
- Keep method matchers as narrow as possible (name, parameter types, visibility)

### Must NOT do in InstrumenterModule

- **Do not extract one-shot method return values into static constants.**
  Methods like `triggerClasses()`, `contextStore()`, `classLoaderMatcher()`, and `methodAdvice()`
  are called **once** by `AgentInstaller` / the framework wiring. Extracting their return value
  into a `private static final` constant provides no performance benefit and needlessly bloats
  the constant pool of the instrumentation class.

  ❌ `private static final String[] TRIGGER_CLASSES = new String[]{"com.example.Foo"};`
     `public String[] triggerClasses() { return TRIGGER_CLASSES; }`

  ✅ `public String[] triggerClasses() { return new String[]{"com.example.Foo"}; }`

#### instrumentationNames() must include a version-qualified alias

```java
@Override
public String[] instrumentationNames() {
    // WRONG — only generic name
    return new String[]{"jedis"};

    // CORRECT — generic + version alias
    return new String[]{"jedis", "jedis-3.0"};
}
```

The version alias (e.g. `"jedis-3.0"`) lets users enable/disable this specific version independently.

#### R13 — Do not create a helper class just for CallDepthThreadLocalMap when only one type is instrumented

When only one type is being instrumented, use `CallDepthThreadLocalMap` directly in the Advice class. A separate helper class that just wraps `CallDepthThreadLocalMap.incrementCallDepth` / `decrementCallDepth` adds indirection without value:

```java
// WRONG — pointless wrapper when only one type is instrumented
public class GsonHelper {
    public static boolean shouldSkip() {
        return CallDepthThreadLocalMap.incrementCallDepth(GsonHelper.class) > 0;
    }
    public static void reset() {
        CallDepthThreadLocalMap.reset(GsonHelper.class);
    }
}

// CORRECT — use CallDepthThreadLocalMap directly with the Advice or Decorator class as key
if (CallDepthThreadLocalMap.incrementCallDepth(GsonInstrumentation.class) > 0) return;
// ... in exit:
CallDepthThreadLocalMap.reset(GsonInstrumentation.class);
```

A helper class is appropriate when multiple instrumentation classes share the same depth counter — use the shared sentinel class as the key in that case.

#### R30 — When regenerating an existing module, preserve master's integration name convention

If you are modifying or regenerating instrumentation for a library that **already exists** in `dd-java-agent/instrumentation/` on master (e.g. `commons-httpclient-2.0/`), READ the existing module's `super(...)` and `instrumentationNames()` declarations and reuse them.

```java
// Master's existing source uses dashed name:
//   super("commons-http-client");
// CORRECT (matches master, master's DD_TRACE_COMMONS_HTTP_CLIENT_* entries continue to work)
super("commons-http-client");

// WRONG (new name, requires adding 6 new supported-configurations.json entries)
super("commons-httpclient", "commons-httpclient-2.0");
```

**Why**: dd-trace-java's `dd-gitlab/config-inversion-linter` requires registered names. Master already has registered entries for its existing convention; inventing a new convention forces a metadata change. Preserving the convention keeps the diff minimal and reviewer attention focused on the code, not boilerplate.

### Advanced: Grouping multiple instrumentations under one module

For complex frameworks with multiple version-specific or feature-specific instrumentations, you can group them under a single `InstrumenterModule` (file ending in `Module.java`). The module class:

- Must extend a `TargetSystem` subclass and have `@AutoService(InstrumenterModule.class)`
- Must implement `typeInstrumentations()` returning an array of instrumentations
- Must **not** implement an `Instrumenter` interface
- Member instrumentations must **not** carry `@AutoService` and must **not** extend `TargetSystem` subclasses

See `docs/how_instrumentations_work.md` section "Grouping Instrumentations" for details.

## Step 6 – Write the Decorator

- Extend the most specific available base decorator:
  - `HttpClientDecorator`, `DatabaseClientDecorator`, `ServerDecorator`, `MessagingClientDecorator`, etc.
- One `public static final DECORATE` instance
- Define `UTF8BytesString` constants for the component name and operation name
- Keep all tag/naming/error logic here — not in the Advice class
- Override `spanType()`, `component()`, `spanKind()` as appropriate

## Step 7 – Write the Advice class (highest-risk step)

### Must do

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
- **Instrument the single delegate method, not all overloads (R15/R16/R17)**: when a library has multiple overloads of the same operation (e.g. `executeMethod(String)`, `executeMethod(HostConfig)`, `executeMethod(HostConfig, HttpMethod)`), check if they all delegate to a single internal method. If yes, instrument ONLY the delegate — not each overload. Instrumenting all overloads without a proper reentrancy guard creates **duplicate spans per request** (one per overload in the call chain) and injects context propagation headers multiple times. Use `CallDepthThreadLocalMap` when you must instrument at a higher level.

### Span lifecycle (in order)

Enter method:
1. `AgentSpan span = startSpan(DECORATE.operationName(), ...)`
2. `DECORATE.afterStart(span)` + set domain-specific tags
3. `AgentScope scope = activateSpan(span)` — return or store via `@Advice.Local`

Exit method:
4. `DECORATE.onError(span, throwable)` — only if throwable is non-null
5. `DECORATE.beforeFinish(span)`
6. `span.finish()`
7. `scope.close()`

#### onExit must be resilient to onEnter throwing

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

#### Specify charset explicitly when converting byte[] to String

```java
// WRONG — uses platform default charset
String cmd = new String(commandBytes);

// CORRECT — explicit charset
import java.nio.charset.StandardCharsets;
String cmd = new String(commandBytes, StandardCharsets.UTF_8);
```

#### R33 — Do NOT catch `NullPointerException`; use null-check guards instead

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

### Step 7.1 — Multiple advice classes and `@AppliesOn`

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

### Must NOT do

- **No logger fields** in the Advice class or the Instrumentation class (loggers only in helpers/decorators)
- **No code in the Advice constructor** — it is never called
- **Do not use lambdas in advice methods** — they create synthetic classes that will be missing from helper declarations
- **No references** to other methods in the same Advice class or in the InstrumenterModule class
- **No `InstrumentationContext.get()`** outside of Advice code
- **No `inline=false`** in production code (only for debugging; must be removed before committing)
- **No `java.util.logging.*`, `java.nio.*`, or `javax.management.*`** in bootstrap instrumentations

## Step 8 – Add SETTER/GETTER adapters (if applicable)

For context propagation to and from upstream services, like HTTP headers,
implement `AgentPropagation.Setter` / `AgentPropagation.Getter` adapters that wrap the framework's specific header API.
Place them in the helpers package, declare them in `helperClassNames()`.

## Step 9 – Write tests

Cover all mandatory test types:

### 1. Instrumentation test (mandatory)

**Write Java tests (JUnit 5), NOT Groovy/Spock (R20).** dd-trace-java policy
forbids new `.groovy` files — the PR bot (`checkNewGroovyFiles`) rejects any PR containing them.
All new tests must go in `src/test/java/`.

- JUnit 5 test class in `src/test/java/datadog/trace/instrumentation/<framework>/`
- Verify: spans created, tags set, errors propagated, resource names correct
- Use `TEST_WRITER.waitForTraces(N)` for assertions
- Use `runUnderTrace("root", () -> { ... })` for synchronous code (Java lambda, not Groovy closure)

**Tests must cover error/exception scenarios, not just the happy path (R14).** At minimum, add a test that exercises an exception or error condition and asserts the span's error tags (`error.type`, `error.message`, `error.stack`) are set correctly:

```java
// Example error test (Java)
@Test
void testExceptionSetsErrorTags() throws Exception {
    assertThrows(SomeException.class, () -> {
        // trigger operation that throws
        client.execute(badRequest);
    });
    List<List<SpanData>> traces = TEST_WRITER.waitForTraces(1);
    SpanData span = traces.get(0).get(0);
    assertThat(span.getAttributes().get(ERROR_TYPE)).isNotNull();
    assertThat(span.getAttributes().get(ERROR_MESSAGE)).isNotNull();
}
```

For tests that need a separate JVM, suffix the test class with `ForkedTest` and run via the `forkedTest` task.

#### R20 — No new .groovy files (Java tests only)

dd-trace-java forbids new `.groovy` files (bot-enforced on every PR). Write Java:

```java
// WRONG — generates a .groovy file which the bot rejects automatically
// src/test/groovy/JedisClientTest.groovy

// CORRECT — Java in src/test/java/
// src/test/java/datadog/trace/instrumentation/jedis3/Jedis3ClientTest.java
@ExtendWith(AgentJUnit5Extension.class)
public class Jedis3ClientTest extends AgentInstrumentationTest {
    @Test
    void testCommandCreatesSpan() {
        // test body
    }
}
```

The PR bot check (`checkNewGroovyFiles`) will fail the PR if any `.groovy` file is added.

#### R29 — Register new integration names in `metadata/supported-configurations.json`

Every new integration name in your module (whether from `super("foo-X.Y")` in `InstrumenterModule`, or from `instrumentationNames()` in the decorator) MUST have a corresponding entry in `metadata/supported-configurations.json` at the repo root. The `dd-gitlab/config-inversion-linter` CI job fails otherwise.

For each new integration name `<NAME>` (uppercase, dashes/dots replaced with underscores), add:

```json
"DD_TRACE_<NAME>_ENABLED": [
  {
    "version": "A",
    "type": "boolean",
    "default": "false",
    "aliases": ["DD_TRACE_INTEGRATION_<NAME>_ENABLED", "DD_INTEGRATION_<NAME>_ENABLED"]
  }
],
```

If the decorator's `instrumentationNames()` returns a shared name (e.g. `"sparkjava"` covering all sparkjava versions), also add the analytics keys for the shared name:

```json
"DD_TRACE_<SHARED>_ANALYTICS_ENABLED": [
  {
    "version": "A",
    "type": "boolean",
    "default": "false",
    "aliases": ["DD_<SHARED>_ANALYTICS_ENABLED"]
  }
],
"DD_TRACE_<SHARED>_ANALYTICS_SAMPLE_RATE": [
  {
    "version": "A",
    "type": "decimal",
    "default": "1.0",
    "aliases": ["DD_<SHARED>_ANALYTICS_SAMPLE_RATE"]
  }
],
```

**Place entries alphabetically** in the JSON file. **Verify the JSON parses** before committing (`python3 -c "import json; json.load(open('metadata/supported-configurations.json'))"`).

**Type names — match existing conventions**: use `"boolean"`, `"string"`, `"integer"`, `"decimal"` (for floating-point — NOT `"double"`). The `dd-gitlab/validate_supported_configurations_v2_local_file` CI job will fail with non-canonical type names like `"double"`. Cross-check by grepping existing entries for similar fields.

**How to discover whether entries are missing**: after writing the instrumentation, search `metadata/supported-configurations.json` for each name used in `super(...)` and `instrumentationNames()`. If any is absent, add it. Do not assume master already has it — version-specific integration names (e.g. `sparkjava-2.3` vs `sparkjava-2.4`) are not interchangeable.

#### R28 — compileOnly and testImplementation may use different versions — explain why

When `compileOnly` and `testImplementation` use different versions of the same library, add a
comment that explains the specific API or class that requires the higher version, and why.
Do not just state the fact — state the reason.

```groovy
// WRONG — states the fact without explaining why
// compileOnly=2.3, testImplementation=2.4
compileOnly group: 'com.sparkjava', name: 'spark-core', version: '2.3'
testImplementation group: 'com.sparkjava', name: 'spark-core', version: '2.4'

// CORRECT — explains the specific class and why it requires the higher version
// compileOnly=2.3 (module targets this version) but testImplementation=2.4:
// JettyHandler, which Spark uses internally to dispatch HTTP requests to route handlers,
// is not accessible as a public class in 2.3 — it was exposed starting in 2.4.
// The instrumentation hooks into JettyHandler via Jetty's existing instrumentation,
// so tests require 2.4 at minimum to exercise the code path.
compileOnly group: 'com.sparkjava', name: 'spark-core', version: '2.3'
testImplementation group: 'com.sparkjava', name: 'spark-core', version: '2.4'
```

**How to discover this during development**: install the library at `compileOnly` version and run
the sample app. If a specific class raises `ClassNotFoundException` or is inaccessible, that class
is the reason — check when it became public and use that version for `testImplementation`.
Name the class in the comment.

#### Include prior version module in testImplementation for mutual exclusion

When two modules instrument the same library at different versions, add the prior
version's module as a `testImplementation` dependency to confirm they don't double-instrument:

```groovy
// jedis-3.0/build.gradle
dependencies {
    testImplementation project(':dd-java-agent:instrumentation:jedis:jedis-1.4')
}
```

This ensures `:test` validates that only the correct module fires for jedis-3.x requests.

### 2. Muzzle directives (mandatory)

In `build.gradle`, add `muzzle` blocks. **There are two valid patterns** — choose based on whether your version range is open-ended or bounded.

**Pattern A — Open-ended range** (your instrumentation supports `[minVersion, ∞)` with no upper bound). Use `assertInverse = true` — the plugin auto-asserts that versions below `minVersion` fail muzzle:

```groovy
muzzle {
  pass {
    group = "com.example"
    module = "framework"
    versions = "[$minVersion,)"
    assertInverse = true
  }
}
```

**Pattern B — Bounded range** (your instrumentation supports `[minVersion, maxVersion)` and an existing sibling module covers `[maxVersion, ∞)`). **Do NOT use `assertInverse = true`** — it can pick boundary versions inside your declared range as inverse-test targets, causing `muzzle-AssertFail-...` failures:

```
> Task :muzzle-AssertFail-redis.clients-jedis-jedis-3.6.2 FAILED
MUZZLE PASSED JedisInstrumentation BUT FAILURE WAS EXPECTED
```

Instead, declare BOTH the pass range AND the explicit fail ranges that bound it:

```groovy
muzzle {
  pass {
    group = "com.example"
    module = "framework"
    versions = "[$minVersion,$maxVersion)"
  }
  fail {
    group = "com.example"
    module = "framework"
    versions = "[,$minVersion)"
  }
}
```

#### R31 — Do NOT use `assertInverse = true` unless the declared min is the actual minimum compatible version

The `assertInverse = true` directive tells muzzle to auto-test versions below the declared minimum and assert they fail. If your instrumentation is actually compatible with versions below the declared minimum (a common case when only ONE of several instrumentation classes requires the new feature), this auto-assertion will fail with:

```
> Task :module:muzzle-AssertFail-group-module-X.Y.Z FAILED
MUZZLE PASSED FeignClientInstrumentation BUT FAILURE WAS EXPECTED
```

**Rule**: If you can't verify (via running `./gradlew muzzle` against a sweep of older versions) that the declared min version is truly the minimum compatible version, **omit `assertInverse = true`**:

```groovy
// Conservative — passes muzzle without asserting unprovable inverse claims
muzzle {
  pass {
    group = "io.github.openfeign"
    module = "feign-core"
    versions = "[10.8,)"
    // NO assertInverse here — we don't know the true minimum
  }
}
```

Add `assertInverse = true` only when you've empirically verified the min via local muzzle sweep. Otherwise, leaving it off is correct.

**Background**: a typical greenfield generation produces a sync instrumentation class (works on older versions) and an async instrumentation class (requires a newer API). The agent picks the higher version as the declared min for muzzle, which is conservative for compileOnly. But `assertInverse = true` then auto-tests a lower version with ONLY the sync class hooks active, and that passes — creating the failure.

#### R18 — Muzzle range must exclude incompatible major versions

If the library you are instrumenting has a major version break where a newer major version
is published under the **same** `group:module` coordinates but with a completely different API
(e.g. `commons-httpclient:commons-httpclient` 2.x vs 4.x), your muzzle `pass` range must
have an explicit upper bound to exclude the incompatible major:

```groovy
// WRONG — open range accidentally covers commons-httpclient 4.x (different artifact family)
muzzle {
  pass {
    group = "commons-httpclient"
    module = "commons-httpclient"
    versions = "[2.0,)"   // 4.x would also match but has completely different API
    assertInverse = true
  }
}

// CORRECT — bounded range excludes 4.x
muzzle {
  pass {
    group = "commons-httpclient"
    module = "commons-httpclient"
    versions = "[2.0,4.0)"
    assertInverse = true
  }
}
```

**How to check**: search Maven Central for the `group:module` coordinates and look for versions
that are clearly a different generation (3.x → 4.x breaks, major API rewrites). Look at the
existing dd-trace-java module for the same library to see how it bounds its range.

#### Library-specific muzzle quirks: skipVersions

Some libraries have **malformed release versions** in Maven Central (e.g., a version literally named `jedis-3.6.2` with a `jedis-` prefix). These break the muzzle plugin's version-resolution algorithm — the task name becomes `muzzle-AssertFail-redis.clients-jedis-jedis-3.6.2` (doubled "jedis") and the muzzle plan can include them in the wrong directive.

The fix is `skipVersions` in the affected directive:

```groovy
muzzle {
  fail {
    group = "redis.clients"
    module = "jedis"
    versions = "[,3.0.0)"
    skipVersions += "jedis-3.6.2"  // bad release version ("jedis-" prefix)
  }
}
```

**How to discover these**: when verify fails with a task name that has a doubled module name (e.g., `redis.clients-jedis-jedis-3.6.2`), check the existing production module for the same library at another version. If it has `skipVersions` entries, copy them. This is library-specific tribal knowledge that lives in the existing modules.

When in doubt, **search adjacent module build.gradle files for `skipVersions`** before declaring a new version-bounded module's muzzle directives.

### 3. Latest dependency test (mandatory)

Use the `latestDepTestLibrary` helper in `build.gradle` to pin the latest available version. Run with:
```bash
./gradlew :dd-java-agent:instrumentation:$framework-$version:latestDepTest
```

**`latestDepTestImplementation` version range must match the instrumented range (R19).** If your module instruments version `2.x`, use `2+` as the version constraint, not `3+`:

```groovy
// WRONG — latestDep tests against 3.x but the module only instruments 2.x
latestDepTestImplementation group: 'commons-httpclient', name: 'commons-httpclient', version: '3+'

// CORRECT — latestDep tests against the highest 2.x release
latestDepTestImplementation group: 'commons-httpclient', name: 'commons-httpclient', version: '2+'
```

Using `3+` for a `2.x` instrumentation means `latestDepTest` runs against an incompatible API version and will either fail or silently test nothing.

### 4. Smoke test (optional)

Add a smoke test in `dd-smoke-tests/` only if the framework warrants a full end-to-end demo-app test.

## Step 10 – Build and verify

Run these commands in order and fix any failures before proceeding:

```bash
./gradlew :dd-java-agent:instrumentation:$framework-$version:muzzle
./gradlew :dd-java-agent:instrumentation:$framework-$version:test
./gradlew :dd-java-agent:instrumentation:$framework-$version:latestDepTest
./gradlew checkInstrumenterModuleConfigurations
./gradlew spotlessCheck
```

**If muzzle fails:** check for missing helper class names in `helperClassNames()`.

**If `checkInstrumenterModuleConfigurations` fails:** an integration name from `super(...)` is missing
(or mismatched) in `metadata/supported-configurations.json` — see Step 4, item 5.

**If tests fail:** verify span lifecycle order (start → activate → error → finish → close), helper registration,
and `contextStore()` map entries match actual usage.

**If spotlessCheck fails:** run `./gradlew spotlessApply` to auto-format, then re-check.

## Step 11 – Checklist before finishing

Output this checklist and confirm each item is satisfied:

- [ ] `settings.gradle.kts` entry added in alphabetical order
- [ ] `metadata/supported-configurations.json` has a `DD_TRACE_<NAME>_ENABLED` entry (+ the two aliases) for every name passed to `super(...)`
- [ ] `build.gradle` has `compileOnly` deps and `muzzle` directives with `assertInverse = true`
- [ ] `@AutoService(InstrumenterModule.class)` annotation present on the module class
- [ ] `helperClassNames()` lists ALL referenced helpers (including inner, anonymous, and enum synthetic classes)
- [ ] Advice methods are `static` with `@Advice.OnMethodEnter` / `@Advice.OnMethodExit` annotations
- [ ] `suppress = Throwable.class` on enter/exit (unless the hooked method is a constructor)
- [ ] No static constants holding return values of one-shot instrumenter methods (`triggerClasses()`, `contextStore()`, etc.)
- [ ] No logger field in the Advice class or InstrumenterModule class
- [ ] No `inline=false` left in production code
- [ ] No `java.util.logging.*` / `java.nio.*` / `javax.management.*` in bootstrap path
- [ ] Span lifecycle order is correct: startSpan → afterStart → activateSpan (enter); onError → beforeFinish → finish → close (exit)
- [ ] Muzzle passes
- [ ] Instrumentation tests pass
- [ ] `latestDepTest` passes
- [ ] `spotlessCheck` passes

## Step 12 – Retrospective: update this skill with what was learned

After the instrumentation is complete (or abandoned), review the full session and improve this skill for future use.

**Collect lessons from four sources:**

1. **Build/test failures** — did any Gradle task fail with an error that this skill did not anticipate or gave wrong
   guidance for? (e.g. a muzzle failure that wasn't caused by missing helpers, a test pattern that didn't work)
2. **Docs vs. skill gaps** — did Step 1's sync miss anything? Did you consult the docs for something not captured here?
3. **Reference instrumentation insights** — did the reference integration use a pattern, API, or convention not
   reflected in any step of this skill?
4. **User corrections** — did the user correct an output, override a decision, or point out a mistake?

**For each lesson identified**, edit this file (`.claude/skills/apm-integrations/SKILL.md`) using the `Edit` tool:
- Wrong rule → fix it in place
- Missing rule → add it to the most relevant step
- Wrong failure guidance → update the relevant "If X fails" section in Step 10
- Misleading or obsolete content → remove it

Keep each change minimal and targeted. Do not rewrite sections that worked correctly.
After editing, confirm to the user which improvements were made to the skill.
