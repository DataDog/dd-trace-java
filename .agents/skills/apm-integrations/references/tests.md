# Writing Tests

> Referenced from `SKILL.md` Step 9.1 (Instrumentation test). For muzzle directives (Step 9.2), see `muzzle.md` in this directory.

## 1. Instrumentation test (mandatory)

**Write Groovy/Spock tests for instrumentation tests** (per `AGENTS.md`: "Only use Groovy / Spock tests for instrumentation and smoke tests"). Full Java instrumentation test support is not yet available. Adding new `.groovy` files to a PR will trigger the `Enforce Groovy Migration` bot — add the `tag: override groovy enforcement` label to bypass it.

- Groovy/Spock test class in `src/test/groovy/datadog/trace/instrumentation/<framework>/`
- Verify: spans created, tags set, errors propagated, resource names correct
- Use `assertTraces(N) { trace(N) { span { ... } } }` for span assertions (Spock DSL from `InstrumentationSpecification`)
- Use `TEST_WRITER.waitForTraces(N)` for setup/teardown flushing (not for assertions)
- Use `runUnderTrace("root") { ... }` from `TraceUtils` for synchronous code (trailing Groovy closure)

**Tests must cover error/exception scenarios, not just the happy path.** At minimum, add a test that exercises an exception or error condition and asserts the span's error tags (`error.type`, `error.message`, `error.stack`) are set correctly:

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

For tests that need a separate JVM, suffix the test class with `ForkedTest` and run via the `forkedTest` task.

### Register new integration names in `metadata/supported-configurations.json`

See [Supported Configurations](supported-configurations.md) for the key shapes, CI checks, and JSON format.

### compileOnly and testImplementation may use different versions — explain why

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

### Include sibling version modules in testImplementation for mutual exclusion

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

## Test hygiene

### No `Thread.sleep()` in tests — use deterministic waits

`Thread.sleep(...)` is a recipe for flake. Use a deterministic mechanism instead:

- `TEST_WRITER.waitForTraces(N)` — waits until at least N traces have been recorded (`traceCount >= N`), with a bounded timeout of 20s (see `dd-trace-core/src/main/java/datadog/trace/common/writer/ListWriter.java`). Use `TEST_WRITER.size()` afterwards to assert the exact count you expect.
- `CountDownLatch` / `CompletableFuture.get(timeout, TimeUnit)` — for signalling from async callbacks
- Spock's `PollingConditions` — for polling an assertion until it holds

If you catch yourself writing `Thread.sleep(...)`, name the specific signal you're waiting for and wait on that signal directly.

### Embedded servers use a static field — do not recreate per test

For tests that start an embedded server (Jetty, Netty, Undertow, Spark, etc.), initialize the server once as a `@Shared` or `static` field and reuse it across test methods. Do NOT construct a new server in each `setup:` / `@Before` unless you have a concrete reason (e.g. per-test configuration). Recreating the server per test multiplies test wall-time and adds a startup-race surface for no benefit. Follow the pattern of existing server-instrumentation tests in the same framework family.

### Factor shared test scaffolding into a base class

If two sibling test classes (e.g. `FooTest` and `FooForkedTest`) need the same setup, request builder, or assertion helpers, extract them into a shared abstract base — do NOT copy-paste between the two files. Duplicated helper code across a handful of test classes is how bespoke JUnit scaffolding metastasizes across the codebase.

### `ForkedTest` variants must have a concrete isolation reason

The `ForkedTest` suffix runs a test in its own JVM via the `forkedTest` task. Only add a `ForkedTest` variant when the test genuinely needs JVM isolation — e.g. a system property that must be set before class-loading, an agent-level configuration that cannot be reset between tests, or a class-loader state that leaks. Do NOT mechanically add a `ForkedTest` alongside every `Test` class; each fork adds JVM startup cost to CI.

State the isolation reason in a comment on the `ForkedTest` class.

### Do not add default jvmArgs to test tasks

`dd.trace.enabled=true` is the default; adding `jvmArgs '-Ddd.trace.enabled=true'` to a `Test` task in `build.gradle` is noise. Only add jvmArgs that meaningfully diverge from defaults (e.g. enabling a specific integration that's off by default, or a debug flag). If you're tempted to copy a `jvmArgs` block from a sibling module, check whether each flag is actually needed for this module.

## Version-sensitive tests belong in a separate `latestDepTest` source set

For libraries whose API surface changes across minor versions (Reactor deprecates and removes APIs; Netty changes handler signatures; gRPC's generated code evolves), route each test to the source set whose classpath actually satisfies its imports:

- **API only exists in the latest version** (added after your pinned min) → put the test in `src/latestDepTest/`, which compiles against `latestDepTestImplementation`. `src/test/` compiles against the pinned min and doesn't have the API.
- **API only exists at the pinned min** (removed in a later version — e.g. Reactor's `Schedulers.elastic()`, removed in 3.4+) → put the test in `src/test/`, NOT `latestDepTest/`. Test the replacement API (`Schedulers.boundedElastic()`) in `latestDepTest/` instead.

Common libraries where this split matters: Reactor, Netty, gRPC, Kafka clients (consumer API changed at 3.0), Cassandra driver (3.x vs 4.x largely incompatible).

**Editing an existing module:** check for `src/latestDepTest/` and the `addTestSuite('latestDepTest')` / `addTestSuiteForDir('latestDepTest', ...)` declaration in `build.gradle` before touching tests, and preserve the split — collapsing it into `src/test/` produces a `latestDepTest` compile failure the moment a test uses a since-removed API.

## No banner/separator comments in test files

Do NOT insert banner-style separator comments (e.g. `// --------- Successful completion ---------`) inside test files to group related test methods. Banner comments have unclear scope, don't render usefully in IDEs, and add review burden without a benefit that justifies the noise.

**If a group of related tests warrants its own heading**, extract them into a separate test class with a focused class-level Javadoc:

```java
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
