# Functional knowledge authoring contract

Before reusing or collecting knowledge, follow [catalog reconciliation](CATALOG_RECONCILIATION.md).
Missing catalogs/reconciliation fail validation. Catalog review does not gate the coverage percentage; candidate families stay outside the declared-behavior denominator.

Use the existing module `library.json` and `observation.json` formats from README. The authoring
workflow adds the following files. A valid draft is not necessarily a correct semantic mapping.

## catalog.json (schemaVersion 1)

`catalog.json` records the independent review of functionality families before test evidence is
interpreted. It carries the same `library` and `version` as `flows.json`, a review `status`, an
explicit `scope`, and `families`. Each family has a stable `id`, human-facing `name`,
`classification`, representative exact `entryMethods`, optional `flowIds`, `sourceIds`, and a
`rationale`.

- `mapped` means the family is represented by one or more declared flows; `flowIds` is required.
- `candidate` means versioned evidence shows functionality in scope that may deserve flows;
  representative graph methods are required, while `flowIds` must remain empty.
- `excluded` records a conscious scope boundary and its reason; it does not become a coverage gap.

Validation binds entry methods and flow/source references. It cannot decide whether a candidate is
important or whether the inventory is complete. Promote a candidate only after semantic review;
do not turn all public methods into test obligations.

## flows.json (schemaVersion 2)

Retain the existing report-compatible fields: `library`, `version`, `flowCatalog` (scope, exclusions,
status), `sources`, `taskContext` and `flows`. Each source has an `id`, `kind`, a `url` or `path`,
version/revision when available, and the claim it supports. Link to the exact relevant section or
source method rather than a documentation home page. Record missing source evidence explicitly.

Each flow has stable `id`, `feature`, `variant`, `outcome`, `status`, `sourceIds`, and `steps`.
Each step has an `id`, `kind: library-method`, and `anchor`: preferably an exact JVM method ID from
the graph query. Existing prefix anchors are supported; a step is satisfied by any matching method.
Multiple bindings are surfaced for review rather than silently treated as equivalent behavior.

The following fragment illustrates checkpoint roles. Its step IDs must refer to the flow's actual
method steps; it is not a verified RxJava mapping:

```json
{
  "identification": {"allOf": ["subscribe", "scheduled-subscription"], "anyOf": [], "noneOf": []},
  "prerequisiteSteps": ["assemble"],
  "completion": {"allOf": ["deliver"], "anyOf": ["success", "error"], "optional": ["cleanup"]}
}
```

- `identification`: associates a test window with the scenario. All `allOf` steps and at least one
  `anyOf` step (when declared) must appear; `noneOf` steps must not appear.
- `prerequisiteSteps`: shared setup shown separately from identifying anchors.
- `completion.allOf`: required completion steps for the proposed experiment.
- `completion.anyOf`: at least one of these completion steps is required.
- `completion.optional`: useful observation targets that do not become acceptance requirements.

Completion roles do not change scenario identification. The report continues to show observations,
including missing optional methods, without treating every unobserved entry as a test failure.
Required/alternative/optional completion roles must be disjoint. Use separate flow variants when
one alternative materially changes the meaning of the scenario. If source proves the lack of a
method checkpoint, do not fabricate one; document the gap.

Legacy catalogs without `completion` retain the positional fallback for compatibility. New drafts
must declare it explicitly, even when all three lists are empty. The current Spring catalog has
been migrated to explicit fields preserving its existing interpretation; this is not new semantic
review. `expectedMethodSteps` can limit the corridor independently of the predicate.

An optional `exercise` holds `status: curated-draft`, a behavior summary, and actionable test steps.
Sources, predicates, checkpoint roles, and recipes remain proposed until reviewed. Use `draft`,
`reviewed` or `needs-review` for new flow statuses. Do not assign reviewer names or approval dates
without actual approval.

An optional `contextContract` records reviewed or proposed active-Context behavior separately from
the functional corridor:

```json
{
  "status": "needs-review",
  "precondition": "The call is created under a non-root parent and cancellation happens after that parent scope closes.",
  "expectations": [
    {
      "id": "cancel-entry",
      "stepIds": ["cancel"],
      "expected": "EITHER",
      "sourceIds": ["local-analogue"],
      "rationale": "The closest repository analogue finishes the stored call span without activating it."
    }
  ]
}
```

`status` is `draft`, `needs-review`, or `reviewed`. The precondition is required because Context
expectations depend on where the scenario begins and which scope remains active. Expectation IDs
must be unique within the flow; `stepIds` refer to declared flow steps; `sourceIds` refer to catalog
sources. `expected` is one of:

- `PRESENT`: method entry must observe non-root Context under the precondition;
- `ABSENT`: method entry must observe root Context under the precondition;
- `EITHER`: either state is valid, so presence is not a quality requirement;
- `UNRESOLVED`: evidence is insufficient and a focused experiment or review is still needed.

Derive these values from target-module contracts and analogous repository behavior first, then
library semantics, and finally official OpenTelemetry conventions when local precedent is absent or
ambiguous. A runtime observation can corroborate a contract but cannot create one. OTel span
lifetime or parentage rules do not automatically imply active Context inside every internal method.

## evidence.json (schemaVersion 1)

```json
{
  "schemaVersion": 1,
  "graphSha256": "SHA-256 of raw-graph.json",
  "claims": [
    {
      "id": "subscription.anchor-rationale",
      "flowId": "library.subscription",
      "basis": "curated",
      "claim": "Explain what the selected anchors establish and what they cannot establish.",
      "sourceIds": ["versioned-source-reference"],
      "methodIds": []
    }
  ]
}
```

Each flow needs at least one claim. `sourceIds` refer to `flows.json.sources`; `methodIds`, when
provided, must exist in the graph. Bases are `static`, `curated`, `hypothesis` or `observed`. Observed
claims also require a `runId`; the validator checks that it is present, not the truth of its claim.
Never label a static path observed. The graph fingerprint ties the rationale to the inspected
artifacts. Reconsider claims when upgrading, even when names and descriptors still bind.

## knowledge-review.md

Include the bounded feature inventory, omissions and reasons, identifying-anchor rationale,
completion roles, counterexamples from the challenge pass, unresolved dynamic edges, source gaps,
and structural validation results. Highlight decisions requiring review. Existing tests may support
examples but do not establish inventory completeness.

## Query and validation commands

```sh
python3 tools/instrumentation-coverage/graph-query.py GRAPH summary
python3 tools/instrumentation-coverage/graph-query.py GRAPH search-methods subscribeOn --limit 15
python3 tools/instrumentation-coverage/graph-query.py GRAPH describe-method 'EXACT_METHOD_ID'
python3 tools/instrumentation-coverage/graph-query.py GRAPH callees 'EXACT_METHOD_ID' --depth 2
python3 tools/instrumentation-coverage/graph-query.py GRAPH find-path 'SOURCE_ID' 'TARGET_ID'
python3 tools/instrumentation-coverage/validate-knowledge.py \
  --graph GRAPH --knowledge MODULE/coverage --output /tmp/knowledge-validation.json
```

Replace GRAPH and method IDs with actual paths/identifiers; quote JVM descriptors in the shell.
Query responses carry graph/artifact identity and truncation flags. `public-apis` means public methods
on public classes, not a guarantee that every result is a supported user API. `find-path` can report
`SEARCH_BUDGET_EXHAUSTED` separately from `NO_DECLARED_PATH`. Static graphs can have cycles; the
queries handle them without calling the full graph a DAG.

Validation checks version identity, duplicate IDs, predicates, checkpoint roles, source references,
bindings, and optional evidence provenance. Ambiguous anchors and missing evidence files generate
warnings. Before submitting a new draft, provide the evidence file and explain every warning in the
review document. Semantic correctness is always reported as `NOT_PERFORMED` by the validator.
