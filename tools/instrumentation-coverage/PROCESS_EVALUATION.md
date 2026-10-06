# Instrumentation-quality process evaluation

Run from the repository root:

```shell
python3 -m unittest discover -s tools/instrumentation-coverage/tests
uv run --with pyyaml python ~/.codex/skills/.system/skill-creator/scripts/quick_validate.py .agents/skills/instrumentation-quality
uv run --with pyyaml python ~/.codex/skills/.system/skill-creator/scripts/quick_validate.py .agents/skills/library-flow-knowledge
```

The skill validator checks packaging, not judgment. Synthetic process tests use
temporary files; they do not create a library catalog in the repository.

An independent forward evaluation exercised a complete lifecycle/scheduling/multicast
catalog request and a filtered branch quality-report request. It correctly required
source-backed candidate resolution and fresh assertion assessment, rather than
delivering the supplied two-flow draft or unassessed passing execution report.
Its remaining operational findings led to explicit exclusion-command routing,
portable failed-assertion findings, and durable final-report scope fields in WORKFLOW.
A second forward check applied the updated instructions to a complete async scope
with unresolved cancellation/multicast and a filtered known parent mismatch. It
continued catalog authoring and assertion assessment, retained the defect, and
distinguished filtered passing results from full-suite correctness. No remaining
task-blocking contradiction was identified in that check.

Validation on this branch: 73 generic process tests and 15 cartography tests passed;
both skill packages passed packaging validation. Real Gradle configuration checks
confirmed the diagnostic filter was present for scoped collection and absent for
an ordinary invocation. These checks did not execute the RxJava tests. The separate
Spring pilot suite could not initialize because its generated
`cartography/build/report/report.json` fixture is absent; it was not regenerated.

The semantic-stage correction adds rejection tests for missing/placeholder labels,
missing rationale and unknown sources, plus end-to-end binding/assessment/render/replay
tests for authored labels and explicitly ungrouped methods. The generic suite now
passes 82 tests; the 15 cartography tests and both skill-package validators also pass.
A fresh RxJava 3.0.0/JDK 21 collection passed all 62 instrumentation tests without
exclusions. The assessed HTML browser check visits all 144 declared behaviors,
checks exact authored stage names and downloaded evidence, rejects a placeholder
report, and exercises ungrouped method browsing. These are consistency/presentation
checks, not automated proof that a stage's semantic explanation is true.

The catalog-navigation correction requires authored grouping for all non-excluded families.
New tests reject absent metadata, orphan/ambiguous membership, unknown values, duplicate
dimensions, indistinguishable rows and placeholder names, including the shared JavaScript
validator with opaque non-RxJava IDs. End-to-end tests preserve navigation through binding,
assessment and replay while leaving method evidence unchanged. The browser checker follows
authored family/dimension controls to every scenario, checks downloaded per-test evidence,
rejects orphan navigation, and checks mobile layout. No UI-generated family names are accepted.
Validation after this correction: 91 generic tests, 15 cartography tests and both skill-package
validators passed. Fresh RxJava run `2ae261b449e34d1e8b6364988005e468` passed 62 tests with zero
failures/skips/exclusions; its assessed grouped report passed the browser check for all 144
scenarios in 21 families. The score remains 10 fully supported and 24 partially supported.

## Evidence invariants

| Adversarial input | Required observable outcome |
| --- | --- |
| Many passing tests, no assessed assertions | Stage is collected/not assessed, no inferred support or task completion |
| Supported subset and candidate family | Declared score stays bounded; candidate survives assessment/rendering |
| Assessment completed with no supporting binding | Distinct from a collection that was never assessed |
| Authorized regression exclusion | Targets/reason/authorization survive collect/assess/replay; declared denominator unchanged |
| Missing exclusion provenance | Rejected before collection; not a manual HTML-only disclaimer |
| Tampered excluded scope | Evidence seal rejects it before joining a report |
| Changed source, report or collection scope | Prepared assessment rejected; fresh preparation required |
| Failed tests or collector health errors | No successful report produced |
| Shared anchors or old supported binding | No automatic current assertion support |
| Removed prior flow or missing catalog | Reconciliation/knowledge validation fails or preserves unresolved scope |
| Missing/placeholder stage label or lifecycle rationale/source | New knowledge rejected before collection |
| Authored stage with internal numeric ID | Binding, assessment, HTML and replay preserve the authored display label |
| Explicit ungrouped mapping with reason | Methods retained without invented stage cards |
| Legacy unlabeled sealed run | Methods render ungrouped, without changing the saved evidence |
| Missing authored family navigation or invalid dimensions | Catalog validation rejects new knowledge |
| Orphan, ambiguous or indistinguishable scenario membership | Catalog and report/viewer validation rejects it |
| Authored families with opaque scenario IDs | All scenarios reachable without library/name parsing; evidence unchanged |
| Legacy report without navigation | Flat scenario browsing retained, without invented metadata |

`test_delivery_process.py` exercises the first seven cases end to end, including
portable HTML payload consistency and safe rendering. Existing assessment,
workflow, knowledge and reconciliation tests cover the remaining boundaries.

## Semantic skill checks

For the upstream-reference workflow, apply REFERENCE_CARTOGRAPHY: static draft authoring is not a
completed runtime reference. A normal reference-comparison request must collect upstream examples
before finalizing stages; a source-only request may stop at a labeled draft. If given only the current
Spring temporal recorder or the static RxJava report, do not assert causal async recording or the
new execution metric exists. Identify the missing adapter/collector/comparison implementation.
Keep Not covered execution separate from assertion gaps. Recording both suites does not prove
exact parentage or turn weak assertions into supported bindings. The recorder acceptance cases
in that protocol are implementation requirements, not tests already passed by this branch.

For a complete-catalog request, establish the requested behavior boundary first.
Investigate meaningful success/error/empty/cancellation/demand/resource/async
variants from version-matched contracts. Do not map a whole family or source file
from one example, empty candidates mechanically, or silently narrow the boundary.
For a bounded draft request, preserve unresolved candidates without claiming full
coverage. For an end-to-end quality request, do not stop after execution HTML;
complete assertion assessment and hand off its validated path. An assessed defect
is a finished assessment conclusion, not a fixed integration.

Review an assertion's entailment separately from valid quote/source hashes.
Physical cross-thread causality, exact parent identity, lifetime and restoration
need controlled tests; method-entry Context cannot prove them. The tests protect
artifact consistency, not universal semantic completeness. A new library should
still receive independent source-grounded review; a passing synthetic suite is
not a promise that an agent can no longer make a reasoning mistake.

## Reproducible filtered diagnostic collection

Only after explicit user approval:

```shell
python3 tools/instrumentation-coverage/workflow.py run --module MODULE \
  --exclude-test 'ExampleTest.knownRegression' \
  --exclusion-reason 'Preserve a known failing regression while collecting other tests' \
  --exclusion-authorization 'User explicitly requested this diagnostic exclusion'
```

The selection is recorded in `collection-scope.json` inside the fresh run, sealed
with its evidence, applied only by the collection Gradle init script, and carried
into report data and regenerated HTML. Ordinary test invocations are unaffected.
This records authorization; the CLI cannot verify the truth of that statement.
Do not use it without actual user approval or claim excluded behaviors verified.
Carry a known failure through reconciliation's `--findings` inputs, including exact
expected/actual assertion text and scenario preconditions plus retained artifact paths.
It survives as `catalogScope.knownFindings` in report data; no banner is injected.
