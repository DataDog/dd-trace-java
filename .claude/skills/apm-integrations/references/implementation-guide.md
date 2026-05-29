# Implementation Guide

Step-by-step guide for creating a new dd-trace-java integration from scratch.

For implementation patterns, **read a canonical reference integration end-to-end** before writing new code. Code in this guide goes stale; live reference integrations don't. See `reference-integrations.md` for the right reference per category.

## Prerequisites

- dd-trace-java repository cloned
- Java 8+ installed
- Gradle wrapper available (`./gradlew`)
- Target library Maven coordinates known
- Read [`docs/how_instrumentations_work.md`](../../../../../../docs/how_instrumentations_work.md), [`docs/add_new_instrumentation.md`](../../../../../../docs/add_new_instrumentation.md), and [`docs/how_to_test.md`](../../../../../../docs/how_to_test.md) in the dd-trace-java repo

## Step 1: Create Module Structure

```
dd-java-agent/instrumentation/<framework>/<framework>-<minVersion>/
  build.gradle
  src/main/java/.../<Framework>Instrumentation.java   # InstrumenterModule
  src/main/java/.../<Framework>Decorator.java         # Decorator
  src/main/java/.../<Framework>Advice.java            # Advice (or per-method)
  src/test/groovy/.../<Framework>Test.groovy          # Spock spec
```

Reference: `dd-java-agent/instrumentation/okhttp-3/` for HTTP-client layout, `dd-java-agent/instrumentation/jedis-1.4/` for database-client layout.

## Step 2: Create build.gradle

Copy the structure from a reference integration in the same category:

- HTTP client → `dd-java-agent/instrumentation/okhttp-3/build.gradle`
- Database → `dd-java-agent/instrumentation/jdbc/build.gradle`
- Messaging → `dd-java-agent/instrumentation/kafka-clients-0.11/build.gradle`
- Reactive → `dd-java-agent/instrumentation/reactor-core-3.1/build.gradle`

Required elements:
- `compileOnly` dep for the target library
- `testImplementation` for tests
- `muzzle { pass { group, module, versions, assertInverse = true } }` block (see `bytebuddy-patterns.md` R12)
- `latestDepTestLibrary` to pin the latest version for the latestDepTest task

## Step 3: Register in settings.gradle.kts

Add the module path to `settings.gradle.kts` in alphabetical order. Search for a nearby module name to find the right spot.

## Step 4: Write InstrumenterModule

Required elements (see `bytebuddy-patterns.md` R3, R4, R11):

- `@AutoService(InstrumenterModule.class)` annotation
- Extend the most specific `InstrumenterModule.*` subclass (`Tracing`, `Profiling`, `AppSec`, etc.) — never the bare abstract class
- Implement the narrowest `Instrumenter` interface possible (`ForSingleType` > `ForKnownTypes` > `ForTypeHierarchy`)
- Declare ALL helper class names in `helperClassNames()`, including inner classes (`Foo$Bar`), anonymous classes (`Foo$1`), and synthetic enum classes
- Override `methodAdvice()` to register Advice
- Add `classLoaderMatcher()` if there's a sentinel class identifying the framework

**Reference**: Read the InstrumenterModule from the reference integration matching your category. The structural pattern is consistent; the type/method matchers and helper list are what change.

## Step 5: Write Decorator

Required elements:
- Extend the most specific base decorator: `HttpClientDecorator`, `DatabaseClientDecorator`, `ServerDecorator`, `MessagingClientDecorator`, etc. — see `reference-integrations.md` for which to pick
- One `public static final DECORATE` instance
- `UTF8BytesString` constants for component name and operation name
- Override `spanType()`, `component()`, `spanKind()` as needed
- Keep all tag/naming/error logic in the Decorator (not in the Advice)

**Reference**: `dd-java-agent/instrumentation/okhttp-3/src/main/java/.../OkHttpClientDecorator.java`.

## Step 6: Write Advice Class

The highest-risk step. **Before writing, read [`bytebuddy-patterns.md`](bytebuddy-patterns.md) end-to-end** for R1-R14 rules.

Quick checklist:
- All Advice methods must be `static`
- Annotate enter: `@Advice.OnMethodEnter(suppress = Throwable.class)`
- Annotate exit: `@Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)` (omit `suppress` for constructors)
- Use `@Advice.Local("...")` for values shared between enter and exit
- Use `CallDepthThreadLocalMap` to guard against recursive instrumentation (R5)
- No logger fields, no lambdas, no method references to other methods in the Advice or InstrumenterModule (R1, R6, R7)
- No `inline=false` in production code

Span lifecycle order matters — see `bytebuddy-patterns.md` R8 for the exact sequence (startSpan → afterStart → activateSpan → onError → beforeFinish → finish → close).

**Reference**: `dd-java-agent/instrumentation/okhttp-3/src/main/java/.../OkHttp3Advice.java`.

## Step 7: Write Propagation Adapter (if needed)

For HTTP/messaging integrations that propagate context, implement `AgentPropagation.Setter` (outbound) and/or `AgentPropagation.Getter` (inbound) adapters wrapping the framework's header API. Place them in the helpers package and declare them in `helperClassNames()`.

**Reference**: `dd-java-agent/instrumentation/okhttp-3/src/main/java/.../RequestBuilderInjectAdapter.java`.

## Step 8: Write Tests

Mandatory test types:

1. **Instrumentation test** — Spock spec extending `InstrumentationSpecification`, in `src/test/groovy/`. Verify spans created, tags set, errors propagated, resource names correct. Use `TEST_WRITER.waitForTraces(N)` and `runUnderTrace("root") { ... }` for sync code. For separate-JVM tests, suffix with `ForkedTest` and run via the `forkedTest` task.

2. **Muzzle directives** in `build.gradle` — `pass { ... assertInverse = true }` ensures versions below the minimum fail muzzle.

3. **Latest dep test** via `latestDepTestLibrary` helper. Run: `./gradlew :dd-java-agent:instrumentation:<framework>-<version>:latestDepTest`.

4. **Smoke test** in `dd-smoke-tests/` only if the framework warrants a full demo-app test (optional).

**Reference**: `dd-java-agent/instrumentation/okhttp-3/src/test/groovy/.../OkHttp3Test.groovy`.

## Step 9: Run Tests

```bash
./gradlew :dd-java-agent:instrumentation:<framework>-<minVersion>:muzzle
./gradlew :dd-java-agent:instrumentation:<framework>-<minVersion>:test
./gradlew :dd-java-agent:instrumentation:<framework>-<minVersion>:latestDepTest
./gradlew spotlessCheck
```

## Step 10: Fix Common Issues

See [`anti-patterns.md`](anti-patterns.md) for the full catalog of common mistakes and how to debug them.

Quick triage:
- **Muzzle failure**: missing helper class name in `helperClassNames()` (R4, R11) — most common cause
- **Test failure with no span created**: span lifecycle order wrong (R8), or `methodAdvice()` not registered, or matcher too narrow
- **Test failure with wrong tags**: tagging logic in Advice instead of Decorator
- **Spotless failure**: run `./gradlew spotlessApply` to auto-fix (R14)

## Step 11: Submit for Review

Verify the checklist in `SKILL.md` Step 11 before submitting.
