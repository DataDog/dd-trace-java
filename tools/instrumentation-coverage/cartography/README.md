# Runtime flow cartography — isolated Spring experiment

Branch: `andrea.marziali/spring-flow-cartography`.
Base: prototype commit `529011ffee9b2d1d6b5f0fb4e5bb72eaa28fffe8`.
The parent worktree is untouched. No production instrumentation or test assertions changed.

Open [the experimental report](build/report/joined-report.html). Its JSON can also be loaded by
`../viewer/index.html`. `build/reference.json` contains the upstream recordings, including synchronous
nesting edges; `build/report/classification.json` contains full rankings and evaluation.

## What runs

1. The existing graph workflow resolves the module's actual Spring 6.0.2 dependencies and analyzes
   bytecode, including invokedynamic metadata. Static call neighborhoods remain visible in the report.
2. `prepare.py` copies ten original upstream test classes and required test fixtures, without editing
   them, into the standalone harness. Every copied file gets a SHA-256 in `build/source-manifest.json`.
3. `Recorder` is a small Byte Buddy premain agent. It observes concrete, non-synthetic/non-bridge
   method bodies in graph-defined Spring Web/MVC classes. It records method counts and nesting between
   selected methods. Test-body windows exclude setup/cleanup, matching our normal collection boundary.
4. Run the upstream suite once without the recorder and three times with it. Compare outcomes and
   invocation identities. Two recordings train the model; the third checks repeatability.
5. Expand our observer's class inventory from the upstream methods, then run our existing Spring
   tests with the real Datadog instrumentation and the existing entry/Context harness.
6. Cluster upstream fingerprints, classify our test windows, and project their unchanged method and
   Context observations through the candidate associations into the existing report UI.

The source checkout used here is Spring Framework tag `v6.0.2`. Dependencies include the original
Spring release jars. `runtime-manifest.json` hashes the resolved Spring jars; classification verifies
matching analyzed artifacts. Supporting test dependencies are declared in `build.gradle`; this is a
standalone selected-test harness, not the full upstream Gradle build or its complete test environment.

## Statistical model

- Features: presence of methods, independent of invocation counts and Context state. Reference
  features must occur in both training repetitions and resolve in the static graph.
- Weights: `1 + log((N + 1) / (documentFrequency + 1))`, reducing shared-method dominance.
- Families: deterministic complete-link agglomerative clustering, weighted Jaccard threshold 0.70.
  Every pair within a family must pass the threshold. Singleton families remain explicit.
- Matching: compare each reference fingerprint with our test's method set, restricted to our eligible
  inventory. Score = weighted reference recall × `(1 - exp(-sharedMethodCount / 3))`.
- Candidate thresholds: score ≥ 0.70, at least four shared methods, and at least 35% of weighted
  reference evidence observable. Candidates within 0.05 of the strongest eligible match are kept,
  up to three. Multiple candidates may represent ambiguity or several behaviors in the same test.
- These are exploratory similarity thresholds fixed before target inspection. Scores are not
  probabilities, likelihood estimates, or verified semantic labels. No quality gate is installed.

## Names and landmarks from upstream evidence

The 53 discovered execution families are now named from their pinned test bodies and assertions,
using an explicit LLM-authored interpretation. The previous 17-flow catalog is not used for names
or corridors. See [NAMING.md](NAMING.md) for the evidence-packet workflow and regeneration instructions.

Each family has a feature name, behavioral title, explanation, original source excerpts and conceptual
landmark groups. The names remain drafts, not human-reviewed contracts. Mixed success/error families
are labeled broadly rather than borrowing the meaning of a single test. Extra support methods are
expandable in the corridor; all recorded reference methods remain represented.

The statistical matching is unchanged. Candidate associations do not certify the named outcome.
Reference method unions are examples, not obligations; missing optional methods are not test failures.
Landmark groups do not establish temporal or cross-thread order. Static neighborhoods remain possible
calls, while the upstream nesting evidence is explicitly synchronous and partial.

## Measured result

- Upstream baseline: 104 passed, zero skipped or failed.
- Recorded upstream runs: 104 passed in each of three repetitions; 312 invocation windows;
  zero transformation errors; 351 distinct recorded methods, 344 stable training methods.
- Clustering: 53 inferred execution families; 104/104 held-out repetitions return to the same family.
  This measures repeatability of the same tests in one JVM, not unseen-scenario accuracy or robustness
  across processes, scheduling seeds, framework versions, or mock/real-container differences.
- Our Spring `allTests` run: 235 passed, 105 skipped, zero failures/errors. Four collector reports healthy.
  `test`: 103 passed / 61 skipped; `forkedTest`: 132 passed / 44 skipped. Both resolve Spring 6.0.2.
- Expanded inventory: 93 classes, 1,231 eligible methods; all 344 stable reference methods are eligible.
- All 340 test results are explicitly accounted for: 101 single candidates, 33 ambiguous/multiple,
  79 unmatched, 20 registered windows without library observations, 2 without collection, and 105 skipped.
  Task, suite and test identity are retained; identical names across targets are not merged.

The initial narrow inventory exposed only 67 reference methods: 3 single candidates, 4 ambiguous,
83 unmatched out of 90 observed windows. Expanding observation improved comparability; it did not
change instrumentation or establish that inferred labels are correct.

Important counterexample: generic response tests strongly resemble an upstream mocked resource-return
case because much of their observed processing overlaps. A high score alone does not establish that
our test returns a Resource. Treat `SINGLE_CANDIDATE` as a retrieval result, not a verified scenario.
The report exposes absent methods and original tests so a reviewer can challenge this inference.

## Async and collection limitations

Worker attribution is a serialized active-test-window association. The upstream recorder captured
60 worker-thread entries across the three runs. Synchronous nesting edges are retained, but
cross-thread submission-to-callback links are not recorded. Nesting edges are deliberately not used
for classification because our existing harness provides method-entry sets, not equivalent edges.

A full execution-flow graph would need independent identities at submission, callback registration,
execution and completion, plus unresolved-edge markers. Those identities must not depend on tracer
Context, otherwise propagation gaps would hide the flow being investigated. Mock event invocation
is not evidence of real container scheduling or a transport failure. Late asynchronous work can also
be temporally associated with a later test; these associations are not suitable as causal proof.

## Aggregate collection and complete accounting

The module uses `allTests`, which expands to `test` and `forkedTest` here. The repository deliberately
excludes latest-dependency targets from `allTests`; those are selected by `allLatestDepTests`.
The Gradle bridge discovers concrete Test tasks, writes observations under a task-specific directory,
and records resolved artifact coordinates for every target. Incompatible artifact sets are rejected
rather than joined to the wrong graph; separate version-matched collection is then required.

`accounting.py` starts from XML results, including skipped tests, and attaches classifier observations
by target/suite/display name. It distinguishes no collector report, no test window, no library
observations, unmatched, single/multiple candidates, and ambiguous identities. Unreconciled classifier
windows fail validation instead of disappearing. Health counts describe reports, not scope completeness.

The legend uses strong green/slate swatches, a split swatch for Context + root, stripes for not observed,
and a dotted question mark for methods outside observation scope. Purple indicates selection.

## Reproduce

Use a full source checkout/archive extraction containing Spring `spring-web`, `spring-webmvc`, and
`spring-web/src/testFixtures` at v6.0.2. From this worktree, with the repository JDK/Gradle environment:

```sh
python3 tools/instrumentation-coverage/cartography/run.py \
  --source tools/instrumentation-coverage/cartography/build/upstream-source/spring-framework-6.0.2
```

This command updates only this worktree's `coverage/observation.json` to include observed reference
types, preserves all existing observation classes, runs the workflow, and prints the report path.
Generated files remain under `build/`; source inputs are hashed. No commits or publication occur.

To regenerate only the statistical report from this successful run:

```sh
python3 tools/instrumentation-coverage/cartography/classify.py \
  --reference tools/instrumentation-coverage/cartography/build/reference.json \
  --baseline tools/instrumentation-coverage/cartography/build/baseline.json \
  --run dd-java-agent/instrumentation/spring/spring-webmvc/spring-webmvc-6.0/build/instrumentation-coverage/c55e6ad7fa1f46139acd3254663dbb5a \
  --output tools/instrumentation-coverage/cartography/build/report
```

Classification verifies the sealed agent-run evidence inventory/hashes and library fingerprints.
The same saved inputs produce the same report data. Rerunning actual tests may produce different paths.

## Checks

```sh
python3 -m unittest discover -s tools/instrumentation-coverage/cartography -p 'test_*.py' -v
./gradlew -p tools/instrumentation-coverage spotlessCheck
node tools/instrumentation-coverage/cartography/capture.mjs
```

The browser check uses locally installed Chrome and writes screenshots under `build/report/`.

## Test guidance and LLM handoff

Each flow has a source-backed test experiment. Choose one upstream example to see its recorded
landmarks, inspect the matching method evidence, and download a JSON task for that example. The task
includes source code and links, target module/version, candidate tests to inspect, instructions and
acceptance criteria. It asks for tests only, preserving production instrumentation and existing
assertions. The chosen example avoids treating a mixed family's union as one required path.

Landmark methods are ranked within their functional group by the existing reference IDF weights;
all methods are retained, with context-present methods collapsed by default. This ranking is a
navigation heuristic, not a proof that hitting a method establishes the behavior. The task requires
behavioral assertions and a before/after allTests collection. Statistical diagnostics and full test
accounting remain accessible in a collapsed “Test inventory and collection details” section.

The test-plan panel defaults to observation gaps for the selected upstream example and selected test
(or aggregate): not observed, root/mixed for contract review, and outside scope for collection changes.
Context-present methods are collapsed. All methods remain available; the plan no longer truncates
landmark groups to three methods. Guidance distinguishes adding/identifying a scenario from reviewing
an existing test, and does not recommend a new test when no method gap is visible. Downloads include
the selected evidence IDs, per-method states/counts, suggested action and focused instructions.
