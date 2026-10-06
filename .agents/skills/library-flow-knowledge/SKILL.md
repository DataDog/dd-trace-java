---
name: library-flow-knowledge
description: Author or refresh a versioned library behavior KB for Pharos from upstream tests, documentation, source and a reachability graph. Use for knowledge authoring alone; instrumentation-quality owns collection, assertion assessment and test improvement.
---

# Library flow knowledge

Produce reusable knowledge for the user's instrumentation module. It describes library behavior,
reference evidence and possible method checkpoints; it does not record whether today's tracer tests
cover that behavior. Read [AUTHORING.md](../../../tools/instrumentation-coverage/AUTHORING.md) and
[KNOWLEDGE_FORMAT.md](../../../tools/instrumentation-coverage/KNOWLEDGE_FORMAT.md) when writing files.

Script names below live in `tools/instrumentation-coverage/`; invoke them with `python3` from the
repository root.

## Establish identity and evidence

Use the module's resolved library version and dependency closure. Prefer a supplied matching graph;
otherwise generate one with `workflow.py graph --module MODULE` after configuration exists. Core
JDK knowledge must match the actual selected test JDK, including its source build.

Inspect version-matched **upstream tests together with documentation, API families and implementation**.
Test inputs, assertions and helpers reveal concrete scenarios, while public contracts expose cases
missing from the selected tests. Distinguish component/mock examples from real transport/lifecycle
evidence. Existing tracer tests are later coverage evidence; their omissions must not remove a
library behavior from the KB. Runtime reference recording is optional, not required for authoring.

Use `graph-query.py GRAPH` with `summary`, `public-apis`, `search-methods`, `describe-method`,
`callers`, `callees` and `find-path` for bounded queries. Don't load the whole graph into model context.
Preserve invokedynamic/lambda metadata and unresolved virtual, reflective and callback edges.
Static paths are possible routes, not recorded order or cross-thread causality.

## Reconcile catalog scope before reuse

A matching dependency version and valid anchors do not make an existing catalog current. Inventory
upstream test sources independently of selected flows, then reconcile prior catalog revisions and
supplied known findings. Use [the reconciliation procedure](../../../tools/instrumentation-coverage/CATALOG_RECONCILIATION.md).
Missing inputs require inventory/review before collection; unresolved signals may remain explicit
while collection proceeds. Never silently drop a prior scenario or treat a known reproducer as
covered by a generic flow: preserve its preconditions and assertion obligations in the mapping.

Keep unreviewed families as candidates, not arbitrary exclusions. Explain exclusions and flow
replacements with source evidence. Do not update input hashes merely to silence validation. Check
available prior artifacts identified by the task or module history; a private queue is not a CI
dependency. Record supplied findings in portable inputs so another worktree retains them.

The headline shows verified behaviors / declared mapped behaviors as a percentage. Candidate
families stay outside the denominator. Catalog review status must not hide the percentage or add
an omissions panel to the report. Keep inventory bookkeeping in authoring artifacts. Context
presence, passing tests and shared methods cannot establish behavioral completeness.

## Author the reusable knowledge

Maintain these files in the module's `coverage/` directory:

- `catalog.json`: functionality families and explicit mapped/candidate/excluded scope.
- `flows.json`: stable behavior IDs, distinguishable feature/variant/outcome names, source references,
  identifying anchors, completion alternatives and optional exercise recipes.
- `evidence.json`: source claims and graph/artifact provenance.
- `knowledge-review.md`: rationale, counterexamples, omissions and unresolved questions.

`library.json` and `observation.json` configure resolution/collection; the main workflow owns them.
Do not put local test-run IDs, passing results or assertion-support scores in the reusable KB.

State triggers and outcomes before choosing methods. Distinguish assembly/registration from
subscription, callback execution and completion. Explain why anchors separate neighboring behaviors;
ask whether another scenario could satisfy them or the intended scenario could avoid them. An
upstream reference's method set is an example, not automatically the required corridor.

Resolve JVM method IDs using the graph. Mark required, alternative and optional completion explicitly.
Keep identification-only `noneOf` exclusions out of `expectedMethodSteps`. Include meaningful branch
alternatives with their conditions. Preserve readable differences such as blocking, callback and
future-based APIs in flow names. Customer cases can contribute examples with their own provenance;
one incident does not become a universal contract.

## Interpret Context only where useful

Derive expectations from this module's tests/design first, analogous repository instrumentation
second, versioned library semantics next, and official OpenTelemetry guidance only if still needed.
Record contrary evidence too. Existing advice is precedent, not proof of intent.

Use an optional `contextContract` with scenario preconditions, cited rationale and step expectations
`PRESENT`, `ABSENT`, `EITHER` or `UNRESOLVED`. Context presence can provide observability evidence;
root entries don't prove a defect. Parentage, identity, lifetime and restoration require their own
assertions. Runtime observations don't define the expected contract.

## Validate and hand back

Run `workflow.py validate-knowledge --module MODULE`, or the lower-level
`validate-knowledge.py --graph GRAPH --knowledge MODULE/coverage` for a supplied graph. Fix invalid
bindings and references. Validation establishes consistency, not semantic correctness.

New knowledge stays draft/needs-review without falsely claiming human approval. Preserve reviewed
knowledge unless evidence warrants a documented change. On upgrades, revisit affected contracts and
anchors, including relevant dependency changes; don't merely update the version to bypass a check.

Return the KB and graph paths, validation result and unresolved questions to `instrumentation-quality`.
For a KB-only request, stop there. Within an authorized end-to-end task, return control to collection
and assessment without introducing another permission checkpoint.
