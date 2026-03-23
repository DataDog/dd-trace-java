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

## Step 5 – Write the InstrumenterModule

Conventions to enforce:

- Add `@AutoService(InstrumenterModule.class)` annotation — required for auto-discovery
- Extend the correct `InstrumenterModule.*` subclass (never the bare abstract class)
- Implement the **narrowest** `Instrumenter` interface possible:
  - Prefer `ForSingleType` > `ForKnownTypes` > `ForTypeHierarchy`
- Add `classLoaderMatcher()` if a sentinel class identifies the framework on the classpath
- Declare **all** helper class names in `helperClassNames()`:
  - Include inner classes (`Foo$Bar`), anonymous classes (`Foo$1`), and enum synthetic classes
- Declare `contextStore()` entries if context stores are needed (key class → value class)
- Keep method matchers as narrow as possible (name, parameter types, visibility)

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

- Spock spec extending `InstrumentationSpecification`
- Place in `src/test/groovy/`
- Verify: spans created, tags set, errors propagated, resource names correct
- Use `TEST_WRITER.waitForTraces(N)` for assertions
- Use `runUnderTrace("root") { ... }` for synchronous code

For tests that need a separate JVM, suffix the test class with `ForkedTest` and run via the `forkedTest` task.

### 2. Muzzle directives (mandatory)

In `build.gradle`, add `muzzle` blocks:
```groovy
muzzle {
  pass {
    group = "com.example"
    module = "framework"
    versions = "[$minVersion,)"
    assertInverse = true  // ensures versions below $minVersion fail muzzle
  }
}
```

### 3. Latest dependency test (mandatory)

Use the `latestDepTestLibrary` helper in `build.gradle` to pin the latest available version. Run with:
```bash
./gradlew :dd-java-agent:instrumentation:$framework-$version:latestDepTest
```

### 4. Smoke test (optional)

Add a smoke test in `dd-smoke-tests/` only if the framework warrants a full end-to-end demo-app test.

## Step 10 – Build and verify

Run these commands in order and fix any failures before proceeding:

```bash
./gradlew :dd-java-agent:instrumentation:$framework-$version:muzzle
./gradlew :dd-java-agent:instrumentation:$framework-$version:test
./gradlew :dd-java-agent:instrumentation:$framework-$version:latestDepTest
./gradlew spotlessCheck
```

**If muzzle fails:** check for missing helper class names in `helperClassNames()`.

**If tests fail:** verify span lifecycle order (start → activate → error → finish → close), helper registration,
and `contextStore()` map entries match actual usage.

**If spotlessCheck fails:** run `./gradlew spotlessApply` to auto-format, then re-check.

## Step 11 – Checklist before finishing

Output this checklist and confirm each item is satisfied:

- [ ] `settings.gradle.kts` entry added in alphabetical order
- [ ] `build.gradle` has `compileOnly` deps and `muzzle` directives with `assertInverse = true`
- [ ] `@AutoService(InstrumenterModule.class)` annotation present on the module class
- [ ] `helperClassNames()` lists ALL referenced helpers (including inner, anonymous, and enum synthetic classes)
- [ ] Advice methods are `static` with `@Advice.OnMethodEnter` / `@Advice.OnMethodExit` annotations
- [ ] `suppress = Throwable.class` on enter/exit (unless the hooked method is a constructor)
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

## Appendix: Collected review rules

These rules are derived from expert feedback on generated PRs. They serve as a checklist for self-review before finishing. Some overlap with guidance in the steps above; they are collected here for completeness.

### R1: No lambdas in advice classes (error)
Search for lambda expressions (`->` or `::`) in any file with "Advice" or "Instrumentation" in its name. Advice methods are inlined into bytecode by ByteBuddy, and lambdas create invokedynamic instructions that break when inlined into a different classloader context.
*Source: mcculls, PR #10579*

### R2: Assign wrapped future back with `@Advice.Return(readOnly=false)` (error)
In async advice exit methods that wrap futures (CompletableFuture, ListenableFuture, etc.), verify the wrapped result is assigned back to the return value using `@Advice.Return(readOnly=false)`. Do not discard the return of `future.whenComplete`/`thenApply`/etc.
*Applies to: async instrumentations only. Source: mcculls, PR #10579*

### R3: Single InstrumenterModule per integration (error)
There should be exactly one class extending `InstrumenterModule` (with `@AutoService(InstrumenterModule.class)`) per module. If the integration needs to instrument multiple classes, list them as separate `Instrumenter` implementations within the same module.
*Source: mcculls, PR #10579*

### R4: Module name must end with version (error)
The module directory under `dd-java-agent/instrumentation/` must follow `{library}-{version}` (e.g., `feign-8.0`, `okhttp-3`). Do not use the Maven artifact name (e.g., `feign-core`).
*Source: mcculls, PR #10579*

### R5: CallDepthThreadLocalMap must be reset on the same thread (error)
If `CallDepthThreadLocalMap` is used for reentrancy protection, `increment()` and `reset()` must be called on the same thread. In async code paths, callbacks may run on a different thread, so `CallDepthThreadLocalMap` cannot be used across async boundaries.
*Applies to: async instrumentations only. Source: mcculls, PR #10579*

### R6: Code passes codeNarc checks (warning)
If there are Groovy test files, avoid common codeNarc violations: unused imports, unnecessary semicolons, missing spaces after keywords. Run `codenarcTest` in addition to `spotlessCheck`.
*Source: Runs 0, 1*

### R7: Test against minimum supported version, not just latest (warning)
Compare the `testCompile`/`testImplementation` dependency version in `build.gradle` against the minimum version in the muzzle `pass` directive. If the test uses APIs that only exist in newer versions, it does not actually verify minimum version compatibility.
*Source: Run 1 (auto-detected)*

### R8: Test extends correct base test class (warning)
The test superclass should match the integration type:
- HTTP clients: extend `HttpClientTest`
- HTTP servers: extend `HttpServerTest`
- Database clients: extend `DatabaseClientTest`
- Messaging: use the appropriate messaging base test

If no base test class exists for the type, custom tests are acceptable but should cover span creation, error handling, and tag verification.
*Source: Run 1 (auto-detected)*

### R9: Disabled base test methods must have justification (warning)
Methods that override base test class methods to return `false` (e.g., `testRedirects() { return false }`) must have a comment explaining why — what specific library limitation prevents the test from working.
*Source: Run 1 (auto-detected)*

### R10: Test exercises real library, not just mocks (error)
The test must instantiate real library objects and make real calls (e.g., a real HTTP client calling a test server). Tests that only use mocks without exercising actual library code do not verify the instrumentation works. Base test classes typically provide real server infrastructure.
*Source: PR #10317 (Resilience4j)*

### R11: Test compiles and runs against minimum supported version (warning)
Verify that the test code only uses APIs available in the minimum supported version. If the test uses a newer API (e.g., a static factory method added in a later version), it cannot verify minimum version compatibility. This extends R7 with API-level checking.
*Source: Run 1 (extends R7)*
