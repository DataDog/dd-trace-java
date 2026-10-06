# Context coverage: intent, prototype, and next experiments

Saved 2026-09-24. Local working document; no commit or publication.

## Intent

During existing instrumentation tests with the real Datadog agent instrumentation active, identify library execution where no non-root Context is active. Help a human or an LLM understand which functionality has evidence, which execution contains gray areas, what remains unknown, and what to test or investigate next.

The criterion is generic Context, not the presence of a span. Root observations are candidates for investigation, not automatically instrumentation bugs. Non-root Context does not prove correct request identity or propagation lineage.

## Desired workflow

1. Run existing tests with production instrumentation enabled and an independent observer.
2. Inventory selected library methods and record execution with root/non-root Context.
3. Present functionality at a glance, with drilldowns to methods, scenarios, and stacks.
4. Suggest concrete tests for unknown behavior and boundary investigations for gray execution.
5. Preview conditional recovery without changing or misrepresenting measured evidence.
6. Execute a proposed experiment and compare real before/after evidence before claiming a gain.

A small propagation fix could affect many methods, but that reach must be demonstrated on actual instrumented tests. Existing executor instrumentation must not be assumed missing.

## Current implementation

Worktree: `/private/tmp/dd-trace-java-context-coverage`
Branch: `prototype/context-coverage`
Prototype: `tools/instrumentation-coverage/`

A Byte Buddy observer instruments entry to declared eligible methods of exact selected classes. It reads `Context.current() != Context.root()` without activating Context or wrapping application tasks. It records counts, representative stacks, thread information, and initiating-thread scenario labels. Workers are currently unattributed.

A Spock global extension attaches this observer to existing InstrumentationSpecification tests. Collection runs during feature bodies, excluding external setup/cleanup. The observer installs after `setupSpec`, preserving load-time test configuration and the production transformer's ordering around the observed method body. Reports verify a registered tracer, an active production transformer, required transformed classes, and the Context class loader. The observer jar contains no duplicate Context implementation.

Output: JSON, Markdown, and standalone offline HTML with plain JavaScript/CSS. No web server or external UI dependencies. Reports include collection health; errors or dropped evidence must be considered before interpreting absence of observations.

## Profiling / JFR

The prototype now has two evidence paths. Method-entry observation provides exact entry counts and
generic root/non-root Context for a selected inventory. A profiling-only path records the unchanged
test JVM with OpenJDK JFR and analyzes CPU/native, allocation, and blocking stacks without advising
library methods.

In the Spring experiment, exact entry evidence observed 107 of 253 selected methods. The dense JFR
run sampled 31 selected methods, including 18 in CPU/native stacks, and 166 Spring Web methods in
total. Of those library methods, 135 were outside the selected inventory. The broader discovery
included invocation and message-conversion classes that the entry collector could not transform.
See [PROFILING_FINDINGS.md](PROFILING_FINDINGS.md) for the setup, limitations, and artifacts.

Profiling is therefore useful for expanding the inventory, reconstructing runtime paths, and ranking
candidate work by sampled CPU or allocation activity. It cannot replace deterministic execution
evidence for short tests: absence from a sample remains unknown.

Ordinary execution samples do not directly encode generic Context state. A useful implementation
must associate a sample with the sampled thread's Context, or correlate execution with accurately
recorded activation/restoration intervals. Reading Context from a separate sampling thread would
read that thread's state.

Custom JFR events could carry Context state, thread/time, and stacks. Emitting an event at every observed method entry is still instrumentation, merely using JFR as the recording mechanism. Sampling cannot establish exact method coverage: unsampled code remains unknown.

## Measured validation

### Guava ListenableFutureTest

Real production instrumentation active; all 8 existing tests pass with and without collection.

Selected inventory: 20 eligible methods, 10 executed; 104 non-root entries and 32 root entries. ChainingListenableFuture.run had 8 non-root entries and zero root entries. No propagation fix demonstrated.

Report: `dd-java-agent/instrumentation/guava-10.0/build/reports/context-coverage/ListenableFutureTest/report.html`.

### Spring WebMVC 6

Existing module `test` task on JDK 21; Spring test dependencies use 6.0.2. Baseline and observed runs match: 158 cases reported, 97 executed, 61 skipped, zero failures/errors. This does not include forkedTest or latestDepTest. JUnit unit tests run normally; collection applies to the two instrumentation specifications.

The latest flow-report run expands the selected inventory to 25 classes and 395 eligible methods:

| Specification | Methods observed | Non-root entries | Root entries |
| --- | ---: | ---: | ---: |
| SpringBootBasedTest | 130 | 10,507 | 100 |
| UrlHandlerMappingTest | 72 | 7,770 | 0 |

The union is 139 distinct observed methods, 35.2% of this selected inventory—not the entire library. Both reports finalized with zero collection errors or dropped observations and bootstrap Context.

Spring Boot method states: 61 non-root only, 66 mixed, 3 root only, 265 unobserved. Root-only methods are WebAsyncManager.getConcurrentResult, getConcurrentResultContext, and clearConcurrentResult. Worker observations are attributed to the one serialized active-test window; this associates them with the running feature without establishing causal request lineage. Async redispatch is an investigation hypothesis, not a confirmed propagation defect.

InvocableHandlerMethod and ServletInvocableHandlerMethod are excluded from observation because inventory/transformation resolves optional org.reactivestreams.Publisher, absent from this test runtime. Initial attempts failed visibly. The final run did not add that dependency or remove their production instrumentation. Keep this limitation documented here; the user explicitly declined saving it to the separate improvement queue.

Reproduce: `bash tools/instrumentation-coverage/research/legacy/run-spring-webmvc6.sh`.
Reports: `dd-java-agent/instrumentation/spring/spring-webmvc/spring-webmvc-6.0/build/reports/context-coverage/<fully-qualified-spec-name>/report.html`, with adjacent JSON/Markdown and parent validation.json.

## Functionality view

Six explicit curated capability mappings: request routing, controller execution, async requests/redispatch, error handling, response rendering, and request lifecycle/interceptors.

Cards show observed, gray, and unknown method evidence. Selecting a capability filters the underlying method map; selecting a method exposes actual observations and example stacks. Class/method mapping rules are inspectable in the HTML. Categories overlap; unmapped methods remain accessible.

This is a semantic grouping of method evidence, not complete functional coverage. Setup/configuration methods in the inventory may remain unobserved because collection excludes setup. Suggested behavioral scenarios are inspection prompts, not proof those tests are absent. Avoid a functionality percentage until a credible scenario inventory and runtime attribution exist.

An LLM could draft capability/scenario mappings from source and tests; human validation remains necessary. Prefer curated mappings over inferring functionality from method names alone.

## Flow-first observational report

The graph experiment now keeps library knowledge separate from CI observations. The Spring MVC
6.0.2 bundle declares a bounded catalog of 17 request-processing flows and the expected library
methods for each flow. Its scope covers annotated controllers, result handling, exceptions,
interceptors, Servlet async return types, view rendering, and the module's SimpleUrlHandlerMapping
path; exclusions are stored with the catalog.

Each flow also declares a distinctive observation predicate (`allOf`, optionally `anyOf` and
`noneOf`) used to associate test windows. Shared dispatch methods cannot identify a flow by
themselves. This corrected the prototype's earlier interpretation of the `/forwarded` test: the
controller returns `CompletableFuture`, so its evidence belongs to the CompletionStage adaptation
flow, while an explicit controller `DeferredResult` return remains without a matching executed test.

The HTML is now a stable viewer template backed by a versioned `report.json`; the generator also
writes a self-contained snapshot for offline sharing. It shows every flow in the declared scope as
a segmented coverage card. Selecting a flow
lists only its matching tests, groups identical evidence into a test-by-stage matrix, and projects
the selected aggregate, pattern, or test onto an ordered flow corridor. Method evidence opens in a
detail drawer. Five observational states remain distinct: Context-present, root, mixed, not
observed, and unknown or outside the exact inventory. Profiling, recommendations, and simulated
recovery are deliberately absent from this view so that every color is traceable to versioned
knowledge plus CI evidence. Selecting a corridor method opens a bounded call-coverage inventory:
all direct calls within the analyzed artifacts and one additional level, grouped by depth and
colored with the selected test's evidence. The user can then select a root, unobserved, or
outside-inventory target and inspect one focused static route from the corridor method, including
invocation kind and source line. This route is an investigation aid and does not claim observed call
order, causal lineage, or thread handoffs.

For a flow with no matching test, the report turns the flow predicate into a coverage contract.
Shared prerequisites are kept separate from the `allOf` or `anyOf` methods that uniquely establish
the scenario; `noneOf` methods are shown as exclusions and later steps as completion checkpoints.
An optional versioned exercise recipe describes the behavior needed to activate the flow. This
prevents a generic shared method such as `doDispatch` from becoming the primary guidance for a
missing Callable, DeferredResult, or view-rendering scenario.

Uncovered flows now produce deterministic `ADD_FLOW_TEST` handoff bundles in JSON and Markdown.
The UI downloads the JSON directly from the loaded report, while CI also publishes each bundle as
an individual artifact. A bundle includes target repository/module/version, the flow contract,
exercise recipe, current observed evidence and collection health, static reachability, commands,
constraints, acceptance criteria, and claim provenance. This lets an LLM implement a bounded test
and regenerate the same report to verify the result. The task model reserves separate future kinds
for Context-gap investigation, entry-inventory expansion, and instrumentation-change verification;
the prototype does not infer those actions merely because a method is root or unobserved.

## Recovery simulator

The HTML offers four curated, unexecuted experiment plans:

| Experiment | Conditional targets in Spring Boot recording |
| --- | --- |
| Callable controller | 2 unobserved startCallableProcessing overloads |
| Deferred-result failure/lifecycle | 4 unobserved methods: setErrorResult, onTimeout, onError, onCompletion |
| Named-view rendering | 2 unobserved methods: render, resolveViewName |
| Async redispatch Context investigation | 3 root-only async-result consumption methods |

Each plan includes source rationale, concrete steps, assumptions, and validation criteria. JSON export is marked HYPOTHETICAL_NOT_EXECUTED. Purple tiles visualize conditional targets; measured maps/counts are never changed.

For a test proposal, projected recovery means “would be observed; Context state unknown.” Callback registration is not callback execution. For an instrumentation investigation, context-present recovery is conditional on demonstrating unintended loss and successfully restoring Context for the targeted entries. No predicted invocation counts, downstream gains, or confirmed instrumentation fixes.

The existing `/forwarded` endpoint returns CompletableFuture; isolate and measure that scenario before proposing a redispatch change. Do not add blanket executor propagation or activate Context inside getters to manufacture green results. Named-view tests should assert actual resolver/view execution; source already contains a RedirectView endpoint, which is not proof the measured run exercised named-view resolution.

Browser checks cover capability filtering, four projection target sets, unchanged measured totals, gray/unobserved filters, and mobile layout. The updated observer jar includes the UI. Simulations reuse existing recordings; the proposed tests and fixes have not been executed.

## Lessons and guardrails

An earlier synthetic demo deliberately omitted propagation and measured a recovery. That validated the observer/paired-experiment mechanism; it did not discover missing production executor instrumentation. Do not present its gains as an actual agent finding.

Distinguish three questions:
- Did this selected code execute?
- Was non-root Context present at entry?
- Was the expected Context correctly propagated for this behavior?

The current prototype answers the first two, subject to its collection windows and inventory. It does not fully answer the third. Counts are method entries, not CPU time, whole-body coverage, or behavioral completeness.

## Earlier decision points

- Execute one recovery plan against real agent-enabled tests, retaining baseline and intervention reports.
- For async redispatch, establish per-request/test correlation and boundary Context evidence before deciding whether instrumentation needs a fix.
- Build a reviewed behavioral scenario inventory to distinguish known tests, exercised behavior, unknown behavior, and intentionally root execution. This is now implemented for the Spring and RxJava prototype catalogs.
- Build a versioned library behavior graph from bytecode, documentation, tests, and runtime evidence. This is now implemented as the graph and knowledge stages of the unified workflow;
  see [LIBRARY_BEHAVIOR_GRAPH_IDEA.md](LIBRARY_BEHAVIOR_GRAPH_IDEA.md). Preserve the lossless
  artifact graph and derive simplified, graph-theory-based views before mapping documented flows.
  Allow sanitized customer and escalation samples to enrich flow relevance, variants, and evidence
  without turning a single occurrence into a universal expectation.
- Prototype generic Context lifecycle duration events and correlate them with JFR samples; keep this
  distinct from production span timeline attribution.
- Generalize capability maps only after validating the Spring prototype's usefulness.

No commit, push, external publication, or automatic improvement-queue capture is authorized by this document.

## Cross-language feasibility follow-up

See [CROSS_LANGUAGE_FEASIBILITY.md](CROSS_LANGUAGE_FEASIBILITY.md) and its source-pinned research notes. Parallel source investigation supports dedicated adapters for Python, Ruby, Node.js, .NET, PHP, and Go. Only Java has a runtime prototype so far. CI produces evidence, the instrumentation toolkit uses it for improvements and gate definitions, and the portal visualizes functionality coverage. Context reach, profiling attribution, scope/lineage correctness, and expected instrumentation behavior remain separate dimensions.

## Repeatable module workflow

The first supported workflow now lives in `tools/instrumentation-coverage/`, with usage,
architecture, and knowledge-authoring documentation. Spring WebMVC owns `coverage/library.json`,
`coverage/flows.json` and `coverage/observation.json`. The shared viewer moved to
`tools/instrumentation-coverage/viewer/index.html`.

The workflow resolves the selected test task's actual artifacts, hashes them, generates/caches the
static graph, validates flow bindings, and runs existing Spock instrumentation tests with opt-in
collection. It snapshots definitions, separates worker output, records run identities, checks
collection health, and joins only the current run. Replay rejects changed or additional evidence.
The first verified run retained 97 passing and 61 skipped tests, two healthy reports, 17 declared
flows and six ADD_FLOW_TEST bundles. Runtime artifact selection yielded ten Spring artifacts,
36,537 methods and 111,835 invocation sites (5,527 invokedynamic); this graph is intentionally a
different scope from the earlier eight-artifact experiment.

The harness now assigns every Spock iteration and JUnit invocation a stable adapter-owned scenario
ID while retaining a separate display name. Method observations carry both their attribution
mechanism and confidence: exact for the initiating test thread, temporal for a serialized active
test window, and none when unattributed. The join uses scenario IDs, so equal or changing display
labels do not merge distinct invocations. This metadata remains independent of `Context`; it cannot
turn a root observation into a non-root one.

Request-level causal attribution, configuration-cache-compatible convention-plugin packaging, and
CI gate adoption remain outside this first supported slice. The next attribution step can correlate
the stable scenario identity through explicit request or reactive carriers and report that as a
stronger evidence channel without changing native Context semantics. The executable workflow is
deterministic; functional definitions remain reviewed human/LLM-authored knowledge.


## JUnit adapter validation

Jupiter collection is now supported for `AbstractInstrumentationTest`. It intercepts inherited
harness initialization and teardown, collects test invocation bodies, and checks the real tracer,
production transformer, transformed-class inventory, and bootstrap Context. Ordinary and
parameterized RxJava 3 tests passed unchanged in both baseline and observed runs: 70 passed, zero
failures/skips, five healthy finalized reports, and 70 distinct scenario IDs (52 parameterized).
Five selected core reactive types supplied a 1,529-method inventory. These observations validate
the adapter. RxJava now also has an 18-flow draft functional catalog. New runs emit the stable Jupiter
scenario ID separately from the human display name and preserve attribution confidence in the
joined report.

Use `python3 tools/instrumentation-coverage/validate-junit.py` to reproduce. The guide is
`tools/instrumentation-coverage/JUNIT.md`. Failure-path tests verify that test assertion identity,
Context, and setup/teardown exclusion are preserved.

## Knowledge-authoring skill and queries

The repository skill `.agents/skills/library-flow-knowledge/SKILL.md` now describes the human/LLM
step from a supplied versioned graph plus documentation/source to a bounded draft catalog. It
produces `flows.json`, claim-level `evidence.json` and `knowledge-review.md`, challenges predicates
with adjacent scenarios, and never promotes a draft to reviewed automatically.

`graph-query.py` provides bounded public-API/search/describe/caller/callee/path queries with graph
fingerprints, source-line metadata and explicit truncation or search-budget exhaustion.
`validate-knowledge.py` checks structural references, artifact version, bindings, source IDs and
optional claim provenance while explicitly declining semantic approval. Completion roles are now
explicit (`allOf`, `anyOf`, `optional`) in new catalogs, reports and task exports. The Spring catalog
was migrated preserving its previous interpretation. The skill and helpers were validated against
the RxJava graph and Spring mapping. The resulting RxJava draft includes `flows.json`, claim-level
`evidence.json`, and `knowledge-review.md`.

## Unified, automatable workflow

The implementation is now consolidated under `tools/instrumentation-coverage/`. The previous
`tools/context-coverage/` prototype root was removed, while its historical notes and demos were
preserved under `research/`. A top-level repository skill,
`.agents/skills/instrumentation-quality/SKILL.md`, guides onboarding, routine collection,
evidence-driven improvement, and dependency upgrades. It invokes the narrower
`library-flow-knowledge` skill only for the semantic authoring step.

`workflow.py` provides one deterministic CLI with `init`, `graph`, `validate-knowledge`, `run`,
`report`, and `compare` commands. A module keeps five reviewed, version-linked files under
`coverage/`: library identity, observation inventory, functional flows, source evidence, and review
notes. Generated graphs, bindings, observations, reports, and comparisons remain build artifacts.
This makes normal CI collection independent of an LLM; an LLM or human is needed when onboarding a
library, reviewing changed semantics, or implementing an evidence-backed recovery.

The join now emits typed investigation bundles. An uncovered flow proposes a focused flow test; an
expected method outside the entry inventory proposes expanding observation; an eligible but unseen
method proposes a reachability investigation; and root/mixed entries propose a Context
investigation. These are statements about the next experiment, not defect classifications.
`CHANGE_INSTRUMENTATION` remains reserved until a focused expectation fails for the same scenario
and a before/after run demonstrates the correction.

The complete pipeline was exercised on RxJava 3. It generated a graph containing 10,315 methods,
27,460 invocation instructions, and nine invokedynamic sites; validated and bound 18 flows; ran the
existing agent-enabled JUnit suite; collected five healthy reports; and generated a reusable report
plus 13 Context-investigation bundles. The Python workflow suite and Java collector suite pass, and
a same-report comparison produces 18 unchanged flows. The number of investigation bundles is not a
quality score: it shows where reviewed expectations and root/mixed observations still need focused
interpretation.

The unified onboarding workflow was then exercised on the existing gRPC 1.5 instrumentation module
against gRPC Java 1.42.2. A bounded draft catalog maps 12 client/server lifecycle flows over a
6,373-method, seven-artifact graph with 15,614 invocation instructions. The collection-enabled suite
passed all 118 tests and produced seven healthy reports over a 128-method entry inventory. Seven
flows matched tests; five client API variants had no matching test: asynchronous unary, direct
future unary, asynchronous and blocking server streaming, and asynchronous client streaming.
Runtime evidence also corrected the draft knowledge: `blockingUnaryCall` internally traverses
`futureUnaryCall`, so the direct-future predicate now excludes the blocking entry instead of
misclassifying 84 blocking test windows. Root/mixed observations remain investigation prompts, not
defects; existing bidirectional-stream callback Context assertions continued to pass.

This pilot exposed two generic harness compatibility issues before it exposed any production
instrumentation failure. Some instrumentation modules use a Gson version predating
`JsonArray.isEmpty()`, and helper specifications may not load every production type required across
the module. The observer now uses the older compatible Gson API and validates required transformed
types against the union of all healthy specification reports. Focused workflow and collector tests
cover the run-level validation behavior.

Five focused gRPC tests now exercise the previously unmatched client variants through generated
public stubs. The ordinary and collection-enabled suites both pass all 123 tests. Each new flow is
matched to its dedicated scenario; the four five-method corridors are fully observed with non-root
Context, while the direct-future corridor observes its five executed methods and correctly leaves
the excluded blocking entry unobserved. No production instrumentation change was required.

This run also found an observer-ordering defect after rebasing onto current `master`. Installing the
observer before `setupSpec` loaded selected gRPC classes before the test applied ignored-method and
server-error configuration, producing 12 false test failures. The Spock adapter now installs after
`setupSpec`, retaining the production transformer's load-time configuration and ordering. The same
collection run then passed without relaxing any gRPC assertion.

Two additional parented experiments separate the remaining aggregate root observations. A generated
bidirectional-streaming call ran its public API, start, sends, half-close, asynchronous client
listener, and close paths with non-root Context; its trace parentage and application callback
Context also passed. A low-level client call was then created and started under a parent but
cancelled after the parent scope closed. Its client span retained the correct parent and completed
with `CANCELLED`, while exact method-entry evidence recorded both `ClientCallImpl.cancel` and
`cancelInternal` at root. This is an execution-attribution candidate rather than a demonstrated RPC
tracing failure: OpenTelemetry defines the RPC lifetime through cancellation but does not require
the internal cancellation method itself to activate the call span. The complete ordinary and
collection-enabled suite now passes all 125 tests.

The flow-authoring model now carries these semantics forward through an optional, versioned
`contextContract`. Contracts declare their scenario precondition and map flow steps to `PRESENT`,
`ABSENT`, `EITHER`, or `UNRESOLVED` active-Context expectations with source-backed rationale. The
authoring workflow searches the target module and analogous repository instrumentation first, then
the library's own semantics, and uses official OpenTelemetry specifications only when local
precedent is absent or inconclusive. It explicitly separates tracing correctness from execution
attribution: span parentage and lifetime may be correct even when an internal method enters at root.
The gRPC catalog now records the parented bidirectional lifecycle as `PRESENT` and cancellation as
`EITHER` pending review, based on the target implementation, focused observations, and the closest
Armeria gRPC precedent. Validation checks contract steps, states, sources, and rationale; joined
reports and downloadable tasks preserve the contract without automatically converting it into a
production-change recommendation.

## Core JDK audit

The workflow now supports core JDK libraries as versioned inputs. It exports the selected test
launcher's exact JMOD instead of resolving a Maven artifact, includes invokedynamic in the same
static graph, and observes modifiable bootstrap classes through a minimal bootstrap bridge. The
bridge forwards method IDs to the application-loaded collector; it does not carry or activate
Context. A module may also combine multiple Gradle test tasks in one evidence run and declare a
reviewed distinct-observation budget for large parameterized suites.

The first audit targets `java-concurrent-1.8` on Oracle JDK 25.0.2+10-69. Its `java.base.jmod` graph
contains 65,520 methods, 230,175 invocation instructions, and 2,305 invokedynamic call sites. A
bounded draft catalogs 21 ExecutorService, scheduled-executor, FutureTask, ForkJoinPool,
CompletableFuture, Timer, cancellation, and rejection flows. The combined `test` and `forkedTest`
run initially recorded 1,001 tests: 973 passed, 28 were skipped, and none failed. Twenty-one
finalized observation reports contained no collector errors or dropped observations. Across 37
unique expected flow checkpoints, 36 were observed. The missing checkpoint was the JDK 25 branch
used when a ForkJoin worker submits a Callable: `ForkJoinTask.AdaptedCallable.exec()`. The external
Callable branch and both Runnable branches were already observed, making this a focused missing test
rather than an inferred instrumentation defect.

A focused test now submits a Callable from within a ForkJoin worker under an existing parent span.
It asserts that the inner callback sees the exact parent span and that no additional span is emitted.
The collected rerun recorded 1,002 tests: 974 passed, 28 were skipped, and none failed. It attributed
one non-root entry of `AdaptedCallable.exec()` to the new scenario, bringing the declared scope to
37 of 37 observed checkpoints without a production instrumentation change. Exact source review also
corrected an earlier flow draft: JDK 25 `submit` always uses `poolSubmit`; external callers use
`AdaptedInterruptible*`, while ForkJoin workers use the non-interruptible adapters.

The workflow now separates flow coverage from catalog completeness. A versioned `catalog.json`
inventories reviewed functionality families as mapped, candidate, or explicitly excluded, binds
representative public entry methods to the exact graph, and is joined with runtime entry evidence.
For the Java-concurrent audit, all 21 flows fall under nine mapped families; ten additional families
need semantic review and four are excluded. Five candidates were already exercised by existing tests
despite lacking flows (timed `invokeAll`, `invokeAny`, direct ForkJoin task operations, one
`runAsync` variant, and `allOf`), while five had no observed representative entry (CountedCompleter,
CompletableFuture recovery, timeout, binary-combination, and either-of families). This prevents a
37-of-37 declared-checkpoint result from being mistaken for comprehensive library coverage. The
report presents mapped and candidate flows in the same grid and coverage totals; candidate cards are
visually marked and count representative entry evidence until a full flow corridor is reviewed.

Six expected methods were root-only at entry, including `ForkJoinTask.doExec`,
`ScheduledFutureTask.run`, and `CompletableFuture.AsyncSupply.run`. These are not defect findings:
collector advice may execute before production entry advice activates captured Context on the same
method. A nested callback checkpoint or an explicit callback assertion is required before assigning
a `PRESENT` contract. This ordering caveat is now part of the module review notes.
