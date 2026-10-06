# Behavioral evidence pilots

Open `../build/behavior-pilot/index.html`. The original execution-family report is preserved and linked
for comparison. This pilot reuses the successful Spring 6.0.2 allTests run: no new tests or production
instrumentation changes.

The same builder and viewer also produce `../build/request-body-pilot/index.html`: four request-body
variants, with three inspected JSON-body test executions and three variants still unassessed.
The JSON test checks the response body and converted-body trace tag; it does not explicitly assert
HTTP status. Upstream component tests remain separate from local HTTP evidence.

For the repeatable prepare → assess → validate → build process, see [WORKFLOW.md](WORKFLOW.md).
The assessment data contract is documented in [FORMAT.md](FORMAT.md).

## Model

The async capability contains five behavioral variants. API types are dimensions, not necessarily separate
capabilities. Each variant records a trigger, expected outcome, supporting upstream source, scope,
inspected local associations, statistical candidates and review status. Context colors appear only
in the method evidence. Behavioral support does not derive from a similarity threshold.

`assessment.json` and `request-body-assessment.json` are explicit LLM-authored assessments,
not discovered contracts. The schema records source citations, claims and exact test bindings. Each pins current
local source files and names the upstream tests to inspect. `build.py` checks those source hashes and
uses exact target/suite/test identities to attach the saved results. Validation also checks citation
quotes and input fingerprints; it does not prove semantic correctness of the claims. Changes to source require
reassessment. The original run did not save source snapshots; that provenance limitation is visible.
The model supports partial evidence, but this first pass uses supported or not-assessed statuses.

Inspected local tests establish successful/error HTTP outcomes for Callable, WebAsyncTask and an
already-completed DeferredResult, across three suite configurations (18 executions total). Their
trace assertions establish shared trace identity and a nonzero parent, not exact parent identity,
scope restoration or request isolation. CompletionStage is not yet assessed locally in this pilot.

Delayed DeferredResult publication is deliberately separate: the local fixture sets the result
before returning, while the upstream test publishes after async processing starts. Timeout fallback
and completion callbacks have component/mock reference evidence; local coverage remains unassessed.
A similar execution is a retrieval candidate, not proof of these behaviors.

Without a selected test, the method grid stays neutral. It never turns an empty association into
“not observed.” Once a test is selected, counts come from that exact target/test's saved observations.
Methods are compared to one selected reference example, not the union of every behavioral variant.

All downloads are `INVESTIGATE_TEST_EVIDENCE` tasks. Expected conclusions: sufficient existing test,
stronger assertions, justified new scenario, collection/mapping correction, or inconclusive. They
include sources, scope, selected observations and constraints against production instrumentation edits.

## Rebuild and check

From the worktree root:

```sh
python3 tools/instrumentation-coverage/cartography/pilot/build.py
node tools/instrumentation-coverage/cartography/pilot/capture.mjs
python3 tools/instrumentation-coverage/cartography/pilot/workflow.py build \
  --assessment tools/instrumentation-coverage/cartography/pilot/request-body-assessment.json \
  --output tools/instrumentation-coverage/cartography/build/request-body-pilot
node tools/instrumentation-coverage/cartography/pilot/capture.mjs \
  tools/instrumentation-coverage/cartography/build/request-body-pilot
python3 -B -m unittest discover -s tools/instrumentation-coverage/cartography/pilot -p 'test_*.py'
```

Outputs are separate under `cartography/build/behavior-pilot/`. The assessment can be revised and the
view regenerated without another test run. A new library version requires new evidence and assessment.

Iteration questions: Are these behavioral distinctions useful? Which local assertions should be
inspected next? Do reference component tests support only a stage, or the claimed complete outcome?
No single instrumentation-quality percentage is produced.

## Combined coverage view

Open `../build/coverage-overview/index.html` for the async and request-body behaviors together.
The overview percentage measures distinct methods observed in recorded test windows divided by the
collection inventory. Static methods across dependency artifacts are a separate scope indicator,
not the denominator of an implied whole-framework behavioral score.

Behavior cards aggregate reference-method observations across assessed local tests only. Without
assessed tests their bar is neutral. Similarity candidates remain available separately. Select a
suite/test execution to see one method coverage grid, then download a behavior-level investigation
task. The download automatically includes the selected test and reference, observed and missing
method evidence, sources, and limitations. No manual landmark selection is required. Source and
assessment details remain available in the collapsed Evidence disclosure. Without a selected test,
the task investigates the behavior and its existing tests; it does not label neutral evidence missing.

```sh
python3 tools/instrumentation-coverage/cartography/pilot/build.py \
  --additional-assessment tools/instrumentation-coverage/cartography/pilot/request-body-assessment.json \
  --output tools/instrumentation-coverage/cartography/build/coverage-overview
node tools/instrumentation-coverage/cartography/pilot/capture.mjs \
  tools/instrumentation-coverage/cartography/build/coverage-overview
```

## Compact Pharos portal preview

Open `../build/portal-preview/index.html`. The previous view remains available through its comparison link.
This preview reuses the report JSON unchanged. It combines a compact behavior list and one detail panel,
automatically selecting an assessed test first (then method overlap) and a reference by hit fraction.
Those choices are navigation heuristics, not probabilities or new behavioral assessments. Alternative
references, citations, limitations, and the selection explanation are in Evidence and sources. Downloads retain
exact selected evidence; a behavior with no candidate remains unknown.

```sh
python3 tools/instrumentation-coverage/cartography/pilot/preview.py \
  --report tools/instrumentation-coverage/cartography/build/coverage-overview/report.json \
  --output tools/instrumentation-coverage/cartography/build/portal-preview
node tools/instrumentation-coverage/cartography/pilot/capture-preview.mjs
```

The preview now adds a rule-based evidence assessment: no supporting associations means limited
evidence, some means partial evidence, and all means test evidence across the declared catalog.
Possible matches do not count as support; this is not a numerical quality or correctness score.
Curated behavior-stage groups use only methods present in the selected reference. They do not
assert observed order or causal handoffs. Clicking a stage reveals its methods; all remaining
reference methods remain accessible through Browse all. Changing behavior, test, or reference
closes the method drilldown. Downloads retain full evidence and optionally identify the focused stage.

The canonical compact viewer is now `tools/instrumentation-coverage/portal/index.html`.
`preview.py` is a compatibility wrapper. See [PHAROS.md](../../PHAROS.md) for the validated workflow
and the completed Spring/RxJava stability check.
