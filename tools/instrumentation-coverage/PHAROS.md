# Pharos — Instrumentation Quality

Pharos joins library knowledge with agent-enabled test observations. The portable report helps a
reviewer investigate a behavior, inspect a test's observations, and download an evidence-backed task.
Production instrumentation is not changed by the workflow.

## Start with an onboarded module

From the repository root, with the normal Gradle/JDK environment:

```sh
python3 tools/instrumentation-coverage/workflow.py run \
  --module dd-java-agent/instrumentation/rxjava/rxjava-3.0
```

The command resolves versioned artifacts, generates/reuses the graph, validates module knowledge,
runs the configured tests with collection, validates collection health, seals the run inputs, and
prints `build/instrumentation-coverage/RUN_ID/pharos/index.html`. This same command works for Spring
MVC. See [README.md](README.md) for onboarding and graph/catalog authoring.

To verify and replay a saved successful run without running tests:

```sh
python3 tools/instrumentation-coverage/pharos.py report --run-directory /absolute/path/to/RUN_ID
```

Replay rejects missing, added, or modified sealed evidence and failed runs. It produces the existing
full report and the compact Pharos view. Graph generation, joining, rendering and replay do not use
an LLM. The catalog remains authored/reviewed knowledge, not an automatically verified specification.

## One viewer, two evidence levels

The generic [viewer](portal/index.html) can open any generated Pharos `report.json` through **Load
report**. Each generated `index.html` also embeds its data and lighthouse, so it works offline.
Library identity, version, stages and observations come from data, not Spring-specific UI code.

- **Headline:** verified behaviors / declared mapped behaviors, displayed as a percentage.
  Candidate families are outside this denominator. Catalog review status does not hide the score
  or add an omissions panel; inventory bookkeeping stays in authoring artifacts. The percentage
  describes the declared scope, not complete library correctness.
- **Execution evidence:** unique reference-method hits, Context observations and per-test details
  remain available beneath the headline. Shared method hits cannot verify a scenario.

See [catalog reconciliation](CATALOG_RECONCILIATION.md) for required inputs and migration of older KBs.

Rows and corridors default to the union across associated and candidate tests. Green means at least
one Context-present observation; gray means root-only; hatching means not observed. Outside-collection
methods remain unknown. Root counts are preserved in drilldown tooltips and downloads, even when
aggregate tiles are green. Selecting a test shows only its observations, including mixed states.
An aggregate does not establish call order or that a single test covers the entire behavior.
Downloads explicitly identify aggregate versus selected-test scope and the contributing test IDs.

An execution-only report can have every reference method hit with zero verified behaviors; this does not produce a 100% headline. For example, successful
Guava dispatch reaches the executor-rejection reference methods without testing rejection.

The RxJava run demonstrates shared-viewer portability. The generic assessment handoff has also
been checked on its empty-Maybe completion test using local assertion evidence, without upstream
recordings. This is one inspected behavior, not a complete assessment of the RxJava catalog.

## Build an assessed behavioral report

Use the generic [LLM workflow](WORKFLOW.md), driven by the `instrumentation-quality` skill:

```sh
python3 tools/instrumentation-coverage/pharos.py prepare \
  --report /path/to/RUN/pharos/report.json \
  --source path/to/Test.java --source path/to/Helper.java \
  --output /path/to/iteration-01
# LLM: read dossier.json, author exact assertion bindings in assessment.json.
python3 tools/instrumentation-coverage/pharos.py assess --session /path/to/iteration-01/session.json
```

After changing tests and collecting a fresh run, prepare a new iteration with `--previous` pointing
to the old assessment. Previous bindings are hints requiring reassessment, never silently carried
support. `pharos.py resume --session PATH` prints the exact saved handoff.

This works with ordinary module reports; upstream runtime recording is optional. The earlier
`pharos.py build` command remains available for the Spring upstream-runtime pilot, as documented
in [its workflow](cartography/pilot/WORKFLOW.md). `render`/`validate` provide structural report checks;
use `report` for sealed-run replay and `assess` for current assertion/source checks.

## Improve one behavior

1. Open the report, select a behavior and test, and download its task. The JSON contains the selected
   evidence, library/version, report identity, run metadata, available rerun commands, reference,
   source claims and limitations. Selecting a stage adds focus without discarding other evidence.
2. Inspect the candidate's input and assertions. Similar method execution alone is insufficient.
   Decide whether the test already suffices, needs stronger assertions, or needs a new scenario.
3. Add the narrowest justified test. Inspect local fixture behavior before assuming HTTP status or
   trace structure. Preserve failing evidence; do not change production code just to make it green.
4. Run `workflow.py run` again. Review/rebind the source assessment for the fresh evidence, then build
   a new report. Keep the baseline artifact.
5. Compare equivalent scope:

```sh
python3 tools/instrumentation-coverage/pharos.py compare \
  --baseline /path/to/baseline/pharos/report.json \
  --candidate /path/to/candidate/pharos/report.json \
  --output /path/to/comparison.json
```

Comparison requires the same library/version, evidence basis, behavior inventory, reference method
sets and method eligibility. It separately lists assessed-test, candidate-test and observed-method
changes. It does not interpret every runtime variation as an improvement or verify request lineage.

## Validation and boundaries

```sh
python3 -B -m unittest discover -s tools/instrumentation-coverage/tests -v
node tools/instrumentation-coverage/cartography/pilot/capture-preview.mjs /path/to/pharos
```

The Python checks use synthetic fixtures and need no generated Spring data. The browser check uses
local Chrome and Node with WebSocket support. It exercises behavior/test/reference selection,
method drilldowns, exact task downloads, title identity and mobile width, and writes `overview.png`
and the downloaded tasks. Historical pilot tests additionally require the local upstream artifacts.

Collection confidence and attribution remain separate from correctness: initiating-thread labels
can be exact while worker observations are temporally associated with the active test window. No
causal async graph is inferred. Unobservable methods remain unknown; no matching test leaves the
method panel neutral. Failed collections retain diagnostics and do not emit a normal success report.
Original collection runs do not contain immutable snapshots of all inherited test sources. Keep
rendered baseline artifacts; rebuilding historical source assessments can require the original
source revision. Automatic semantic reassessment and a cross-language harness remain future work.

See [the completed missing-body experiment](cartography/pilot/experiments/missing-body/README.md)
for the first report → task → test → collection → assessed-report comparison with this viewer.
