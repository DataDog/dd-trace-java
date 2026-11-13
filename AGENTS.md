# Agent context for dd-trace-java

Datadog Java APM agent (`dd-trace-java`): a Java agent that auto-instruments JVM applications at runtime via bytecode manipulation.
The agent provides tracing, profiling, application security (AppSec and IAST), CI Visibility, Universal Service Monitoring (USM), and LLM Observability.

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
| Project layout & design | [ARCHITECTURE.md](ARCHITECTURE.md) |
| Building from source | [BUILDING.md](BUILDING.md) |
| Contributing & PR guidelines | [CONTRIBUTING.md](CONTRIBUTING.md) |
| How instrumentations work | [docs/how_instrumentations_work.md](docs/how_instrumentations_work.md) |
| Adding a new instrumentation | [docs/add_new_instrumentation.md](docs/add_new_instrumentation.md) |
| Adding a new configuration | [docs/add_new_configurations.md](docs/add_new_configurations.md) |
| Testing | [docs/how_to_test.md](docs/how_to_test.md) |
| Reproducing timing-sensitive CI flakes | [Reproduction guide](docs/how_to_test.md#reproducing-timing-sensitive-ci-failures) |
| Writing JUnit tests | [docs/how_to_test_with_junit.md](docs/how_to_test_with_junit.md) |
| Writing a smoke test | [docs/how_to_smoke_test.md](docs/how_to_smoke_test.md) |
| Working with Gradle | [docs/how_to_work_with_gradle.md](docs/how_to_work_with_gradle.md) |
| Bootstrap/premain constraints | [docs/bootstrap_design_guidelines.md](docs/bootstrap_design_guidelines.md) |
| Instrumentation/advice constraints | [docs/instrumentation_design_guidelines.md](docs/instrumentation_design_guidelines.md) |
| GitHub Actions workflows | [.github/workflows/README.md](.github/workflows/README.md) |

**When working on a topic above, read the linked file first** — they are the source of truth maintained by humans.

## Build quick reference

```shell
./gradlew clean assemble                  # Build without tests
./gradlew :dd-java-agent:shadowJar        # Build agent jar only (dd-java-agent/build/libs/)
./gradlew :path:to:module:test             # Run tests for a specific module
./gradlew :path:to:module:test -PtestJvm=11  # Test on a specific JVM version
./gradlew spotlessApply                   # Auto-format code (google-java-format)
./gradlew spotlessCheck                   # Verify formatting
```

## Code conventions

- **Formatting**: google-java-format enforced via Spotless. Run `./gradlew spotlessApply` before committing.
- **Static imports**: Prefer static imports for call-style helpers; no wildcard imports. See [the import guidelines](CONTRIBUTING.md#static-imports).
- **Documentation**: Use concise Javadoc comments (`/** ... */`) for class, method, and field documentation.
- **Tests**: Write new tests in Java with JUnit 5. Maintain existing Spock tests as needed; new `.groovy` files require an explicit exception. Follow the testing guides above for parameterization, forked tests, and flaky tests.

## PR conventions

- Follow the [contribution guidelines](CONTRIBUTING.md#pull-request-guidelines) and [PR template](.github/pull_request_template.md) for titles, descriptions, size, labels, and draft status.
- Dependency changes: when adding, updating, or removing dependencies, regenerate all affected `gradle.lockfile` files
  and include them in the same PR. See [lockfile updates](docs/how_to_work_with_gradle.md#updating-lock-files).

## Code Review Rules

- **SDK review**: run `/sdk-review` over the committed branch changes before pushing or marking a PR ready. It runs one read-only review per lens (correctness, security, design, performance, maintainability, conventions, cross-SDK consistency) — see [.agents/skills/sdk-review/SKILL.md](.agents/skills/sdk-review/SKILL.md).
- **CI flakiness**: use [reproduce-ci-flakiness](.agents/skills/reproduce-ci-flakiness/SKILL.md) for intermittent timeouts, races, asynchronous test failures, or tests that pass locally but fail in CI.
- **Technical debt**: run `/techdebt` over branch changes before marking a PR ready to catch code duplication, unnecessary complexity, and dead code (refactor-only, never changes behavior) — see [.agents/skills/techdebt/SKILL.md](.agents/skills/techdebt/SKILL.md).
- **Performance**: run `/perf-review` over branch changes before marking a PR ready (advisory, not a merge gate) — see [.agents/skills/perf-review/SKILL.md](.agents/skills/perf-review/SKILL.md).
- **Correctness contracts**: an annotation meta-annotated `@CorrectnessContract` (in `datadog.contract`, e.g. `@Confined`) states a rule code must follow, with a *Checker contract* in its Javadoc. When a change uses a type that carries one, check the change against that contract: a violation is a bug, and there is no suppression.

### Core performance rules

These rules are strict for shipped runtime code in `dd-trace-core/src/main/` and `internal-api/src/main/`. Elsewhere, including those modules' tests, benchmarks and build files, treat them as advice.

- **Measure performance claims.** A PR that claims to be faster or to allocate less must include JMH before/after results from the same machine, JDK, JVM flags and benchmark settings, with time and allocation (`-Pjmh.profilers=gc`). Without them, reviewers should flag the claim as unmeasured and the author should either add numbers or drop the claim.

## Critical constraints

- **Bootstrap**: never use `java.util.logging.*`, `java.nio.file.*`, or `javax.management.*` in `premain` code — see [docs/bootstrap_design_guidelines.md](docs/bootstrap_design_guidelines.md).
- **Advice**: never call a static interface method from advice — can cause a `VerifyError` — see [docs/instrumentation_design_guidelines.md](docs/instrumentation_design_guidelines.md).
