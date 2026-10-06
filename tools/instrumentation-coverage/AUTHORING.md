# Authoring library knowledge

This file describes the legacy static KB format. The default execution-fingerprint workflow is
in [WORKFLOW.md](WORKFLOW.md); it does not require local assertion review or catalog reconciliation
as a report-generation gate. Use these details only when maintaining a legacy module KB.

Before reusing or collecting knowledge, follow [catalog reconciliation](CATALOG_RECONCILIATION.md).
Missing catalogs/reconciliation fail validation. Catalog review does not gate the coverage percentage; candidate families stay outside the declared-behavior denominator.

The semantic mapping is reviewed knowledge. A human or LLM can draft it; deterministic tools bind
and validate it. A graph alone cannot identify all important features, and existing tests alone
cannot reveal all missing scenarios.
For upstream-reference quality assessment, the sequence is static graph, upstream test/contract
review, flows/families, upstream recording, then finalized stages/reference coverage. Follow
[REFERENCE_CARTOGRAPHY.md](REFERENCE_CARTOGRAPHY.md); source-only authoring is an explicitly static
draft. The generic async recorder is a required capability still to implement, not an existing CLI.

## Add an instrumentation module

1. Create its `coverage/` directory. Use Spring WebMVC 6 as the first example.
2. Set `library.json` to the actual library coordinate, reviewed version, selected artifact groups,
   test task and corresponding resolved runtime configuration. The bridge takes the intersection
   of resolved artifacts and that test task's actual classpath. Avoid including test-only libraries
   unless they are relevant to the analysis boundary.
   For core JDK modules, use `graphSource: {"kind": "jdk-module", "module": "java.base"}` and
   record the selected test launcher's full runtime version. The workflow exports the matching jmod;
   do not substitute source or bytecode from another JDK build.
3. Choose observation classes and required production-transformed classes in `observation.json`.
   Observation classes must be loadable by the test application loader or bootstrap loader. Review
   optional dependencies. `maxDistinctObservations` may raise the default 10,000-row budget for a
   large parameterized suite. A row is a distinct method, scenario, Context state, attribution and
   handoff combination. Exceeding the budget fails the run, so use a reviewed module-specific value
   instead of accepting partial evidence.
4. Run `graph` against those artifacts. Draft `catalog.json` from documentation, public API families, version-matched source, and explicit
   scope decisions. Classify families as mapped, candidate, or excluded. Then draft `flows.json`
   using documentation, source, and tests.
5. Record upstream examples using the reference protocol, combine observations with the static
   graph/source review, and finalize semantic stages. Run `validate-knowledge`; inspect
   `catalogAssessment`, graph/binding artifacts and reference acceptance separately.
6. Run our tests with `run`, check collection health and test attribution, and compare with the
   pinned upstream reference. Without an accepted reference this remains a local diagnostic.

Set `adapter` to `spock` for `InstrumentationSpecification`, or `junit` for
`AbstractInstrumentationTest`. Both use the same collector and report format. See
[JUNIT.md](JUNIT.md) for the Jupiter lifecycle and validation command.

## Draft a flow

First define its owning functionality family and useful navigation dimensions in catalog.json.
Author family description, dimension labels/values and scenario labels/values following
KNOWLEDGE_FORMAT. Do not reconstruct grouping later from flow names. Use no dimensions when the
family's scenarios can be browsed directly. Split scenarios for materially different preconditions,
outcomes or assertion obligations; equivalent overloads remain reference examples. Check that
labels distinguish scenarios after filtering and cancellation timing is explicit where relevant.
Review semantics separately from validation of exact, unambiguous membership. The same metadata
must drive visualization and survive binding, assertion assessment and replay without changing
the score or hiding unsupported outcomes.

Use stable flow IDs and identify the user-visible behavior first. For example, a Callable-returning
controller starts asynchronous processing, completes it, and redispatches the servlet request.

Choose `feature`, `variant`, and `outcome` as the report's human-facing summary. Adjacent flows must
be distinguishable from these three fields alone. For example, unary gRPC variants should say
`Blocking client call`, `Async response observer`, or `ListenableFuture client call`, rather than
repeating `Client stub`. A reader should not need JVM method IDs to understand the difference.

Record sources and distinguish documented behavior from a test fixture or investigation hypothesis.
Choose a corridor containing recognizable checkpoints. Method anchors use JVM owners, names and
descriptors; prefix anchors can bind multiple overloads. Inspect those bindings before accepting them.

Define each stage's lifecycle role before choosing its methods. Author a meaningful `label`, a
source-grounded `rationale`, and `sourceIds` following KNOWLEDGE_FORMAT. Do not display internal IDs
or number methods as stages. Review the label against every bound method: entering an emitter or
scheduled task does not by itself prove accepted delivery, task completion or causal ordering.
If no useful grouping is supported, explicitly justify an ungrouped method view. Verify stage
labels survive binding, assessment and report replay; browser acceptance must check their content,
not just that the cards are clickable.

Use `identification.allOf` for required steps, `anyOf` for alternatives and `noneOf` for exclusions.
A step with multiple bound methods is satisfied when any one of its methods is observed. Shared
methods such as `DispatcherServlet.doDispatch` cannot identify a Callable flow by themselves.

Declare shared prerequisites and completion roles explicitly using `prerequisiteSteps` and
`completion.allOf`, `completion.anyOf`, and `completion.optional`. Position in the corridor does
not make an optional branch required. Legacy catalogs retain positional inference only for
compatibility. See KNOWLEDGE_FORMAT.md for the complete authoring contract.

Add an `exercise` recipe when you can explain how to reach the behavior. Mark it `curated-draft`
until reviewed. Recipes are proposals; root observations do not justify an instrumentation fix.

A useful LLM authoring instruction is:

> Inspect the resolved library source, documentation and raw graph. Propose a bounded feature
> catalog independent of current test coverage. For each scenario, provide sources, distinctive
> anchor predicates, expected checkpoints, alternatives, exclusions and an exercise recipe.
> Explain ambiguous dynamic dispatch and unresolved edges. Keep claims of observed behavior separate
> from static reachability. Validate the bindings and present semantic choices for human review.

## Review before adoption

Check that the independent functionality inventory covers the intended boundary before interpreting
flow coverage. Candidate families identify potential catalog omissions; they are not automatically
test obligations. Check that mapped flows explain real library behavior, that overloads are
appropriate, and that identifying methods distinguish adjacent variants. Check missing test
scenarios against documentation rather than declaring the existing suite complete. Current binding
validation detects duplicate IDs, invalid references, identity mismatches and unresolved method
anchors; it cannot validate semantic truth.

Customer examples can later contribute source references and additional variants. Preserve their
provenance and avoid turning one observed customer path into a universal requirement.

## Author Context expectations

Context expectations are a separate layer over the functional map. First inspect explicit tests,
instrumentation advice, and lifecycle documentation in the target module. Then search for the
closest analogous integration in this repository. Use versioned library semantics next. Consult
official OpenTelemetry specifications and semantic conventions only when local evidence does not
settle the question.

Repository precedent must be described accurately: an analogous advice implementation is evidence
of established behavior, while an explicit assertion or maintained design document is stronger
evidence of intent. Runtime observations establish what happened in one scenario and cannot define
the expected contract by themselves.

Record a `contextContract` only when it helps interpret the flow. State the scenario precondition,
map expectations to declared step IDs, cite their sources, and distinguish `PRESENT`, `ABSENT`,
`EITHER`, and `UNRESOLVED`. `PRESENT` means a non-root Context is required at method entry under the
declared precondition. `EITHER` means root and non-root entries are both acceptable; it does not mean
the observed state must vary. Keep span parentage, span lifetime, and propagation assertions in the
test or supporting claim rather than pretending method-entry Context proves them.

## Upgrade a dependency

`validate-knowledge` and `run` reject a primary library version different from `reviewedVersion`. Examine
`resolved.json`, compare source/documentation changes, update the library and flow version together,
then regenerate bindings. Review added, removed and changed anchors before accepting the mapping.
Do not merely edit the version to silence the check.

Changes to selected transitive dependencies invalidate the graph cache, even when the primary
library version remains the same. Current validation rechecks method bindings, but semantic changes
in dependencies still need review. The resolved artifact list and hashes remain in each run.

## Close the loop

Download an uncovered flow's task, add the scenario using the existing instrumentation-test
conventions, and run fresh collection. Compare the identifying test and each declared checkpoint.
A new observation establishes execution; its Context state can be root, non-root, or mixed.
Investigate unexpected Context separately before proposing instrumentation changes.

## Use the authoring skill

The repository skill is `.agents/skills/library-flow-knowledge/SKILL.md`. Example request:

```text
Use $library-flow-knowledge to draft the RxJava 3 functional catalog.
Module: dd-java-agent/instrumentation/rxjava/rxjava-3.0
Graph: tools/instrumentation-coverage/build/rxjava3-graph/raw-graph.json
Produce flows.json, evidence.json and knowledge-review.md for review.
```

If the skill is not yet in the session's catalog, supply its SKILL.md path explicitly. The skill
uses the deterministic query/validation commands in [KNOWLEDGE_FORMAT.md](KNOWLEDGE_FORMAT.md).

To generate a graph from a saved resolved-artifact manifest without a flow catalog:

```sh
./gradlew -p tools/instrumentation-coverage analyzeResolvedLibrary \
  -PcoverageManifest=/absolute/path/to/resolved.json \
  -PcoverageGraphOutput=/absolute/path/to/graph-directory
```

This lower-level command does not need `flows.json`. The workflow's `graph` command produces
structural evidence and `validate-knowledge` binds an existing catalog; neither infers semantics.
The orchestration skill invokes the authoring skill between those stages.
