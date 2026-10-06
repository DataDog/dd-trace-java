# LLM workflow for instrumentation quality

Before reusing or collecting knowledge, follow [catalog reconciliation](CATALOG_RECONCILIATION.md).
Missing catalogs/reconciliation fail validation. Catalog review does not gate the coverage percentage; candidate families stay outside the declared-behavior denominator.

Use `.agents/skills/instrumentation-quality/SKILL.md` as the entry point. It owns the full requested
investigation; `.agents/skills/library-flow-knowledge/SKILL.md` supplies focused knowledge authoring.
The LLM reads evidence and changes tests. The commands validate and collect; they do not call an LLM.
This workflow adds no acceptance-policy engine and does not require using the report UI.

## Artifacts and ownership

| Artifact | Lifetime | Owner / purpose |
| --- | --- | --- |
| Module `coverage/` | Library version/dependency scope | Reusable KB and collector configuration, authored using the knowledge skill |
| Generated graph | Resolved artifact identity | Bytecode analyzer; method bindings, invokedynamic and unresolved edges |
| `build/instrumentation-coverage/RUN_ID/` | One test execution | Collector, test outcomes, saved KB and sealed evidence |
| Iteration `dossier.json` | One assessment preparation | Current test sources, candidate identities, observations, previous evidence and source changes |
| Iteration `assessment.json` | One report/source identity | LLM-authored local assertion bindings and rationale, never library truth |
| Iteration `session.json` | Handoff to another LLM turn | Exact paths, current phase and next command/action |
| Iteration `report/` | Validated assessment | Generic Pharos JSON and portable HTML |

Keep source-backed behaviors and library examples in the KB. Test identities, observed method counts
and assertions checked in a particular run belong to that run's assessment. A test change normally
requires collection and reassessment, not regenerating an unchanged graph or rewriting the KB.

## Discover and author

Inspect the module build, resolved versions, harness and existing `coverage/` files. Initialize a new
module using `workflow.py init`; see [README.md](README.md) for required arguments. Infer them from
repository evidence, including forked test tasks and the selected JDK.

```sh
python3 tools/instrumentation-coverage/workflow.py graph --module MODULE
# LLM: inspect source, upstream tests, docs and bounded graph queries; author/update coverage/.
python3 tools/instrumentation-coverage/workflow.py validate-knowledge --module MODULE
```

On dependency changes, revisit affected methods and semantics. Current tracer tests help assess
coverage; they do not define the complete functionality inventory. Recording upstream executions
is optional enrichment, not a dependency of the generic workflow.

## Collect

```sh
python3 tools/instrumentation-coverage/workflow.py run --module MODULE
```

Use `--test-jvm` when required by the module. The final output prints the fresh run and report paths.
Inspect failures and collector health before interpreting observations. Read
[ARCHITECTURE.md](ARCHITECTURE.md) for lifecycle or attribution questions. For saved evidence,
`pharos.py report --run-directory RUN` verifies the seal and regenerates execution reports.

## Prepare the assertion assessment

The same command accepts a joined module report or a generated Pharos report:

```sh
python3 tools/instrumentation-coverage/pharos.py prepare \
  --report /path/to/RUN/pharos/report.json \
  --source path/from/repository/root/to/Test.java \
  --source path/from/repository/root/to/AssertionHelper.java \
  --output /path/to/iteration-01
```

Include setup and inherited helpers needed to interpret the assertions. `--source` is repeatable;
files must be within the repository. Paths may be absolute if still within the repository. The
command creates a dossier, an incomplete assessment, and a session with the exact next command.
It refuses a nonempty output directory, preserving previous iterations.

Read the dossier and source evidence. It retains the full declared behavior inventory and the
selected run's exact candidate IDs. Do not equate a candidate with an established behavior.
The generic assessment overlays local assertions onto the existing KB/report; it cannot invent
behaviors, method observations or unrecorded test identities. Fix the KB or collection and rerun if
an actual scenario is absent from that input.

## Author, validate and render

Edit `assessment.json` using this contract:

- Keep `format`, `schemaVersion`, library/version, `reportIdentity`, and source metadata as emitted.
- Keep `reviewStatus: LLM_DRAFT_NOT_HUMAN_REVIEWED`. This is not a human approval workflow.
- Retain every `behaviors[].id`. Write `assessmentSummary` describing support or the unresolved
  question. An empty `bindings` list keeps that behavior unassessed.
- Each binding has `testId`, `evidenceStatus` (`BEHAVIOR_SUPPORTED` or `PARTIALLY_SUPPORTED`), `reason`,
  and nonempty `citations`. Inspect the trigger, outcome and relevant helper assertions first.
- Each citation has `sourceId` (the emitted repository-relative source path), one-based inclusive
  `startLine`/`endLine`, and an exact `quote` from those lines. Cite assertions, not just a test name.
- Set `ready: true` once the authored assessment is ready for structural/source validation.

For example, a binding might contain:

```json
{
  "testId": "the exact recorded ID from the dossier",
  "evidenceStatus": "BEHAVIOR_SUPPORTED",
  "reason": "The test supplies the declared input and asserts the result in the cited lines.",
  "citations": [{
    "sourceId": "module/src/test/java/ExampleTest.java",
    "startLine": 42,
    "endLine": 44,
    "quote": "assertEquals(expected, actual);"
  }]
}
```

```sh
python3 tools/instrumentation-coverage/pharos.py assess --session /path/to/iteration-01/session.json
```

Validation rejects changed report/source identities, incomplete drafts, unknown tests, altered
behavior inventories and invalid citations. It cannot prove that a quote entails a claim. A ready
LLM draft may be used to continue an authorized investigation without claiming human review.
The output preserves library/reference evidence separately from new assertion claims. Context
presence, temporal attribution and behavioral support remain distinct.

## Investigate and iterate

Use report JSON directly or download a task. Inspect the existing test/fixture before adding one;
a missing reference method is not itself a test requirement. See [RECOVERY.md](RECOVERY.md).
Implement a narrow test improvement, run collection again, and prepare a fresh iteration:

```sh
python3 tools/instrumentation-coverage/pharos.py prepare \
  --report /path/to/NEW_RUN/pharos/report.json \
  --previous /path/to/iteration-01/assessment.json \
  --source path/to/any/new/Test.java \
  --output /path/to/iteration-02
```

Previous source paths are reused, current source hashes/text are gathered, and changed/new sources
are identified. Old test bindings are included only as suggestions, with exact-ID presence marked;
**all new bindings start empty**, even if the old ID and source still exist. Inspect current inputs,
assertions and helpers, rebind the fresh evidence, then assess again. Do not edit the old report hash
or source hashes to make old support appear current. Missing/renamed source files require selecting
the current sources in a new preparation rather than silently skipping them.

```sh
python3 tools/instrumentation-coverage/pharos.py resume --session /path/to/iteration-02/session.json
```

`resume` prints the saved paths and current handoff; it does not start an uncontrolled background
agent. An LLM uses those inputs to continue the requested work. Produce a reproducer for a genuine
instrumentation failure; change production code only within the user's requested scope.

## Optional upstream runtime enrichment

The Spring experiment under `cartography/` records upstream tests, classifies local execution and
adds source-pinned behavioral interpretations. Its existing `pharos.py build` path remains available
for those artifacts; see [the pilot workflow](cartography/pilot/WORKFLOW.md). That setup is still
Spring-specific. Its resulting Pharos report can be passed to the **same generic prepare/assess loop**.
Do not require or pretend to have upstream runtime data when onboarding another module.

Current local source snapshots in a dossier are evidence of the working tree at preparation time,
not of the original test run. Use a matching checkout; retain that provenance limitation for old
runs. Draft assessment and source consistency checks do not establish complete instrumentation
correctness or a universal quality score.

## Exercised handoffs

The generic CLI was checked against saved Spring baseline/candidate runs under
`build/workflow-check/spring-01` and `spring-02`. The authoring pass re-expressed the already-inspected
local assertions using the emitted source IDs; source and citation validation passed. The fresh
preparation had zero bindings until authored, and the resulting reports retained support for 3/9
and 4/9 behaviors respectively. `spring-02/session.json` resumes at investigation.

`build/workflow-check/rxjava-01` exercises the same commands on a normal RxJava module report, without
Spring data or upstream recordings. One inspected `Maybe` empty-completion test supplies the input,
completion, parent identity, cleanup and restoration assertions; other behaviors remain unassessed.
These are handoff validations using saved executions, not newly run integration suites or automatic
proof of semantic correctness. Browser checks cover generated reports and exact downloaded tasks.
