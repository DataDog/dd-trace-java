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
| Writing JUnit tests | [docs/how_to_test_with_junit.md](docs/how_to_test_with_junit.md) |
| Writing a smoke test | [docs/how_to_smoke_test.md](docs/how_to_smoke_test.md) |
| Working with Gradle | [docs/how_to_work_with_gradle.md](docs/how_to_work_with_gradle.md) |
| Bootstrap/premain constraints | [docs/bootstrap_design_guidelines.md](docs/bootstrap_design_guidelines.md) |
| Instrumentation/advice constraints | [docs/instrumentation_design_guidelines.md](docs/instrumentation_design_guidelines.md) |
| CI/CD workflows | [.github/workflows/README.md](.github/workflows/README.md) |

**When working on a topic above, read the linked file first** — they are the source of truth maintained by humans.

## Gradle changes

Before editing files under `buildSrc/` or `build-logic/`, read that folder's
`AGENTS.md`. These local instructions supplement the rules below.

### Required skill

- Before modifying Gradle scripts, settings, properties, version catalogs, or task/plugin
  source, use the official `gradle-best-practices` skill in audit mode. Scope the audit to
  the requested change and its affected consumers.
- The complete installed or repository-provided skill bundle, including its reference
  files, must be available. A skill name or remote link alone does not satisfy this prerequisite.
- If unavailable or incomplete, report: "gradle-best-practices is required for this change
  but is unavailable." Provide the [official installation instructions](https://github.com/gradle/gradle-skills#installation).
  Continue read-only investigation, but make no Gradle changes until the skill is available.
- In Codex, invoke `$gradle-best-practices`. Its `$skill-installer`, when available, can
  install the `skills/gradle-best-practices` directory from `gradle/gradle-skills`.
  In Claude Code, install and invoke the official plugin:

  ```text
  /plugin marketplace add gradle/gradle-skills
  /plugin install gradle-skills@gradle-skills
  /gradle-skills:gradle-best-practices
  ```

- Check recommendations against the pinned Gradle wrapper and the JVMs that execute the
  affected code. A newer or nightly catalog may describe APIs unavailable to this build.
- Keep fixes within the requested scope. Record unrelated findings separately. An audit
  does not authorise wrapper upgrades, dependency updates, or structural migrations.

### Guardrails

- Find affected plugin IDs, extensions, properties, tasks, fixtures, and consuming modules
  before editing. Preserve their contracts and defaults unless the request changes them.
- Prefer `register`, `named`, `configureEach`, and provider composition. Avoid eager task
  traversal, dependency resolution, external processes, and expensive work during configuration.
  Check when providers are consumed and whether the receiving API supports them.
- Wire producer outputs into consumer inputs where data flows. Preserve the distinction
  between execution dependencies, ordering, lifecycle aggregation, and finalisation.
  `mustRunAfter` does not cause a prerequisite task to run.
- Keep task execution independent of the live `Project` model. Use declared properties
  and supported injected services. Declare relevant inputs and distinct outputs, and choose
  cacheability and path sensitivity according to the task's behaviour.
- Use public Gradle APIs. Avoid new cross-project mutable configuration and lifecycle
  callbacks such as `afterEvaluate` when providers or plugin callbacks express the relationship.
- Sharing a source root, such as `buildSrc`, does not make plugins one component. Integrate
  through plugin IDs, documented extensions, and Gradle model APIs. Avoid another plugin's
  implementation classes, internal helpers, and incidental task layout.
- Apply required plugins explicitly; use `withPlugin` for optional integrations. Extract
  shared code only for shared knowledge. Prefer small local duplication over premature coupling.
- Verify changed behaviour with focused tests and a representative consumer. When changing
  configuration-cache behaviour, verify both storage and reuse. When changing incremental or
  build-cache behaviour, inspect task outcomes and artifacts. Preserve validation and diagnostics.

### Documentation boundary

Update [docs/how_to_work_with_gradle.md](docs/how_to_work_with_gradle.md) only when a change
affects general Gradle guidance or practices broadly applicable to this repository. This
includes repository constraints that prevent following a best practice. Preserve the project
specifics intentionally documented there. Keep new plugin implementation details, local
workarounds, and task-specific procedures in source comments or local documentation.

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
- **Static imports**: Prefer static imports over class-qualified calls for call-style helpers, in both test (Assertions.assertEquals, Mockito.mock) and production code (Collections.emptyList). Wildcard imports disallowed — see CONTRIBUTING.md.
- **Documentation**: Use concise Javadoc comments (`/** ... */`) for class, method, and field documentation.
- **Instrumentation layout**: `dd-java-agent/instrumentation/{framework}/{framework}-{minVersion}/`
- **Instrumentation pattern**: Type matching → Method matching → Advice class (bytecode advice, not AOP)
- **Test frameworks**: Always use JUnit 5 for unit tests. Only use Groovy / Spock tests for instrumentation and smoke tests.
- **Parameterized tests**: Prefer `@TableTest` for multi-column literal data, with named scenarios and aligned columns. Prefer a simple `@TypeConverter` over switching to `@MethodSource` when it can turn table values into the required arguments. Use `@MethodSource` for cases requiring complex object construction, builders, or mocks. See [the JUnit testing guide](docs/how_to_test_with_junit.md#parameterized-tests).
- **Forked tests**: Use `ForkedTest` suffix when tests need a separate JVM
- **Flaky tests**: Annotate with `@Flaky` — they are skipped in CI by default

## PR conventions

- Title: imperative verb sentence describing user-visible change (e.g. "Fix span sampling rule parsing")
- Labels: always add `tag: ai generated` and at least one `comp:` or `inst:` label + one `type:` label
- Use `tag: no release notes` for internal/refactoring changes
- Open as draft first, convert to ready when reviewable
- Dependency changes: when adding, updating, or removing dependencies, regenerate all affected `gradle.lockfile` files
  and include them in the same PR. See [lockfile updates](docs/how_to_work_with_gradle.md#updating-lock-files).

## Review Guidelines

- **Technical debt**: run `/techdebt` over branch changes before marking a PR ready to catch code duplication, unnecessary complexity, and dead code (refactor-only, never changes behavior) — see [.agents/skills/techdebt/SKILL.md](.agents/skills/techdebt/SKILL.md).
- **Performance**: run `/perf-review` over branch changes before marking a PR ready (advisory, not a merge gate) — see [.agents/skills/perf-review/SKILL.md](.agents/skills/perf-review/SKILL.md).

## CI scripts

Every `curl` that runs in CI (`.gitlab-ci.yml`, `.gitlab/`, GitHub Actions, the scripts they call, and the Dockerfiles they build) should:

- **Fail on HTTP errors**: pass `-f`/`--fail`. Without it, a 4xx/5xx exits 0 and the error page is used as if it were the real response (e.g. piped into `bash` or `jq`).
- **Retry transient errors**: pass `--retry 5 --retry-delay 2` (add `--retry-connrefused` for remote endpoints).
- **Propagate the failure**: the job must stop when curl fails. Never pipe `curl` straight into `bash`/`sh`: download to a file with `-f`, then run it.

Example: `curl -fsSL --retry 5 --retry-delay 2 --retry-connrefused -o out.tar.gz "$url"`

Exception: a script that checks the HTTP status itself (`-w "%{http_code}"`, e.g. `.gitlab/check_test_agent_results.sh`) may skip `--fail`, but still needs `--retry`.

## Critical constraints

- **Bootstrap**: never use `java.util.logging.*`, `java.nio.file.*`, or `javax.management.*` in `premain` code — see [docs/bootstrap_design_guidelines.md](docs/bootstrap_design_guidelines.md).
- **Advice**: never call a static interface method from advice — can cause a `VerifyError` — see [docs/instrumentation_design_guidelines.md](docs/instrumentation_design_guidelines.md).
