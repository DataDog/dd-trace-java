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

## Developer Tools

### MCP Tools

* Datadog MCP defaults: When using Datadog MCP tools (CI pipelines, tests, flaky tests, coverage, PR insights), default to the repository `github.com/DataDog/dd-trace-java` (or `https://github.com/DataDog/dd-trace-java` for PR-related tools) unless the user specifies a different repository. Use the current git branch and latest commit SHA as context when relevant.

### GitHub Tools

- When interacting with GitHub (github.com), ALWAYS use `gh` on the command line.
- If `gh` CLI is not installed or there are authentication or configuration problems, check the document: [AI Tools Integration Guide - GitHub CLI](docs/ai-tools-integration-guide.md#github-cli-gh)

### GitLab Tools

- When interacting with GitLab (gitlab.ddbuild.io), ALWAYS use `glab` on the command line.
- If `glab` CLI is not installed or there are authentication or configuration problems, check the document: [AI Tools Integration Guide - GitLab CLI](docs/ai-tools-integration-guide.md#gitlab-cli-glab)

### Handling the Pull Request and CI

- If the request relates to CI status for a branch or PR, MUST ALWAYS try the Datadog MCP first.
- Use local GitLab/GitHub tools as a fallback/support for the Datadog MCP when interacting with GitLab/GitHub CI.
