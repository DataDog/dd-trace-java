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
```

## Code conventions

- **Formatting**: google-java-format enforced via Spotless. Run `./gradlew spotlessApply` before committing.
- **Static imports**: Prefer static imports over class-qualified calls for call-style helpers — JUnit `Assertions`, Mockito (`mock`, `when`, `verify`, `anyString`, `RETURNS_DEFAULTS`, ...), Hamcrest/AssertJ matchers, internal test DSLs, and similar. Same goes for production code: `Collections.emptyList()` is fine, but if you find yourself repeatedly writing `Foo.bar(...)` where `Foo` adds no information at the call site, static-import `bar`. Wildcard `import static x.*` is disallowed (enforced by IDE config in CONTRIBUTING.md).
- **Instrumentation layout**: `dd-java-agent/instrumentation/{framework}/{framework}-{minVersion}/`
- **Instrumentation pattern**: Type matching → Method matching → Advice class (bytecode advice, not AOP)
- **Test frameworks**: Always use JUnit 5 for unit tests. Only use Groovy / Spock tests for instrumentation and smoke tests.
- **Forked tests**: Use `ForkedTest` suffix when tests need a separate JVM
- **Flaky tests**: Annotate with `@Flaky` — they are skipped in CI by default
- **Instrumentation one-shot methods**: Never extract the return values of `triggerClasses()`, `contextStore()`, `classLoaderMatcher()`, or `methodAdvice()` into static constants. These are called once by the framework — extracting to a constant adds constant-pool bloat with no benefit.

## PR conventions

- Title: imperative verb sentence describing user-visible change (e.g. "Fix span sampling rule parsing")
- Labels: always add `tag: ai generated` and at least one `comp:` or `inst:` label + one `type:` label
- Use `tag: no release note` for internal/refactoring changes
- Open as draft first, convert to ready when reviewable

## Bootstrap constraints (critical)

Code running in the agent's `premain` phase must **not** use:
- `java.util.logging.*` — locks in log manager before app configures it
- `java.nio.file.*` — triggers premature provider initialization
- `javax.management.*` — causes class loading issues

See [docs/bootstrap_design_guidelines.md](docs/bootstrap_design_guidelines.md) for details and alternatives.
