# Pharos instrumentation quality workflow

Start with [WORKFLOW.md](WORKFLOW.md) for catalog → upstream recording → local collection →
automatic fingerprint classification → report. Manual assertion review is optional, not a gate.
The current reference classifier/report command is `reference/report.py`.
See [ARCHITECTURE.md](ARCHITECTURE.md) for the architecture and
[PHAROS.md](PHAROS.md) for the report and replay interface.
See [the Guava validation](experiments/guava-listeners.md) for a complete onboarding and test-improvement run.

This workflow turns an existing agent-enabled instrumentation test run into an inspectable library
coverage report. Library execution, Context presence, and missing observations remain separate.
It uses a static graph and reviewed flow definitions to organize evidence; it does not claim to
record runtime call edges or prove correct request Context identity.

The workflow supports Spock `InstrumentationSpecification` and JUnit Jupiter
`AbstractInstrumentationTest`. This directory owns the CLI, standalone Gradle engine, graph
analyzer, method observer, evidence join, schemas, tests, and shared viewer. Instrumentation modules
own only their versioned functional knowledge. Ordinary tests remain opt-out.
Python 3 (standard library only) and the repository Gradle/JDK environment are required. Use JDK 21
for the supported Spring collection run.

## Existing module collection and legacy static KB commands

Use `.agents/skills/instrumentation-quality/SKILL.md` for the end-to-end workflow. It generates the
graph, invokes functional-knowledge authoring, collects both executions and classifies our tests.
The commands below are the existing collection and legacy static KB interface; follow WORKFLOW
for the upstream-reference classification step. They are the
deterministic automation and CI interface.

Initialize a new module after inspecting its build and instrumentation matchers:

```sh
python3 tools/instrumentation-coverage/workflow.py init \
  --module dd-java-agent/instrumentation/example/example-1.0 \
  --library group:artifact --version 1.0 --adapter spock \
  --artifact-group group --observe-class example.LibraryType \
  --required-transformed example.LibraryType
```

For core JDK instrumentation, bind the graph and version identity to the same toolchain that runs
the test task:

```sh
python3 tools/instrumentation-coverage/workflow.py init \
  --module dd-java-agent/instrumentation/java/example-jdk-1.8 \
  --library jdk:java.base --version 25.0.2+10-LTS --jdk-module java.base \
  --adapter spock --observe-class java.util.concurrent.FutureTask \
  --required-transformed java.util.concurrent.FutureTask
```

The exported `java.base.jmod` comes from the selected `-PtestJvm` launcher. A runtime-version change
invalidates the knowledge identity and requires graph regeneration and semantic review.
Use `--additional-test-task forkedTest` when a module's declared instrumentation coverage is split
across ordinary and forked suites. The tasks must use the same selected JDK; their observations and
task-specific JUnit XML directories are joined into one run.

Generate the exact resolved-artifact graph, author knowledge against the printed graph, and validate
it before collection:

```sh
python3 tools/instrumentation-coverage/workflow.py graph --module MODULE
python3 tools/instrumentation-coverage/workflow.py validate-knowledge --module MODULE
```

`knowledge` remains a compatibility alias for `validate-knowledge`.

From the repository root:

```sh
python3 tools/instrumentation-coverage/workflow.py run \
  --module dd-java-agent/instrumentation/spring/spring-webmvc/spring-webmvc-6.0
```

This builds the observer, resolves the actual test runtime, generates or reuses the static graph,
validates flow bindings, runs the existing module `test` task with collection enabled, checks the
collection, and generates the report. The final lines print the shared viewer and report-data locations. No LLM is involved in
this execution. Test failures or incomplete collection fail the workflow; they are not turned into
a successful quality report.

To regenerate a report from an existing successful run:

```sh
python3 tools/instrumentation-coverage/workflow.py report \
  --run-directory /absolute/path/to/module/build/instrumentation-coverage/RUN_ID
```

Replay verifies the complete evidence file inventory and hashes before joining. It does not read
current module definitions or scan historical runs. Modified or added evidence is rejected.

Compare a recovery run with its baseline:

```sh
python3 tools/instrumentation-coverage/workflow.py compare \
  --baseline /path/to/baseline/report/report.json \
  --candidate /path/to/candidate/report/report.json
```

## Read the report

Each run is isolated below the module:

```text
build/instrumentation-coverage/<run-id>/
  manifest.json             # lifecycle, run identity, evidence hashes
  resolved.json             # actual selected artifacts, paths, SHA-256
  graph-identity.json       # graph cache identity and implementation inputs
  knowledge/                # saved library, flow, and observation definitions
  graph/                    # lossless raw graph plus derived analyses
  binding/                  # validated flow bindings before collection
  observations/worker-*/     # finalized per-specification Context reports
  test-results/             # JUnit XML from this execution
  report/
    report.json             # joined data, including run provenance
    joined-report.html      # portable snapshot with embedded data
    tasks/                  # proposed test tasks, JSON and Markdown
    schemas/                # report/task contracts
```

Open the shared [viewer](viewer/index.html) and select the run's `report/report.json` in the file
picker. The generic HTML is kept only in this shared folder, outside instrumentation modules.
For a served repository, pass the data URL explicitly:

```text
/tools/instrumentation-coverage/viewer/index.html?report=/path/to/run/report/report.json
```

`report/joined-report.html` is an optional standalone snapshot with embedded data, specific to that
run. It is distinct from the shared viewer.

Flow cards show declared flows and candidate families in one inventory. Candidate cards count their
representative entry methods and tests, use a dashed outline, and remain labeled as entry evidence
until their full corridor is reviewed. Click a mapped flow to see its identifying tests, then select
a test or evidence pattern to inspect the corridor and method neighborhood. Context/root counts are
method-entry observations. Static paths are possible routes, not observed runtime call sequences.

The report exposes downloadable action bundles when the evidence supports a next investigation:
add a missing flow test, expand the entry inventory, inspect an expected but unobserved route, or
investigate root/mixed Context observations. Each bundle contains the flow contract, evidence,
provenance, commands, and validation criteria. It proposes an experiment rather than asserting a
defect. After making a change, run its collection command to obtain fresh evidence. Running ordinary
tests alone does not refresh the coverage report.

## What to maintain

The instrumentation module owns six reviewed files under `coverage/`:

| File | Responsibility |
| --- | --- |
| `library.json` | Library coordinates, reviewed version, selected artifact groups, test task, runtime configuration |
| `catalog.json` | Versioned functionality-family inventory: mapped flows, candidates needing review, and explicit exclusions |
| `flows.json` | Feature catalog, scope, source references, identifying predicates, corridor methods, optional exercise recipes |
| `observation.json` | Explicit class inventory, expected production-transformed types, collector adapter and attribution policy |
| `evidence.json` | Source references and claim-level provenance used to review the catalog |
| `knowledge-review.md` | Human-readable review notes, boundaries, ambiguities, and unresolved graph gaps |

Keep these definitions in source control. Generated graphs and observations stay under `build/`.
The viewer lives once at `tools/instrumentation-coverage/viewer/index.html`.

Use `.agents/skills/instrumentation-quality/SKILL.md` for the complete lifecycle and
`.agents/skills/library-flow-knowledge/SKILL.md` for semantic authoring alone. See
[AUTHORING.md](AUTHORING.md) for onboarding and upgrades,
[KNOWLEDGE_FORMAT.md](KNOWLEDGE_FORMAT.md) for graph queries and provenance,
[RECOVERY.md](RECOVERY.md) for evidence-driven improvements, and
[ARCHITECTURE.md](ARCHITECTURE.md) for collection and join semantics.

## CI usage

Invoke the same `run` command in the selected instrumentation job and publish the printed run
directory as a job artifact, including `manifest.json`. Preserve the failing directory and logs
when the command exits nonzero. This implementation does not install a CI quality gate or publish
artifacts automatically.

The Gradle bridge applies only to this invocation. It disables test caching/up-to-date reuse and
runs one test worker at a time; Spock specifications/features are serialized and Jupiter parallel
execution is disabled in JUnit mode.
Configuration caching is explicitly disabled for this first bridge. The graph cache is local under
`tools/instrumentation-coverage/build/graphs/`; it is not yet a remote build cache integration.

## Validation

```sh
python3 -m unittest discover -s tools/instrumentation-coverage/tests -v
./gradlew -p tools/instrumentation-coverage test spotlessCheck
```

The Python checks cover replay isolation, modified/foreign evidence, failed runs, version mismatch,
and invalid flow predicates. The Java tests exercise the collector's Context and lifecycle behavior.
The `run` command validates the complete Spring integration.

## Current boundaries

- JUnit collection requires `AbstractInstrumentationTest` and `"adapter": "junit"`. Ordinary unit
  tests still execute, but are excluded from collection. See [JUNIT.md](JUNIT.md).
- The catalog is a bounded, draft selection of 17 flows, not complete Spring behavioral coverage.
- Reports keep a stable adapter-owned scenario ID separate from the human test name. Each method
  observation records attribution provenance and confidence: initiating-thread labels are exact,
  serialized-window worker labels are temporal, and unattributed entries have no confidence.
  Late asynchronous work can overlap another test window, so temporal evidence remains unsuitable
  for a universal gate or a claim of request-level causal identity.
- Flow predicates prove that anchor methods appeared in a test window, not order or a single request.
- Optional Reactive Streams dependencies still prevent observing Spring invocation helper classes;
  those methods remain outside the entry inventory.
- Coverage runs currently require successful tests and healthy finalized reports. Failed-run evidence
  remains available on disk for diagnosis, without a normal successful report.
- Profiling is excluded from this view. No universal coverage percentage or automatic instrumentation
  fix is generated.
- Bootstrap-loaded JDK targets use a minimal bootstrap bridge. It forwards entry notifications to
  the collector and never retains or activates Context. Named JDK modules receive only the read edge
  required to call that bridge while collection is enabled.

## Validate the JUnit adapter

```sh
python3 tools/instrumentation-coverage/validate-junit.py
```

This runs the RxJava 3 suite twice (baseline, then observed), preserves both result sets, and checks
outcome equivalence, production instrumentation, bootstrap Context, finalized collection, and
per-invocation attribution. It writes `validation.json` below the RxJava module's
`build/junit-coverage-validation/<run-id>/`. This is collector validation; it does not create a
functional flow map for RxJava.
