# Agent context for dd-trace-java

## What is this project?

Datadog Java APM agent (`dd-trace-java`): a Java agent that auto-instruments JVM applications at runtime via bytecode manipulation.
It ships ~120 integrations (~200 instrumentations) for tracing, profiling, AppSec, IAST, CI Visibility, USM, and LLM Observability.

## Project layout

```
dd-java-agent/            Main agent
  instrumentation/        All auto-instrumentations (one dir per framework)
  agent-bootstrap/        Bootstrap classloader classes
  agent-builder/          Agent build & bytecode weaving
  agent-tooling/          Shared tooling for instrumentations
  agent-{product}/        Product-specific modules (ci-visibility, iast, profiling, debugger, llmobs, aiguard, ...)
  appsec/                 Application Security (WAF, threat detection)
dd-trace-api/             Public API & configuration constants
dd-trace-core/            Core tracing engine (spans, propagation, writer)
dd-trace-ot/              OpenTracing compatibility layer
internal-api/             Internal shared API across modules
products/                 Sub-products (feature flagging, metrics)
communication/            HTTP transport to Datadog Agent
components/               Shared low-level components
remote-config/            Remote configuration support
telemetry/                Agent telemetry
utils/                    Shared utility modules (config, time, socket, test, etc.)
metadata/                 Supported configurations metadata & requirements
benchmark/                Performance benchmarks
dd-smoke-tests/           Smoke tests (real apps + agent)
docs/                     Developer documentation (see below)
```

## Key documentation (read on demand, don't load upfront)

| Topic | File |
|---|---|
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
- **Instrumentation layout**: `dd-java-agent/instrumentation/{framework}/{framework}-{minVersion}/`
- **Instrumentation pattern**: Type matching → Method matching → Advice class (bytecode advice, not AOP)
- **Test frameworks**: JUnit 5 (preferred for unit tests), Spock 2 (for complex scenarios needing Groovy)
- **Forked tests**: Use `ForkedTest` suffix when tests need a separate JVM
- **Flaky tests**: Annotate with `@Flaky` — they are skipped in CI by default

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

See [docs/bootstrap_design_guidelines.md](docs/bootstrap_design_guidelines.md) for details and alternatives.
