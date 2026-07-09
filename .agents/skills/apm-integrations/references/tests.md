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
