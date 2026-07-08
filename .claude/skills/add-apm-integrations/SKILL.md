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
5. Register the integration name in `metadata/supported-configurations.json` (see "Register new integration names" in Step 9), or
   `checkInstrumenterModuleConfigurations` fails. The name in `super(...)` maps to env var
   `DD_TRACE_<NAME>_ENABLED` (`.` and `-` become `_`, uppercased — `couchbase-3` →
   `DD_TRACE_COUCHBASE_3_ENABLED`). Add a `"type": "boolean"` entry, in alphabetical order, with
   aliases `DD_TRACE_INTEGRATION_<NAME>_ENABLED` and `DD_INTEGRATION_<NAME>_ENABLED`. Set `default`
   to the module's real default — `"true"`, or `"false"` if it overrides `defaultEnabled()` (e.g.
   OpenTelemetry, Hazelcast). Declaring several names (`super("a", "b")`) means one entry each.

**See [Naming Conventions](references/naming-conventions.md) — module directory name must end with a version or an allowed suffix (`-common`, `-stubs`, `-iast`).**

## Step 4.1 – Library category: span-creating vs context-propagation

**Read [Category B — Context Propagation](references/category-b-context-propagation.md) before picking instrumentation targets.**

Classify the library along the `target_kind` axis: Category A (span-creating — HTTP/DB/messaging/RPC) or Category B (context-propagation — reactive/async/executor/fiber). Category B does not create spans; it bridges trace context across async boundaries. Get this wrong and you generate the wrong shape of instrumentation.

## Step 4.2 – Java naming consistency (CRITICAL — non-negotiable)

**Read [Naming Conventions](references/naming-conventions.md) § "Java naming consistency".**

Filename and `public class` name MUST match character-for-character (including acronym casing). Use one canonical string everywhere: filename, class decl, `import static`, `ClassName.member` references. The reference file has a sanity-check script — run it before declaring done.

## Step 5 – Write the InstrumenterModule

**Read [InstrumenterModule Guidance](references/instrumenter-module.md).**

In short: annotate with `@AutoService`, extend the right `InstrumenterModule.*` subclass, implement the narrowest `Instrumenter` interface possible (`ForSingleType` > `ForKnownTypes` > `ForTypeHierarchy` — with a critical exception for interface-only API JARs like JMS/JPA/JDBC). Include a version-qualified alias in `instrumentationNames()` (`{"jedis", "jedis-3.0"}`). Declare all helper classes. When regenerating an existing module, preserve master's integration name to avoid churn in `supported-configurations.json`. Do NOT extract one-shot method return values into static constants.

## Step 6 – Write the Decorator

- Extend the most specific available base decorator:
  - `HttpClientDecorator`, `DatabaseClientDecorator`, `ServerDecorator`, `MessagingClientDecorator`, etc.
- One `public static final DECORATE` instance
- Define `UTF8BytesString` constants for the component name and operation name
- Keep all tag/naming/error logic here — not in the Advice class
- Override `spanType()`, `component()`, `spanKind()` as appropriate

## Step 7 – Write the Advice class (highest-risk step)

**Read [Writing the Advice Class](references/advice-class.md).**

The reference file covers: `static` advice methods; enter/exit annotations (with the constructor exception); parameter annotations (`@Advice.This`, `@Advice.Argument`, `@Advice.Return`, `@Advice.Thrown`, `@Advice.Enter`, `@Advice.Local`); `CallDepthThreadLocalMap` reentrancy guarding; single-delegate-method instrumentation (not all overloads); span lifecycle order; `onExit` resilience to `onEnter` throwing; explicit charset for `byte[]` → `String`; no `NullPointerException` catches (SpotBugs blocks); `@AppliesOn` for multiple advice classes; and the "Must NOT do" list (no logger fields, no lambdas in advice, no `inline=false` in production, no `java.util.logging.*` / `java.nio.*` / `javax.management.*` in bootstrap).
## Step 8 – Add SETTER/GETTER adapters (if applicable)

For context propagation to and from upstream services, like HTTP headers,
implement `AgentPropagation.Setter` / `AgentPropagation.Getter` adapters that wrap the framework's specific header API.
Place them in the helpers package, declare them in `helperClassNames()`.

## Step 9 – Write tests

Cover all mandatory test types:

### 1. Instrumentation test (mandatory)

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

#### No new .groovy files (Java tests only)

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

#### Register new integration names in `metadata/supported-configurations.json`

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

#### compileOnly and testImplementation may use different versions — explain why

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

#### Do NOT use `assertInverse = true` unless the declared min is the actual minimum compatible version

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

#### Muzzle range must exclude incompatible major versions

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

**`latestDepTestImplementation` version range must match the instrumented range.** If your module instruments version `2.x`, use `2+` as the version constraint, not `3+`:

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
