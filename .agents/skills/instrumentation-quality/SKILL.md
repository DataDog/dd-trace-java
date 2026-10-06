---
name: instrumentation-quality
description: Assess instrumentation scenario coverage by comparing our tests' execution fingerprints with recorded upstream scenarios. Use for the complete catalog, collection, classification and report workflow.
---

# Instrumentation quality — Pharos

**Map the library → record upstream tests → collect our tests → classify execution → report.**

Use [library-flow-knowledge](../library-flow-knowledge/SKILL.md) to build the versioned behavior
catalog and its functionality families. Documentation and source interpretation belong to that
catalog-building phase, not to a second manual review of every local test.

Reuse matching graphs and completed recordings. Run our instrumentation tests with the existing
collection bridge, retaining their outcomes and root/non-root context observations.

Compare each local test's method-entry fingerprint with each upstream scenario. Use distinctive
methods, counts and semantic stages; use observed edges or async handoffs when both collectors
provide comparable features. Keep upstream examples/repetitions as alternatives, not a synthetic
union path. A composed local test may match several scenarios.

Classify automatically using reproducible similarity scores. Show likely matches, partial matches
and no matches, with matched/missing stage methods and the local test names. Show close alternatives
when fingerprints cannot distinguish scenarios. Similarity is not a calibrated probability.

Read [WORKFLOW.md](../../../tools/instrumentation-coverage/WORKFLOW.md) for commands and scoring.
Do not require prepare/assess, source-cited assertion bindings, human approval, or an
"unverified" state to generate the report. Assertion review is optional and only done when asked.

The report contains family colors, scenario matches, stage coverage, context observations and
test pass/fail counts. Keep provenance and scoring details in expandable sections, not banners.
Reuse saved collections for report changes; rerun tests only when their inputs or collector change.

Check artifact identity and collection health before comparison. A collector error is a failed
collection, not absent test coverage. Do not silently omit catalog scenarios. Test changes and
production fixes remain subject to the user's requested scope.

Deliver the HTML path and test outcomes. Describe the result as execution-based scenario coverage,
not proof of assertions or whole-library correctness; do not turn that distinction into a workflow gate.
