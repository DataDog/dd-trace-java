---
name: apm-integrations
description: Write a new library instrumentation end-to-end.
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
