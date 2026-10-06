# Upstream reference cartography

Build the behavior catalog from versioned documentation, implementation and upstream tests.
Group scenarios into functionality families, then record selected upstream tests to establish
their execution fingerprints. Documentation explains the catalog; it is not an extra gate on
classifying our tests.

## Record once, reuse

Pin the library artifacts, upstream sources and invocation IDs. Keep recorder-off outcomes and
repeat recordings. Record method entries/counts and available synchronous edges and async ownership
links independently of tracer Context. Check collection health and matching artifacts automatically.
Preserve actual repetitions as alternatives rather than forcing identical counts or merging paths.

Use recorded execution plus the static graph to assign semantic stages such as subscription,
delivery, draining and cleanup. Never show numbered placeholder stages. Catalog metadata owns
families and stage names; the renderer preserves them.

## Classify our tests

Compare one local test at a time with each reference alternative. Weight distinctive method entries
more heavily than ubiquitous methods. Use counts and stage membership, and compare edges/handoffs
only when both sides recorded comparable data. Missing edge data is not an invented graph edge.
Allow a composed local test to match several scenarios. Retain close alternatives when execution
fingerprints are indistinguishable.

The report shows similarity scores, likely/partial/no matches, matched/missing stage methods,
root/non-root context observations and test outcomes. Scores are similarity, not calibrated
probabilities. No source-cited local assertion review or verification status is required.

Coverage colors summarize classified scenarios, not assertions. Collection errors fail the
comparison instead of becoming a coverage gap. Keep technical provenance in expandable details;
no banners. Historical failures remain stored but do not replace the current test outcomes.

The implemented commands and scoring are in [WORKFLOW.md](WORKFLOW.md).
