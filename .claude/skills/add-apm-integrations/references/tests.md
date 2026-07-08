# Writing Tests

> Referenced from `SKILL.md` Step 9.1 (Instrumentation test). For muzzle directives (Step 9.2), see `muzzle.md` in this directory.

## 1. Instrumentation test (mandatory)

**Write Java tests (JUnit 5), NOT Groovy/Spock.** dd-trace-java policy
forbids new `.groovy` files — the PR bot (`checkNewGroovyFiles`) rejects any PR containing them.
All new tests must go in `src/test/java/`.

- JUnit 5 test class in `src/test/java/datadog/trace/instrumentation/<framework>/`
- Verify: spans created, tags set, errors propagated, resource names correct
- Use `TEST_WRITER.waitForTraces(N)` for assertions
- Use `runUnderTrace("root", () -> { ... })` for synchronous code (Java lambda, not Groovy closure)

**Tests must cover error/exception scenarios, not just the happy path.** At minimum, add a test that exercises an exception or error condition and asserts the span's error tags (`error.type`, `error.message`, `error.stack`) are set correctly:

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

### No new .groovy files (Java tests only)

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

### Register new integration names in `metadata/supported-configurations.json`

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

### Include prior version module in testImplementation for mutual exclusion

When two modules instrument the same library at different versions, add the prior
version's module as a `testImplementation` dependency to confirm they don't double-instrument:

```groovy
// jedis-3.0/build.gradle
dependencies {
    testImplementation project(':dd-java-agent:instrumentation:jedis:jedis-1.4')
}
```

This ensures `:test` validates that only the correct module fires for jedis-3.x requests.
