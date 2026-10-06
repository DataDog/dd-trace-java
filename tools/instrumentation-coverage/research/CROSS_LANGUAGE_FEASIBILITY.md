# CI evidence for instrumentation quality: cross-language feasibility

2026-09-24. Source assessment of six other Datadog tracers by two parallel research agents and the primary agent, plus an independent targeted review of the Node.js/Go conclusions. No non-Java collector was built or run. Existing Java prototype results remain the only runtime validation of this particular design. No tracer source modified, committed, pushed, or published.

## Decision

The architecture is feasible across Python, Ruby, Node.js, .NET, PHP, and Go, with language-specific collectors feeding shared evidence, analysis, and visualization. This is source-grounded implementation feasibility, not demonstrated cross-language correctness, overhead, or a ready-made feature in those repositories.

Existing CI workloads are reusable. Existing exported traces, test results, and ordinary code-coverage artifacts alone cannot reconstruct all library execution without Context. Some profilers already collect correlated execution stacks, but jobs may not enable/export them. Exact Context evidence requires additional observation in the actual instrumented application process.

Do not promise one universal `Context.current() != root` predicate. Preserve native semantics and distinguish these questions:
1. Which selected library behavior executed or was sampled?
2. Which native context/carrier state was visible at that execution point?
3. Was profiling attribution available?
4. Did expected propagation, lineage, activation, and restoration hold?
5. Did the expected instrumentation behavior and trace output occur?

Ambient context can cover a whole request without proving that every framework feature is instrumented correctly. Context reach is one dimension of instrumentation quality, not its complete definition.

## Tracer findings

| Tracer | Source-confirmed foundation | Key adaptation | Candidate pilot |
| --- | --- | --- | --- |
| Python | ContextVar state, activation events, profile span correlation, instrumented subprocess tests | Distinguish Span / Context / None; account for callbacks seeing snapshot ContextVars on Python 3.14 and for state-normalizing accessor side effects | Existing Flask snapshot application, pinned runtime, selected passive entry probes |
| Ruby | Fiber-local Context/trace state, native sampling correlation, targeted TracePoint mechanisms, Rack tests | Context container can exist while empty; distinguish trace/span; preserve fiber identity | Existing Rack request/error tests with selected observational hooks |
| Node.js | AsyncLocalStorage-backed stores, middleware scope tests, wall-profile context correlation | Store presence is not active span; missing sample metadata can mean unavailable or idle; observe correct async context | Existing Express middleware/queue tests with selected probes and optional profiles |
| .NET | AsyncLocal scope restoration, native profiling context tracker, CallTarget, instrumented sample tests | Active scope versus generic context/interop; profiler-only harness disables tracing by default | Stable ASP.NET Core sample with tracing verified and local profile export |
| PHP | Function hooks, non-mutating native profile-context reader, request/framework tests, local pprof export | Public active_span() can create a root span; use passive read; request-wide roots do not prove comprehensive instrumentation | Existing Symfony scenarios with selected hooks/profile capture |
| Go | Explicit context carriers, goroutine pprof labels, real-tracer HTTP/gRPC profiling tests | No universal ambient context in ordinary builds; separate argument state from profiler labels and optional Orchestrion fallback | Existing real-tracer HTTP/gRPC profile harness plus explicit boundary probes |

Detailed source paths, line numbers, commits, branches, limitations, and proposed validation:
- [Python and Ruby](cross-language/python-ruby.md)
- [.NET and PHP](cross-language/dotnet-php.md)
- [Node.js and Go](cross-language/js-go.md)

These are local checkout snapshots, several on feature branches. Python also has unrelated working-tree modifications documented in its report. Upstream default branches and deployed tracer versions were not independently verified. Orchestrion behavior in particular must be treated as snapshot/build-mode-specific.

## What is reusable versus new work

Reusable: existing instrumented application workloads, framework/version test matrices, existing span assertions and snapshots, profiler stack/correlation capabilities where enabled, CI artifact publication patterns.

New: test-only observation/configuration, actual application-process collection, native context semantics adapters, independent scenario attribution, explicit selected inventory, collector-health evidence, capability maps, shared report format, and evaluated gates. Sampling-only evidence can prioritize work but cannot establish exact method execution or scope-pairing integrity. Finished spans alone cannot reconstruct the complete activation/restore history.

Proposed shared fields:
- provenance: tracer revision, library/runtime versions, build/instrumentation mode, production tracer versus mock tracer, test/scenario/process and collection window;
- execution: method/frame/boundary identity, observation source (entry, sample, lifecycle), count/weight and unit;
- native context: semantic type and state, unknown/not-applicable support, logical task/fiber/goroutine identity where available;
- correlation: profile IDs present/absent/unavailable, recorded independently of native Context;
- expectations: required parent/lineage/restoration, intentional empty/suppressed state, pass/fail/unverified;
- inventory/health: selected scope, capability-map version, exclusions, sample settings, errors, dropped evidence.

Test identity must not depend solely on the same context propagation being evaluated: if propagation fails, losing the test label must not erase the finding. Use isolated request/test windows or an independent correlation mechanism with its own declared guarantees.

## CI, toolkit, portal

CI executes tests and produces evidence artifacts. The instrumentation toolkit consumes evidence to improve instrumentation and tests, and defines quality gates evaluated in CI. The portal visualizes library functionality, evidence gaps, context reach, expected-behavior results, and change over time.

Initial gates should be scoped to deterministic, reviewed scenarios: collector and real tracer enabled; original assertions preserved; known entry points still observed; expected Context/lineage/restoration not regressed; intentional empty-context controls preserved. Sample disappearance is not a deterministic regression. Do not gate on zero root/unattributed execution or a single cross-language method percentage.

Recovery simulations remain hypotheses until a matched real before/after run verifies them. A new test improves knowledge; an instrumentation fix changes behavior. Show these as different outcomes.

## Story the evidence supports

“We started by looking at existing test execution as evidence of instrumentation correctness: are context APIs used correctly, and do scopes and spans retain their expected integrity? That leads to a larger opportunity. Every CI run already exercises real libraries, requests, failures, and async boundaries. We use much of that activity to answer whether assertions pass, but we can extract more from it: which functionality ran, where Context reached, where it was absent or attribution unavailable, and which behaviors remain unknown.

“By collecting additional evidence during those same instrumented tests, we can build a functionality-oriented view of instrumentation quality. CI produces the evidence; the toolkit turns it into targeted tests, improvements, and reviewed quality gates; the portal makes coverage and regressions understandable. Profiling offers broad discovery, while targeted probes establish precise behavior. The design can span tracers without pretending their context models are identical.”

The opening describes the user's intended starting point; this assessment does not independently prove a prior completed cross-language scope/API integrity project. Avoid saying test data is wholly unused: the repositories already contain snapshot checks, context assertions, code coverage, and profiling tests. The opportunity is to connect and extend that evidence into instrumentation quality feedback, including negative space it does not currently describe.

## Suggested next validation

Use Java's existing prototype as one reference, then a small Node.js/Express or Python/Flask pilot with native semantics explicit. Add Go early as a contrasting explicit-context model to test whether the shared schema is actually portable. Reuse deterministic existing behaviors: success, error, async continuation, deliberate context-free work, and restoration/request isolation. Compare original assertions and observer passivity before claiming a gate. No additional implementation or runtime experiment has been performed by this assessment.
