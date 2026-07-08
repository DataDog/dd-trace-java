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

**Read [Writing Tests](references/tests.md).** Java tests only (JUnit 5) in `src/test/java/` — no new `.groovy` files (bot-enforced). Must cover error/exception scenarios. When adding new integration names, register them in `metadata/supported-configurations.json` (config-inversion-linter enforces). When `compileOnly` and `testImplementation` use different versions, comment the specific class that requires the higher version. Include the prior-version module as a `testImplementation` dependency for mutual-exclusion tests.
### 2. Muzzle directives (mandatory)

**Read [Muzzle Directives](references/muzzle.md).**

Two valid patterns: open-ended range (`[$min,)` with `assertInverse = true` only when the true min is verified) or bounded range (`[$min,$max)` with explicit `fail { versions = "[,$min)" }` — no `assertInverse`). Muzzle range must exclude incompatible major versions when the same `group:module` republishes with a rewritten API. Library-specific quirks (malformed release versions like `jedis-3.6.2`) require `skipVersions` — search adjacent modules for these before declaring new ranges.
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
