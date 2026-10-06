# Optional upstream-runtime behavioral evidence workflow

For the module-independent assessment and iteration loop, start with [WORKFLOW.md](../../WORKFLOW.md).
The commands below prepare the richer Spring upstream-runtime experiment; they are not required
for ordinary module onboarding or assertion assessment.

This workflow separates deterministic collection and reference validation from LLM interpretation.
It consumes a version-matched execution report, upstream naming-evidence packet, recordings, and
local test sources. It does not call an external LLM API. An assistant or human authors the assessment
between `prepare` and `validate`; that is an explicit, reviewable step, not hidden generator logic.

## 1. Prepare a bounded dossier

From the worktree root, for the request-body capability:

```sh
python3 tools/instrumentation-coverage/cartography/pilot/workflow.py prepare \
  --capability-id request-body-binding \
  --capability-name 'Bind request bodies to controller arguments' \
  --upstream-filter RequestResponseBodyMethodProcessorMockTests \
  --source dd-java-agent/instrumentation-testing/src/main/groovy/datadog/trace/agent/test/base/HttpServerTest.groovy \
  --source dd-java-agent/instrumentation/spring/spring-webmvc/spring-webmvc-6.0/src/test/groovy/datadog/trace/instrumentation/springweb6/boot/TestController.groovy \
  --output tools/instrumentation-coverage/cartography/build/body-dossier
```

Outputs: `dossier.json` and `assessment.skeleton.json`. The incomplete skeleton is intentionally not
renderable. The dossier includes hashes, selected upstream examples and helper-bearing source files,
local sources, and all test identities/results. Family method sets are unions, not per-example paths.
The report, naming packet and recordings can be supplied explicitly using `--report`,
`--naming-evidence` and `--reference`; defaults point to this experiment's saved run.

## 2. LLM assessment instructions

Give the assistant this document, `FORMAT.md`, and the dossier. Ask it to:

1. Declare the capability, bounded variants and API dimensions. Do not force one variant per cluster,
   infer every API × outcome combination, or remove behavior merely because no current test matches.
2. For each variant, state trigger, conditions and expected outcome. Produce claims with exact source
   quotes/line ranges and distinguish component, mocked-lifecycle, HTTP-integration and source-only scope.
3. Inspect local test bodies, assertions, inherited fixtures and helpers. Bind exact recorded test IDs;
   explain why the cited assertions support the variant or why evidence is only partial. Do not use
   test names, passing results, or similarity scores alone as behavioral proof.
4. Keep upstream examples and scopes distinct. Include helper excerpts when assertions delegate.
   Split mixed clusters or combine fingerprints semantically when evidence supports it; preserve
   original example identities and limitations. Do not invent a runtime call order.
5. Keep unsupported dimensions `NOT_ASSESSED`. Record limitations and unresolved mapping questions.
6. Preserve the dossier input identity, source hashes and `LLM_DRAFT_NOT_HUMAN_REVIEWED`. Do not claim
   human approval. Use the complete existing assessments as structural examples, not sources of labels.

Outputs are data (`assessment.json`), not code changes to the builder. Check citations and hypotheses
before suggesting tests. All handoffs default to investigation, with sufficient existing test,
stronger assertions, justified scenario, collection/mapping correction, or inconclusive as outcomes.

## 3. Validate and render

```sh
python3 tools/instrumentation-coverage/cartography/pilot/workflow.py validate \
  --assessment tools/instrumentation-coverage/cartography/pilot/request-body-assessment.json \
  --output tools/instrumentation-coverage/cartography/build/body-validation
python3 tools/instrumentation-coverage/cartography/pilot/workflow.py build \
  --assessment tools/instrumentation-coverage/cartography/pilot/request-body-assessment.json \
  --output tools/instrumentation-coverage/cartography/build/request-body-pilot
```

Validation checks source hashes, exact citation quotes/ranges, claim references and scope, version and
input identity, test IDs, API dimensions, and local assertion evidence for assessed associations.
It rejects stale evidence and invented references. It cannot prove that a quote entails the claim,
that test input establishes the intended trigger, or that the library catalog is complete.
The builder has no async test-name patterns or Spring-specific suite allowlists.

## 4. Review and iterate

The report links claims to source excerpts and distinguishes assessed tests from similarity-only
candidates. Preserve separate review status. Fix the assessment when wrong; change tests only when
an investigation justifies it. On changed sources or evidence, prepare a new dossier and reassess.
A changed label can retain its variant ID; automated cross-version split/merge reconciliation is not
implemented. Source snapshots of the original test run and causal async edges remain future work.

Two capabilities now use the same builder and viewer: async controller results (5 variants) and
request-body binding (4 variants). This is a generalization check within Spring, not demonstrated
cross-library portability or measured semantic-classification accuracy.
