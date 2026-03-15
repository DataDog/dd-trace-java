# Agent context for dd-trace-java

## What is this project?

Datadog Java APM agent (`dd-trace-java`): a Java agent that auto-instruments JVM applications at runtime via bytecode manipulation.
It ships ~120 integrations (~200 instrumentations) for tracing, profiling, AppSec, IAST, CI Visibility, USM, and LLM Observability.

## Project layout

See [ARCHITECTURE.md](ARCHITECTURE.md) for detailed module descriptions.

```
dd-java-agent/            Main agent (shadow jar, instrumentations, product modules)
dd-trace-api/             Public API & configuration constants
dd-trace-core/            Core tracing engine (spans, propagation, writer)
dd-trace-ot/              Legacy OpenTracing compatibility library
internal-api/             Internal shared API across modules
components/               Shared low-level components (context, environment, json)
products/                 Sub-products (feature flagging, metrics)
communication/            HTTP transport to Datadog Agent
remote-config/            Remote configuration support
telemetry/                Agent telemetry
utils/                    Shared utility modules (config, time, socket, test, etc.)
dd-smoke-tests/           Smoke tests (real apps + agent)
docs/                     Developer documentation (see below)
```

## Key documentation (read on demand, don't load upfront)

| Topic | File |
|---|---|
| Architecture & design | [ARCHITECTURE.md](ARCHITECTURE.md) |
| Building from source | [BUILDING.md](BUILDING.md) |
| Contributing & PR guidelines | [CONTRIBUTING.md](CONTRIBUTING.md) |
| How instrumentations work | [docs/how_instrumentations_work.md](docs/how_instrumentations_work.md) |
| Adding a new instrumentation | [docs/add_new_instrumentation.md](docs/add_new_instrumentation.md) |
| Adding a new configuration | [docs/add_new_configurations.md](docs/add_new_configurations.md) |
| Testing guide (6 test types) | [docs/how_to_test.md](docs/how_to_test.md) |
| Working with Gradle | [docs/how_to_work_with_gradle.md](docs/how_to_work_with_gradle.md) |
| Bootstrap/premain constraints | [docs/bootstrap_design_guidelines.md](docs/bootstrap_design_guidelines.md) |
| CI/CD workflows | [.github/workflows/README.md](.github/workflows/README.md) |

**When working on a topic above, read the linked file first** — they are the source of truth maintained by humans.

## Build quick reference

```shell
./gradlew clean assemble                  # Build without tests
./gradlew :dd-java-agent:shadowJar        # Build agent jar only (dd-java-agent/build/libs/)
./gradlew :path:to:module:test            # Run tests for a specific module
./gradlew :path:to:module:test -PtestJvm=11  # Test on a specific JVM version
./gradlew spotlessApply                   # Auto-format code (google-java-format)
./gradlew spotlessCheck                   # Verify formatting
./gradlew :path:to:module:forkedTest      # Run forked tests (separate JVM per class)
./gradlew :path:to:module:latestDepTest   # Test against latest framework version
./gradlew :path:to:module:muzzle          # Check instrumentation version safety
./gradlew checkInstrumentationNaming      # Validate instrumentation module names
./gradlew forbiddenApisMain               # Check forbidden API usage
./gradlew checkAgentJarSize               # Verify agent jar < 32 MB
```

## Code conventions

- **Formatting**: google-java-format enforced via Spotless. Run `./gradlew spotlessApply` before committing.
- **Instrumentation layout**: `dd-java-agent/instrumentation/{framework}/{framework}-{minVersion}/`
- **Instrumentation pattern**: Type matching → Method matching → Advice class (bytecode advice, not AOP)
- **Test frameworks**: JUnit 5 (preferred for unit tests), Spock 2 (for complex scenarios needing Groovy)
- **Forked tests**: Use `ForkedTest` suffix when tests need a separate JVM
- **Flaky tests**: Annotate with `@Flaky` — they are skipped in CI by default

### Instrumentation module naming (CI-enforced)
- Module names **must end with a version number** (e.g., `couchbase-3.1`) or a configured suffix (`-common`, `-stubs`, `-iast`)
- Module names **must contain their parent directory name** (e.g., `play-2.4` under `play/`)
- Validated by: `./gradlew checkInstrumentationNaming`
- Modules under `:dd-java-agent:instrumentation:datadog` are exempt (internal features)

### InstrumenterModule separation
- `InstrumenterModule` (the module descriptor) should be **separate from** `Instrumenter` (the bytecode advice). Recent refactoring systematically separated these.
- Use `InstrumenterModule.Tracing` for tracing instrumentations, `InstrumenterModule.ContextTracking` for pure context propagation.
- Use `@AppliesOn` to override the target system when an advice applies to a different product than its module.

### Cleanup expectations
- Remove unused imports
- Remove duplicate condition checks
- Remove unnecessary semicolons
- Use `System.arraycopy()` instead of manual array copy loops
- Use Java-style array declarations (`String[] args`) not C-style (`String args[]`)

## PR conventions

- Title: imperative verb sentence describing user-visible change (e.g. "Fix span sampling rule parsing")
- Labels: at least one `comp:` or `inst:` label + one `type:` label
- Use `tag: no release note` for internal/refactoring changes
- Use `tag: ai generated` for AI generated code
- Open as draft first, convert to ready when reviewable

## Bootstrap constraints (critical)

Code running in the agent's `premain` phase must **not** use:
- `java.util.logging.*` — locks in log manager before app configures it
- `java.nio.*` — triggers premature provider initialization
- `javax.management.*` — causes class loading issues
- `UUID.randomUUID()` — can trigger `java.util.logging` initialization via ACCP. Use `RandomUtils.randomUUID()` instead.

See [docs/bootstrap_design_guidelines.md](docs/bootstrap_design_guidelines.md) for details and alternatives.

## Performance guidelines (critical)

The tracer runs inside every customer JVM. Overhead budget is extremely tight — every allocation, lock, and exception matters on the hot path.

### Allocation avoidance
- **Never use `Objects.hash()`** in hot paths — varargs creates an `Object[]` on every call. Use `HashingUtils.hash()` (fixed-arity overloads, zero allocation) or manually unroll the polynomial hash.
- **Avoid primitive boxing** — use typed setters (`TagMap.set(key, int)`, `DDSpanContext.setMetric(key, long)`) instead of `Object`-accepting methods. The v0.4/v0.5 serializers were specifically refactored to eliminate boxing.
- **Prefer `UTF8BytesString`** over `String` for span metadata (operation names, service names, resource names, tag keys). It lazily caches the UTF-8 byte representation, avoiding repeated `getBytes(UTF_8)` allocations during serialization.
- **Reuse objects per-thread** when possible — `CoreTracer.ReusableSingleSpanBuilder` pools span builders per thread, providing major throughput gains in constrained heaps.
- **Never throw exceptions for flow control** on hot paths — use pre-validation instead of catch-based parsing (e.g., fast-path format validation instead of catching `NumberFormatException`).
- **Avoid `String.split()`, `String.replaceAll()`, `String.replaceFirst()`** — these compile regexes internally. Use `Strings.replace()` from `datadog.trace.util.Strings` or manual parsing.
- **Use `StringBuilder` sparingly** — consider whether the string is actually needed or whether the computation can be done directly.

### Use existing performance utilities
- **Caching**: Use `DDCaches.newFixedSizeCache(capacity)` — NOT `ConcurrentHashMap` unless the key set is implicitly bounded. The `FixedSizeCache` family is open-addressing, lock-free, and bounded.
- **Hashing**: `HashingUtils.hash(a, b, c)` — overloads for 1-5 args, plus primitive overloads for `int`, `long`, `boolean`, etc.
- **Queues**: Use `Queues.newMpscArrayQueue()` / `Queues.newSpscArrayQueue()` from `datadog.common.queue` — VarHandle-based on Java 9+, JCTools on Java 8. Never use `LinkedBlockingQueue` or `ArrayBlockingQueue`.
- **Random UUIDs**: Use `RandomUtils.randomUUID()` — NOT `UUID.randomUUID()` which can trigger `java.util.logging` initialization during premain.
- **String operations**: `Strings.replace()`, `Strings.join()`, `Strings.truncate()` — regex-free alternatives.
- **Atomic fields**: Prefer `AtomicLongFieldUpdater`/`AtomicReferenceFieldUpdater` on `volatile` fields over `AtomicLong`/`AtomicReference` objects — saves 16+ bytes per instance. Critical for frequently-allocated objects like `DDSpan`.

### Serialization hot path
- The v0.4/v0.5 trace serializers are performance-critical. Changes to `TraceMapperV0_4` or `TraceMapperV0_5` must be benchmarked.
- Tag iteration uses `TagMap.EntryReader` to avoid boxing — use this API, not `getTag()` calls in tight loops.
- UTF-8 encoding uses `SimpleUtf8Cache` (tag names, low cardinality) and `GenerationalUtf8Cache` (tag values, mixed cardinality) — both tolerate benign races for performance.

### Benchmarking
- Performance-sensitive changes **must include JMH benchmarks** in the appropriate `src/jmh/` directory.
- Existing benchmarks live in `internal-api/src/jmh/`, `dd-trace-core/src/jmh/`, and `benchmark/`.
- Run benchmarks: `./gradlew :module:jmh`
- CI has a performance SLO gate — regressions in key benchmarks block merge.

## Forbidden APIs and common pitfalls

The build enforces forbidden API rules at compile time (`./gradlew forbiddenApisMain`). Key rules:

| Forbidden | Use instead |
|---|---|
| `Objects.hash(Object...)` | `HashingUtils.hash()` (in hot paths) |
| `String.split()` / `replaceAll()` / `replaceFirst()` | `Strings.replace()` or explicit `Pattern` |
| `UUID.randomUUID()` | `RandomUtils.randomUUID()` |
| `System.getenv()` / `System.getenv(String)` | `ConfigHelper.getenv()` |
| `System.out` / `System.err` | SLF4J logging (shaded `datadog.slf4j`) |
| `Class.forName(String)` | Framework-specific classloading utilities |
| `ElementMatchers.named()` / `.hasSuperClass()` | `NameMatchers` / `HierarchyMatchers` equivalents |
| `FixedSizeStripedLongCounter` | JDK `LongAdder` |
| `Field.set()` and reflection setters | Avoid mutating final fields (JEP-500) |

### Null safety
Many recent bug fixes address NPEs in decorators, extractors, and context propagation. When writing advice or decorator code:
- **Always null-check return values** from `AgentSpan.context()`, request/response getters, and header accessors.
- **Guard decorator calls** — e.g., `if (path != null) span.setResourceName(path)`.
- **Protect exception advice** from `NoClassDefFoundError` — the target class may not be loadable in all environments.

### Resource management
- **Close resources in reverse order** of opening.
- **Streams wrapping other streams** must delegate `close()` to the underlying stream.

## Thread safety and concurrency

### General rules
- **Prefer `volatile` over `AtomicInteger`/`AtomicReference`** for simple single-field CAS-free access.
- **Use `AtomicFieldUpdater` patterns** when CAS is needed on frequently-allocated objects — avoids allocating a separate `Atomic*` wrapper.
- **Tolerate benign race conditions** in caches — `FixedSizeCache` deliberately avoids synchronization; the worst case is a redundant computation. Do NOT add synchronization to cache code unless there is a correctness issue.
- **Lock `selector.selectedKeys()`** before iterating — NIO selector key sets are NOT thread-safe.
- **Reduce lock contention** — prefer lock-free structures (`VarHandle` queues, `ConcurrentHashMap`, `LongAdder`) over synchronized blocks.
- **Avoid class loading under locks** — can cause deadlocks. Preload classes before entering critical sections.

### Virtual threads
- Virtual thread context tracking is supported. `ThreadUtils.isVirtualThread()` checks at runtime.
- Thread-local reuse patterns (like `ReusableSingleSpanBuilder`) check for virtual threads and skip reuse if detected.

## Dependency and build rules

### Gradle conventions
- **Use lazy APIs** — `tasks.register()` not `tasks.create()`, `tasks.named()` not `tasks.getByName()`, `configureEach {}` not `all {}`. The build has ~630 projects and ~33,000 tasks; eager configuration causes major slowdowns.
- **Single GAV string** for dependencies: `implementation("group:artifact:version")` not the map syntax.
- **Use the version catalog** (`gradle/libs.versions.toml`) for shared dependencies. Instrumentation modules declare framework versions directly.
- **Instrumented framework deps must be `compileOnly`** — they must not leak into the agent jar.
- **Script plugins (`gradle/*.gradle`) are deprecated** — use convention plugins in `buildSrc/` for new work.
- **Agent jar size limit: 32 MB** — enforced by `checkAgentJarSize` task.

### Shading / relocation
- All third-party libraries bundled in the agent jar **must be relocated** (e.g., `org.slf4j` → `datadog.slf4j`, `okhttp3` → `datadog.okhttp3`).
- OpenTracing must **never** be a direct dependency — enforced at build time.
- See `dd-java-agent/build.gradle` `generalShadowJarConfig()` for the full relocation table.

## API boundaries

| Module | Visibility | Contents |
|---|---|---|
| `dd-trace-api/` | **Public** (published Maven artifact) | User-facing API: `@Trace`, `GlobalTracer`, `DDTags`, config constants, product APIs (LLMObs, AppSec, etc.) |
| `internal-api/` | **Internal** (not published) | Shared internals: `AgentSpan`, `AgentTracer`, `Config`, `TagMap`, caches, utilities |
| `dd-trace-core/` | **Internal** | Core engine: `DDSpan`, `CoreTracer`, serializers, writers |

### Rules
- Never add internal types to `dd-trace-api/` — it has zero dependencies on implementation.
- Use `CharSequence` over `String` in internal APIs — enables `UTF8BytesString` passthrough without allocation.
- Every public API must have a **NoOp implementation** for when the agent is not loaded or a feature is disabled.
- **Deprecation**: annotate with `@Deprecated` + Javadoc pointing to the replacement + provide a default method that delegates to the new API. Never remove without a deprecation cycle.
- Config keys use dot-separated lowercase without `dd.` prefix (e.g., `trace.agent.url`). The `DD_` env var prefix is applied automatically.
