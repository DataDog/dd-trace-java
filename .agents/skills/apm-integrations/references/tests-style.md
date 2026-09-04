# Test Style & Situational Rules

> Referenced from [tests.md](tests.md). These rules are situational — read the specific section you need, not the whole file top to bottom.

## Error test example

```groovy
// Example error test (Groovy/Spock)
def "exception sets error tags"() {
    when:
    client.execute(badRequest)

    then:
    thrown(SomeException)
    assertTraces(1) {
        trace(1) {
            span {
                errored true
                errorTags(SomeException, "expected error message")
            }
        }
    }
}
```

## compileOnly and testImplementation may use different versions — explain why

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

**How to discover this during development**: install the library at the `compileOnly` version and run
your instrumentation test. If a specific class raises `ClassNotFoundException` or is inaccessible, that
class is the reason — check when it became public and use that version for `testImplementation`.
Name the class in the comment.

## Include sibling version modules in testImplementation for mutual exclusion

When two modules instrument the same library at non-overlapping version ranges, each module should include the other as a `testImplementation` dependency to confirm they don't double-instrument. The rule is symmetric — both the older and the newer module should carry this dependency:

```groovy
// jedis-3.0/build.gradle
dependencies {
    testImplementation project(':dd-java-agent:instrumentation:jedis:jedis-1.4')
}

// jedis-1.4/build.gradle
dependencies {
    testImplementation project(':dd-java-agent:instrumentation:jedis:jedis-3.0')
}
```

This ensures `:test` in each module validates that only the correct module fires for its version range.

## Embedded servers use a static field — do not recreate per test

For tests that start an embedded server (Jetty, Netty, Undertow, Spark, etc.), initialize the server once as a `@Shared` or `static` field and reuse it across test methods. Do NOT construct a new server in each `setup:` / `@Before` unless you have a concrete reason (e.g. per-test configuration). Recreating the server per test multiplies test wall-time and adds a startup-race surface for no benefit. Follow the pattern of existing server-instrumentation tests in the same framework family.

## Factor shared test scaffolding into a base class

If two sibling test classes (e.g. `FooTest` and `FooForkedTest`) need the same setup, request builder, or assertion helpers, extract them into a shared abstract base — do NOT copy-paste between the two files. Duplicated helper code across a handful of test classes is how bespoke JUnit scaffolding metastasizes across the codebase.

## ForkedTest variants must have a concrete isolation reason

The `ForkedTest` suffix runs a test in its own JVM via the `forkedTest` task. Only add a `ForkedTest` variant when the test genuinely needs JVM isolation — e.g. a system property that must be set before class-loading, an agent-level configuration that cannot be reset between tests, or a class-loader state that leaks. Do NOT mechanically add a `ForkedTest` alongside every `Test` class; each fork adds JVM startup cost to CI.

State the isolation reason in a comment on the `ForkedTest` class.

## Version-sensitive tests belong in a separate latestDepTest source set

For libraries whose API surface changes across minor versions (Reactor deprecates and removes APIs; Netty changes handler signatures; gRPC's generated code evolves), route each test to the source set whose classpath actually satisfies its imports. First check how the module wires `latestDepTest` in `build.gradle`:

- **`addTestSuite('latestDepTest')`** — `latestDepTest` has its own sources at `src/latestDepTest/`, separate from `src/test/`. In this shape: put latest-only APIs (added after your pinned min) in `src/latestDepTest/`; put removed-in-latest APIs (e.g. Reactor's `Schedulers.elastic()`, removed in 3.5+) in `src/test/`, testing the replacement API (`Schedulers.boundedElastic()`) in `latestDepTest/` instead.
- **`addTestSuiteForDir('latestDepTest', 'test')`** — `latestDepTest` reuses `src/test/`'s sources and compiles them against the latest classpath too. In this shape, `src/test/` is NOT a safe place for a removed-in-latest-API test — it still gets compiled against `latestDepTestImplementation` and will fail the same way. A test that exercises a removed API needs the module to declare a real separate `latestDepTest` source set instead (switch to `addTestSuite('latestDepTest')`), or the test needs to avoid the removed API entirely (e.g. call the replacement API and assert equivalent behavior).

Common libraries where this split matters: Reactor, Netty, gRPC, Kafka clients (consumer API changed at 3.0), Cassandra driver (3.x vs 4.x largely incompatible).

**Editing an existing module:** check for `src/latestDepTest/` and the exact `addTestSuite(...)`/`addTestSuiteForDir(...)` declaration in `build.gradle` before touching tests, and preserve whichever shape master uses.

## No banner/separator comments in test files

Do NOT insert banner-style separator comments (e.g. `// --------- Successful completion ---------`) inside test files to group related test methods. Banner comments have unclear scope, don't render usefully in IDEs, and add review burden without a benefit that justifies the noise.

**If a group of related tests warrants its own heading**, extract them into a separate test class with a focused class-level Javadoc:

```java
// Java example, style only — the same DSL rule in tests.md applies regardless of language
// ❌ Banner comments
class RxJava3ResultExtensionTest extends AbstractInstrumentationTest {
  // ---------------------------------------------------------------------------
  // Successful async completion: span finishes when reactive type completes
  // ---------------------------------------------------------------------------
  @ParameterizedTest
  void successfulCompletion(...) { ... }

  // ---------------------------------------------------------------------------
  // Error paths: span records error and finishes
  // ---------------------------------------------------------------------------
  @ParameterizedTest
  void errorPath(...) { ... }
}

// ✅ Either omit the banner
class RxJava3ResultExtensionTest extends AbstractInstrumentationTest {
  @ParameterizedTest
  void successfulCompletion(...) { ... }

  @ParameterizedTest
  void errorPath(...) { ... }
}

// OR extract into focused classes with class Javadoc
/**
 * Successful async completion — verifies the extension finishes the span
 * when the reactive type emits a terminal signal.
 */
class RxJava3ResultExtensionSuccessTest extends AbstractInstrumentationTest {
  @ParameterizedTest
  void successfulCompletion(...) { ... }
}

/**
 * Error paths — verifies the extension records the error and finishes the span
 * when the reactive type emits an onError signal.
 */
class RxJava3ResultExtensionErrorTest extends AbstractInstrumentationTest {
  @ParameterizedTest
  void errorPath(...) { ... }
}
```

Source: @ygree review on PR #11939.
