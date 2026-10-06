# Catalog reconciliation before collection

A version match and valid method bindings do not establish current or complete scope. The workflow
requires a nonempty `catalog.json`, all flows classified into families, and persisted reconciliation
inputs. Missing artifacts fail validation before the test task. Unresolved scope may still collect
useful evidence. Review status does not gate the report percentage.

## Inventory independently of selected flows

After authoring or discovering a KB, inspect a version-matched upstream checkout and the task's known
prior catalog revisions, reproductions and findings. Inventory source files without filtering to
current tracer tests or selected anchors. For this Java workflow:

```sh
python3 tools/instrumentation-coverage/catalog_reconciliation.py \
  --knowledge MODULE/coverage --upstream-tests UPSTREAM/src/test/java \
  --previous-flows PREVIOUS/flows.json --findings findings.json
```

`--previous-flows` is repeatable. `--findings` accepts an array of objects with `id`, `description`,
and `evidence`; preserve relevant scenario preconditions as additional fields. These are supplied
inputs: the tool does not discover every old worktree, customer ticket or private queue automatically.
Use the task context/module history to identify them. The private improvement queue is not a CI
runtime dependency. Upstream source files include helpers; file counts are not behavioral gap counts.

The command refuses to overwrite prior inventory/review files. Preserve them when refreshing;
compare changed upstream inputs, carry forward supplied finding identities, and author new decisions.
Never discard prior inputs or merely refresh a hash to obtain a green result.

## Review dispositions

`catalog-inputs.json` contains version identity, hashed upstream file inventory, prior flow IDs,
and supplied findings. `catalog-reconciliation.json` binds decisions to that input identity and
current flow IDs. Both are snapshotted and sealed with run knowledge.

Each decision has `signalId`, `status`, and a nonempty `rationale`:

- `mapped`: `flowIds` names existing flows. Inspect the actual scenario, not just shared methods.
  Known defects require preserving their preconditions and assertion obligations in the flow recipe.
  Mapping a finding does not establish it is fixed or covered by passing tests.
- `candidate`: `familyId` names a candidate family. It remains unresolved scope.
- `excluded`: explain why the signal is outside the declared scope. Exclusions remain visible.

Omitted decisions remain unresolved and visible. Removed prior flows must have an explicit disposition;
renaming or combining flows needs an explicit mapping to replacements. New/deleted current flow IDs
invalidate the saved review. Modified inventory invalidates its identity. Duplicate decisions,
unknown signals and nonexistent replacement flows fail validation.

A mapped source file asserts that its relevant behaviors have been accounted for. Reading one method
in a large upstream test file does not justify mapping the whole file; retain it as unresolved or
candidate until reviewed. Do not expand every API/operator combination mechanically or hide whole
families under an unsupported exclusion just to finish reconciliation.

## Reporting semantics

The headline always shows verified behaviors / declared mapped behaviors as a percentage when
there are mapped behaviors. Candidate families stay outside the denominator. Missing or unresolved
catalog review does not suppress that percentage. It describes the declared scope, not total library
correctness. Legacy reports remain readable without acquiring review data from the current checkout.

Execution-method counts and the green/gray corridor remain available as drilldown evidence. They
cannot substitute for scenario coverage. Keep inventory signals and review dispositions in authoring
artifacts and structured task context; do not expose an omissions panel in the dashboard.

## RxJava regression case

The original saved 18-flow run had no catalog.json and previously passed with a warning. It now
fails new-run knowledge validation; its saved report remains readable and reports coverage of its declared mapped behaviors.
The regenerated 32-flow KB reconciles the earlier 25-flow revision, explicitly maps seven renamed
flows, and preserves IDEA-0036's root-created, independently parented multicast preconditions.
Its 953 upstream Java source files are inventoried, not declared fully reviewed; ten API families
also remain candidates. The candidate families remain outside the mapped-behavior score. Inventory tracking helps retain
known scenarios; it does not claim all library behaviors have been discovered or that the historical
instrumentation defect is fixed.

Regression tests exercise missing catalogs, lost prior flows, changed input identities, unknown
mappings, unresolved findings and explicit exclusions. Browser checks cover declared-scope scoring independent of review status,
aggregate observations and per-test isolation. This establishes bookkeeping consistency; semantic
mapping still requires inspection and source-backed judgment.
