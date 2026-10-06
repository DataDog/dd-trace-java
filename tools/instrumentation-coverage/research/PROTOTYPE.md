# Context coverage during instrumentation tests

For the module-owned, run-isolated workflow, start with
[Instrumentation coverage](../instrumentation-coverage/README.md). The commands below retain the
original experiments and collector development entry points.

Collect **generic Context presence** while the repository's existing instrumentation tests run with
CoreTracer and the real agent instrumentation modules installed. The observer does not activate
Context, create spans, wrap tasks, or change the test body. It produces JSON, Markdown and an
offline interactive map of the observed root/non-root method entries.

## Run against an existing test

From the worktree root (JDK 21+ for Gradle):

```shell
./gradlew -p tools/instrumentation-coverage observerJar
./gradlew -I tools/instrumentation-coverage/research/legacy/instrumentation-tests.init.gradle \
  :dd-java-agent:instrumentation:guava-10.0:test \
  --tests ListenableFutureTest -PtestJvm=21
```

Open:

`dd-java-agent/instrumentation/guava-10.0/build/reports/context-coverage/ListenableFutureTest/report.html`

The adjacent `report.json` and `report.md` contain the same evidence. The existing test ran both
without observation and with observation: all eight cases and their original trace assertions
passed. In the first observed run, 10 of 20 selected methods executed, with 104 non-root entries and
32 root entries. `Futures$ChainingListenableFuture.run()` had context on all eight calls. These are
observations, not declarations that the root entries are defects; for example, promise construction
outside a parent context legitimately runs at root. Counts describe this run, not all of Guava.

The selected inventory is AbstractFuture, SettableFuture, ExecutionList and
Futures$ChainingListenableFuture from the actual Guava dependency resolved by that test.

## How integration works

The init script adds an observer-only jar to the selected test task's runtime classpath. An opt-in
Spock extension installs the probes **before** `InstrumentationSpecification.setupSpec` installs the
production transformer. The later production advice surrounds the observer-instrumented method
body, allowing context activation to precede observation. It verifies registered AgentTracer,
non-null production transformer and the configured required transformed classes before collecting.
The collector is paused outside feature execution and reset before agent teardown.

The observer jar contains **no Context implementation**. It reads the same bootstrap-loaded Context
as the tracer; the report records this loader identity, tracer class and production transformed
classes. The support package is outside the harness's bootstrap namespace. This uses the actual
instrumentation-test harness, not a packaged-agent smoke application.

Original test iterations label the initiating thread only. Worker observations remain
`<unattributed>` rather than guessing their request identity or carrying extra context. This first
integration aggregates feature windows per specification. Late background work overlapping another
feature cannot be assigned to a specific test; work outside all feature windows is excluded.

To select another Spock instrumentation test, use these project properties with the same init script:

- `-PcontextCoverageProject=:path:to:instrumentation`
- `-PcontextCoverageTask=test` (or its dependency-test task)
- `-PcontextCoverageClasses=fully.qualified.Type,fully.qualified.OtherType`
- `-PcontextCoverageRequireTransformed=fully.qualified.ExpectedInstrumentedType`

Select one specification with `--tests`, run on JDK 17+, and explicitly select classes in the test
application loader. Bootstrap and isolated application-loader targets are rejected. The init script
opts into execution on each run. No source changes to the instrumentation tests are needed.

## Collector validation (synthetic; not an instrumentation finding)

```shell
./gradlew -p tools/instrumentation-coverage test spotlessCheck
```

The standalone validation build compiles the repository's context sources and deliberately exercises
missing/restored propagation to test the detector. Its separate report lives under
`tools/instrumentation-coverage/build/reports/context-coverage/`. It is prominently labeled synthetic.
Use the **instrumentation-test report above** to inspect execution with the real agent enabled.

## Profiling-only experiment

`profiling-tests.init.gradle` records an existing test task with OpenJDK JFR and does not load the
method-entry observer. Use `%p` in the output name so separate worker JVMs cannot overwrite each
other:

```shell
./gradlew -I tools/instrumentation-coverage/research/legacy/profiling-tests.init.gradle \
  :dd-java-agent:instrumentation:spring:spring-webmvc:spring-webmvc-6.0:test \
  -PtestJvm=21 \
  -PprofileCoverageProject=:dd-java-agent:instrumentation:spring:spring-webmvc:spring-webmvc-6.0 \
  -PprofileCoverageMethodProfiling=max \
  -PprofileCoverageOutput=/absolute/output/profile-%p.jfr
```

Analyze a recording against one or more existing entry reports:

```shell
./gradlew -p tools/instrumentation-coverage analyzeProfile \
  -PprofileArgs='<recording.jfr> <output-directory> org.springframework.web. <report.json> [report.json]'
```

The analyzer writes `profile-analysis.json` and `profile-analysis.md`. It reads full JFR stacks,
groups CPU/native, allocation, and blocking evidence, and lists sampled library methods outside the
declared entry inventory. It does not interpret an unsampled method as unexecuted.

Embed the analysis in an existing standalone report:

```shell
./gradlew -p tools/instrumentation-coverage enrichProfileReport \
  -PprofileReportArgs='<entry-report.json> <profile-analysis.json> <report.html>'
```

The enriched report keeps Context colors and counts unchanged. Orange outlines mark selected methods
seen in task-wide profile stacks, with logarithmic intensity relative to the selected CPU or
allocation signal. An evidence matrix distinguishes sampled gray execution, sampled methods without
entry evidence, corroborated Context execution, and exact entry evidence missed by sampling.
Representative stack paths preserve the execution flow, while discoveries outside the inventory
suggest classes to review. The profile layer is explicitly scoped to the complete test worker rather
than the individual instrumentation specification.

The measured Spring results and interpretation are in
[PROFILING_FINDINGS.md](PROFILING_FINDINGS.md). The denser recording sampled 31 of 253 selected
methods and 166 Spring Web methods overall, while exact entry evidence observed 107 selected methods.
Profiling exposed useful paths beyond the inventory, including invocation classes the entry observer
could not transform. Standard JFR supplied no generic Context root/non-root state.

## Library behavior graph experiment

Build a lossless declared-call graph for Spring WebMVC 6.0.2 and its focused Spring Framework
dependency closure, calculate deterministic simplified views and graph metrics, then bind a
versioned, bounded request-flow catalog to graph nodes and existing Context evidence:

```shell
./gradlew -p tools/instrumentation-coverage analyzeSpringWebMvcFlows
```

Output is under
`tools/instrumentation-coverage/build/reports/library-graph/spring-webmvc-6.0.2/`. `raw-graph.json`
preserves all declared methods and bytecode invocation sites. It records `invokedynamic` call-site
identity, bootstrap methods, bootstrap constants, and method-handle arguments; LambdaMetafactory
implementation handles become explicit candidate edges. `analysis.json` and `core-graph.json`
contain the simplified structural view, k-core, PageRank, and class/package aggregates.

`flow-analysis.json` and `flow-analysis.md` bind the 17-flow catalog from
`dd-java-agent/instrumentation/spring/spring-webmvc/spring-webmvc-6.0/coverage/flows.json`. Its declared scope covers annotated controllers,
result handling, exceptions, interceptors, Servlet async return types, view rendering, and the
module's SimpleUrlHandlerMapping path. A missing static path is
reported as `SEMANTIC_EDGE_REQUIRED`; it is not silently invented. Optional runtime evidence comes
from the existing Spring Context reports and remains unknown for methods outside their selected
inventory. The design and guardrails are in
[LIBRARY_BEHAVIOR_GRAPH_IDEA.md](LIBRARY_BEHAVIOR_GRAPH_IDEA.md).

Join the versioned graph and flow definitions with the latest exact Context reports:

```shell
./gradlew -p tools/instrumentation-coverage joinSpringWebMvcEvidence
```

The task writes a reusable viewer and a self-contained snapshot beside the graph output:

```text
ci-join/
├── report.json                # canonical report payload
├── joined-report.html         # self-contained offline snapshot
├── joined-report.json         # named copy of the canonical payload
├── joined-report.md
├── schemas/
│   ├── joined-report.schema.json
│   └── instrumentation-task.schema.json
└── tasks/                     # JSON and Markdown agent handoffs
```

The generic viewer lives at `tools/instrumentation-coverage/viewer/index.html`, outside all
instrumentation modules and report directories. Open it locally and select a run's `report.json`,
or serve it with `?report=<report-data-url>`. `joined-report.html` remains an optional portable
snapshot containing the data for that specific run.

The web report presents every flow in the
declared catalog as a coverage-colored card. Selecting a flow shows only tests associated through
its distinctive observation predicate. The second section groups tests with identical evidence in
a test-by-stage matrix, then projects the selected aggregate, pattern, or individual test onto an
ordered flow corridor. Method details remain one click away. Context-present, root, mixed,
not-observed, and unknown states stay separate. Profiling and simulated recovery remain absent from
this observational view. Selecting a corridor method also opens a
JaCoCo-style inventory of all direct library callees plus one further level, grouped by static call
depth and colored with the selected test evidence. A gap selector reduces the static graph to one
candidate route from the corridor method to a root, unobserved, or outside-inventory target,
including bytecode invocation kind and source line. The candidate route does not infer runtime call
order, causal lineage, or thread handoffs.

When a flow has no matching test, the report derives a coverage contract from its identification
predicate. Shared prerequisite methods are collapsed, `allOf` and `anyOf` anchors become the minimum
identifying evidence, `noneOf` anchors become exclusions, and downstream steps become completion
checkpoints. Optional versioned `exercise` metadata provides the concrete behavioral recipe. The
Callable draft, for example, requires `CallableMethodReturnValueHandler.handleReturnValue` and
`WebAsyncManager.startCallableProcessing`, then checks async completion and redispatch methods.

Each uncovered flow also exposes **Download LLM task**. The downloaded `ADD_FLOW_TEST` JSON bundle
is a handoff contract rather than a prose prompt. It identifies the repository, module, library
version and flow; separates shared prerequisites, required or alternative anchors, exclusions and
completion checkpoints; includes the curated exercise recipe, current collection health, static
candidate paths, test/report commands, constraints and acceptance criteria; and labels claims as
`observed`, `static`, `curated` or `hypothesis`. Matching Markdown files are generated for humans.
The first action kind is deliberately narrow: zero matching tests produce `ADD_FLOW_TEST`; a root
observation does not automatically become an instrumentation-change task.

The current eight-artifact graph contains 30,706 methods and 95,525 invocation instructions,
including 4,459 `invokedynamic` sites and 1,645 LambdaMetafactory implementation handles. Its
unscoped maximum k-core is 7 and is dominated by utilities, configuration, ASM, and CGLIB. This
demonstrates why graph density must be combined with documented-flow, instrumentation-boundary, and
runtime-evidence filters. Flow-level execution remains `NOT_ESTABLISHED`: method observations from
different or unattributed scenarios are never combined into a claim that the whole flow ran.

In the fresh attribution run on 2026-09-25, 158 tests were reported: 97 passed, 61 were skipped, and
none failed. The expanded 25-class inventory contains 395 methods; 139 distinct methods were
observed across the two reports, with no collection errors or dropped observations. The report
correctly associates `test forwarded request [method: GET, body: null, #0]` with the
CompletionStage/CompletableFuture flow. An explicit controller DeferredResult return, Callable, and
WebAsyncTask flows have no matching executed test. Worker entries are attributed to the one serialized active Spock
feature window; the collector still does not propagate or activate Context.

## Synthetic fixtures and validation

The Guava BaseEncoding demo automatically observes concrete declared methods in three selected
classes: BaseEncoding, the base64 implementation, and its immediate superclass. This is an explicit
inventory, **not all of Guava**. It runs encoding/decoding with:

- no context;
- a context containing only an ordinary string value;
- an asynchronous call without propagation;
- an asynchronous call with a continuation resumed by the fixture;
- an explicitly attached root context;
- work after scope close;
- unattributed asynchronous work;
- a failing decode.

The collector itself only reads context. Scenario labels are supplied independently by the test,
including on the worker thread, and never attach or propagate context. The restored-context case
deliberately resumes a continuation as the positive control; it does not claim Guava does so.

Additional controls verify method overloads, unexecuted inventory entries, unchanged return values
and exception identity, nested scenario restoration, rejection of a second concurrent observer,
transformer reset/reinstallation, and rejection of bootstrap targets.
An observed Thread subclass also verifies that stack collection cannot recursively re-enter the
observer. Collector-generated calls are excluded from application entry counts.

## Low-level standalone collector API

```java
ContextCoverage coverage = ContextCoverage.observe(LibraryType.class, ConcreteImplementation.class);
try (ContextCoverage ignored = coverage) {
  ContextCoverage.inScenario("request-17", () -> exerciseLibrary());
  // Wait for asynchronous work to complete before closing the collector.
}
coverage.writeReport(outputDirectory);
```

Select concrete implementation classes and superclasses explicitly. The observer retransforms
already loaded classes and injects a method-entry call to its public helper. Keep fixture activation
outside the observed method when evaluating context presence at entry. For asynchronous attribution,
carry the scenario string through a fixture-owned request/callback identity, not through Context.
Unlabelled work is reported as `<unattributed>`; thread names never establish ownership.

## Reading the report

Only the synthetic validation report currently includes a **propagation opportunity** card: a context-present submission followed by a
root-context task and downstream gray execution. The demo measures one such boundary: restoring
context across executor submission recovers **11 methods / 12 entries** in the matched workload.
Use **Show before** and **Show with propagation** to switch the map. The remaining unexercised
library surface stays hatched; a handoff fix does not manufacture test coverage.

To investigate a boundary in a test:

```java
// Call while the submitting thread has the context you expect to propagate.
Runnable task = coverage.handoff("ExecutorService.submit", "baseline", () -> exerciseLibrary());
executor.submit(task).get();

// In a second run of the same workload, restore context in the fixture BEFORE task.run().
// The observer wrapper never captures, attaches, or restores Context itself.
coverage.compareHandoff("Propagate across submit", "baseline", "with-propagation");
```

Declare a separate handoff wrapper for the intervention scenario as well. Each wrapper is single-use
and has an independent handoff ID; method observations join by that ID, not by scenario name or
thread name. An unrelated invocation using the same display label cannot inflate the opportunity.
IDs cover the observed task body; a further asynchronous hop needs its own observation. Wrappers
carry no Context payload. This prototype requires the fixture to designate the submission point;
it does not yet automatically intercept arbitrary executors or infer an entire async graph.

An untested opportunity is `CANDIDATE`. `VERIFIED_GAIN` requires a finalized, loss-free report, a
successful baseline and intervention at the same boundary, restored entry context, and identical
per-method invocation counts. The fixture is responsible for matching inputs and changing only
propagation; equal method counts alone cannot establish that. Ambiguity, failures or mismatched
workloads produce `INCONCLUSIVE`; no recovery or any method regression produces `NO_GAIN`.
Partial improvement may recover entries without fully recovering a method.

Ranking uses measured recovered methods, then recovered entries; untested candidates use observed
downstream reach. Results from different candidates are **not additive**. Implementation effort is
not inferred: before calling a candidate low-hanging fruit, check existing executor instrumentation,
intended suppression, cancellation, task reuse and compatibility in a test with the full agent.
The standalone demo deliberately lacks that agent and does not demonstrate a production defect.

The HTML view starts with the whole selected surface: green for methods observed only with context,
gray for root-only execution, a green/gray split for both, and hatching for methods not exercised.
Squares have equal area per method, not per invocation or time. The overview bar counts methods in
each category; a mixed method's square splits by its entry counts. Select a scenario to compare
`async-context-lost` with `async-context-restored` without reading individual records. Clicking a
square opens its evidence. Summary cards follow the scenario; search and status filters affect
only the class map. All data is embedded; the report makes no network requests.

`NON_ROOT_CONTEXT` means `Context.current() != Context.root()`. It does **not** require any particular
payload. `ROOT_CONTEXT` cannot distinguish absent context from deliberate suppression by attaching
root. The API does not expose an attachment count; a root observation is a review candidate, not
proof of missing instrumentation. Context identity/lineage correctness is not checked in this first
prototype: a wrong non-root context still counts as present.

- **Method exercise coverage:** `executedMethods / eligibleMethods` for the explicitly selected classes.
- **Context presence at entry:** `contextEntries / (contextEntries + rootEntries)` for observed invocations.
- **Never observed:** methods in the inventory without any observed invocation.

No percentage describes execution time or context throughout a method body. Nested library calls
each count as separate method entries. Unexecuted methods are test gaps, not proven instrumentation
gaps. Private methods are included; constructors, abstract/native methods, bridge methods, and
synthetic methods are excluded. Methods are identified by declaring class, name, and JVM descriptor.

For an LLM, start with root observations grouped by scenario and stack; distinguish deliberate
background/root cases from unexpected loss, and inspect the caller/handoff before recommending an
activation hook. The report includes artifact paths, exact methods, counts, representative threads,
and stacks, but does not claim an automatic fix or causal reconstruction.

## Deliberate limits

- Only selected classes in the collector's defining loader and an unnamed module are supported.
  Bootstrap/custom-loader injection and production-agent transformer ordering are not implemented.
- The standalone demo uses the default Context manager without the tracing agent. The opt-in
  instrumentation-test adapter above uses the existing real tracer and instrumentations. A
  packaged-agent smoke test remains a separate validation boundary.
- The collector serializes observations and captures representative stacks. It changes timing and
  is unsuitable for performance measurement. There is no JFR or CPU/wall sampling yet.
- There is one session per JVM, up to 10,000 method/scenario/state rows, and 12 frames per example.
  Dropped detail is reported separately; summary invocation counts still include dropped rows.
- Stop/join relevant work before final reporting. A report written during collection is a snapshot.
- Transformation/observation/reset errors prevent a successful report; inspect test failures.

## Delivery record

Built as a worktree-only prototype with no production changes or commits. The transformation lens
implemented the collector; the lead supplied the isolated build, real-library demo, validation, and
simplicity check; a separate review lens checked the integrated result. The experiment deliberately
avoids production configuration, profiler plumbing, general class-loader injection, and automatic
repair before the context-presence signal has been evaluated.

Validation on JDK 25: all four JUnit tests and Spotless passed. The independent review identified
collector reentrancy; a thread-local guard and the observed-thread regression test address it.
The demo observed 11 of 38 selected methods, 24 non-root entries and 66 root entries, with no errors
or dropped observations. These are controlled fixture results, not a Guava instrumentation defect.

Two specialist lanes were used, with inherited models and no model escalation. Exact elapsed time,
token usage, and monetary cost are unavailable. The first build exposed the instrument dependency's
Java 17 baseline; the standalone prototype was aligned with it. Remaining limitations above are
explicit experiment boundaries, not claims of production readiness.

Visual report validation: checked in headless Chrome at desktop and mobile sizes. Verified overview
counts, hidden method labels/details, lost/restored scenario colors, method drill-down, labels toggle,
and the gray-area filter. The four Java tests and formatting checks remain green.

Handoff extension record: the instrumentation specialist implemented the pure analyzer; the lead
added independent task attribution, matched experiments and visual comparison; a separate reviewer
checked lifecycle and false-gain risks. Its cross-session task-ID finding was fixed and regression-tested. Nineteen focused tests pass on JDK 25, including unrelated
same-label work, incomplete/failed tasks, workload mismatch, partial recovery, regression controls and late work from earlier sessions.
No new production tracing machinery, profiler integration, or automatic executor transformation was
introduced. Two specialist lanes, inherited models, no escalation; elapsed/token/monetary telemetry
is unavailable. This extension demonstrates measured reach, not implementation cost or production
coverage. Revisit on a real instrumentation suite with the packaged tracer.

## Agent-test integration record

The transformation specialist implemented the Spock lifecycle adapter; the lead integrated packaging,
collection windows, real-test execution and provenance labels; an independent lane reviewed the
integrated artifact and ordering. The baseline and observed ListenableFutureTest both pass all eight
original cases. No production instrumentation or test source changed. The old synthetic scenario is
retained only as collector validation and is not presented as a discovered executor defect. Exact
elapsed/token/monetary telemetry is unavailable; two specialist lanes, inherited models, no escalation.

## Spring WebMVC 6 module run

Run `bash tools/instrumentation-coverage/research/legacy/run-spring-webmvc6.sh` from this worktree. The script builds the observer and runs the existing module `test` task on JDK 21 with production instrumentation enabled. It does not run `forkedTest` or `latestDepTest`. The existing JUnit unit tests run normally; collection applies to the two instrumentation specifications.

The unchanged baseline and observed run both had 158 reported cases: 97 executed, 61 skipped, zero failures/errors, identical case names and skip status. Tests use Spring 6.0.2. Twenty-five selected Spring classes contain 395 eligible methods; these are a selected inventory, not all Spring library methods.

| Specification | Executed methods | Non-root Context entries | Root Context entries |
| --- | ---: | ---: | ---: |
| SpringBootBasedTest | 130 / 395 | 10,507 | 100 |
| UrlHandlerMappingTest | 72 / 395 | 7,770 | 0 |

Together the specifications exercise 139 distinct methods (35.2% of the selected inventory). Both reports finalized successfully with bootstrap Context, verified production DispatcherServlet transformation, and zero dropped observations or collection errors.

Reports are under `dd-java-agent/instrumentation/spring/spring-webmvc/spring-webmvc-6.0/build/reports/context-coverage/<fully-qualified-spec-name>/report.html`, with adjacent JSON and Markdown. `validation.json` in the parent directory records the run summary.

The Spring Boot view has 61 context-only methods, 66 mixed, 3 root-only, and 265 unobserved. The three root-only methods are WebAsyncManager.getConcurrentResult, clearConcurrentResult, and getConcurrentResultContext. Worker observations are attributed to the one serialized active test window, which names the running feature without propagating Context; it remains weaker than causal request lineage.

Observation excludes InvocableHandlerMethod and ServletInvocableHandlerMethod: the former's method inventory and the latter's transformation resolve an optional org.reactivestreams.Publisher type absent from the existing test runtime. Initial attempts failed visibly before running test features. The final run excludes both rather than changing application dependencies. Their ordinary production instrumentation is still present. This is a collector limitation, not evidence about their Context coverage.

Configuration and initialization methods remain in the inventory even though collection only runs during test feature bodies. Unobserved methods therefore do not automatically imply missing request-behavior tests.

### Functionality view

Spring reports now open with six curated capability cards: routing, controller execution, async/redispatch, errors, rendering, and lifecycle/interceptors. Click a card to filter the method map and read scenario-inspection prompts. The “How this map is defined” disclosure contains the explicit class/method rules from `capabilityMap` in the HTML template. Categories overlap; unmapped methods remain accessible through the functionality selector. Capability cards follow the scenario filter; the global summary remains the whole selected inventory.

This is a semantic grouping of existing method evidence, not measured behavioral scenario coverage. In particular, async success/failure/timeout/cancellation prompts are not claims that those tests are missing. No functionality percentage is reported. The view was generated from the previously validated Spring JSON, without rerunning or changing tests. Browser checks cover card selection, async method filtering, existing gray/unseen filters, and mobile layout.

### Recovery simulations

The Spring view includes a “What if” experiment selector. Four curated plans target Callable execution, deferred-result failure/lifecycle registration, named-view rendering, and Context restoration at async redispatch. Each provides source rationale, concrete test/investigation steps, assumptions, validation criteria, and downloadable JSON marked `HYPOTHETICAL_NOT_EXECUTED`.

Purple tiles are conditional targets; the measured method map and totals never change. Test plans project unknown methods becoming observed with Context state still unknown. The redispatch plan projects only its explicitly selected root-bearing methods becoming context-present, conditional on verifying an unintended loss and a successful restoration. It does not infer propagation defects, predict entry counts, or claim downstream gains. Counts follow the current scenario filter; rationale explicitly refers to the original Spring Boot recording. Categories and plans are curated, not automatic recommendations. The plans have not been executed.

In the recorded Spring Boot run, the candidate sets contain two Callable overloads, four DeferredResult methods, two view methods, and three async-result consumption methods respectively. Browser verification checks these sets, unchanged measured totals across all projections, capability filters, and mobile layout. Existing controller source already has a CompletableFuture `/forwarded` endpoint: isolate that scenario before proposing new async instrumentation.

### Proposal demo

[Annotated demo gallery](demo/index.html) contains four captures of the actual Spring Boot report. [Demo subsection](demo/DEMO.md) provides copy-ready captions and the value narrative for the proposal. Screenshots distinguish measured evidence from hypothetical recovery.
