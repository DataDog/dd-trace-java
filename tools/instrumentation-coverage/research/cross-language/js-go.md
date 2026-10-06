# Cross-tracer instrumentation coverage: Node.js and Go

Read-only local-source assessment, 2026-09-24. No tests/builds run; no tracer files changed. Paths relative to `/Users/andrea.marziali/go/src/github.com/DataDog/`. Feasibility assessment, not runtime validation or upstream-default-branch verification.

## Node.js

Snapshot: `4dfb204a0b389df3808810a6326c3c4461dfb4b6`, branch `andrea.marziali/dbm-dynamic-service`, working tree clean at inspection. Read root AGENTS.md.

- `dd-trace-js/packages/dd-trace/src/scope.js:9-32`: active() reads store.span; activate() installs span on the legacy async store and restores the old store in finally. `packages/datadog-core/src/storage.js:16-24,44-60`: AsyncLocalStorage-backed store with WeakMap indirection. Store presence and span presence are distinct; this store also carries non-tracing data.
- `packages/dd-trace/src/profiling/profilers/wall.js:146-153,230-278,282-316`: existing sampler attaches span/root-span and endpoint data depending on enabled configuration; context tracking accounts for async context frames. `:398-427`: non-JS thread samples, missing sample context, idle/unavailable contexts explicitly cannot all carry tracing attribution. Missing labels must not be classified as definite missing Context.
- `packages/dd-trace/test/plugins/agent.js:420-465` loads the real tracer, with tracer/module reload behavior to consider for observer installation. `packages/datadog-plugin-express/test/index.spec.js:48` loads Express/router plugins, `:957-989` tests task-queue scope leakage, `:1014-1032` exercises per-middleware scope restoration including deliberate null activation. `.github/workflows/apm-integrations.yml:70-84` shows existing plugin CI jobs. Actual test activity is reusable; this does not mean every plugin CI job already records a profile.

Feasible pilot: selected Express/router function-entry wrappers in the actual plugin test process, installed with explicit ordering around existing instrumentation, plus independent async-store/span state and original test assertions. Start with existing middleware/queue tests, recording process/worker/async identity and test-window provenance. Export a sampled profile with current correlation as optional broad evidence; do not promote it to exhaustive function coverage. Test observer passivity, callback identity, require-cache reloads, exception behavior, and restoration. Existing diagnostic channels give convenient boundary events but alone cannot inventory execution in uninstrumented library regions. V8/nyc code coverage alone does not include Context state.

## Go

Snapshot: `f6684c8bc30505f82bed6f6a2a6700c38acdb62c`, branch `andrea.marziali/dbm`, working tree clean at inspection. Read root/tracer/profiler/contrib/orchestrion AGENTS instructions. Orchestrion details below are specific to this local snapshot.

- `dd-trace-go/ddtrace/tracer/context.go:26-53,90-138`: ordinary ContextWithSpan puts a span into an explicitly passed context.Context; SpanFromContext examines that particular context. Arbitrary function execution has no universal Java-style ambient Context.current(). Context.Background, cancellation contexts, and a context carrying a span must not be conflated.
- `ddtrace/tracer/context.go:33-47,97-103` and `ddtrace/tracer/orchestrion.yml:143-242` describe build-time Orchestrion additions with goroutine-local fallback. Evidence must record whether this mode is enabled; do not assume those semantics for every Go tracer application or every upstream version.
- `ddtrace/tracer/tracer.go:1040-1075`: code-hotspot/endpoint options attach local-root/span/endpoint pprof labels to the current goroutine. `ddtrace/tracer/span.go:1102-1105` restores labels. These labels indicate profiling correlation; they do not prove what context.Context argument an arbitrary downstream function received.
- `contrib/net/http/http_test.go:37-45` illustrates tests using mocktracer. Such tests must not be presented as full production-tracer runtime validation. A stronger existing pilot is `internal/traceprof/traceproftest/app.go:108-134`, starting the actual tracer with hotspot/endpoint options and HTTP/gRPC instrumentations. `internal/traceprof/traceproftest/traceprof_test.go:29-35` explicitly validates pprof correlation using a test application; short test paths need sufficient sampling opportunity.
- `.github/workflows/unit-integration-tests.yml:58-98` runs integration matrices with Datadog agent and test-agent services; `:271-277` uploads coverage. Ordinary Go coverage is execution evidence for its instrumented package inventory, not context-at-entry evidence or automatic third-party coverage.

Feasible pilot: use the existing real-tracer HTTP/gRPC profile test harness for sampled attribution discovery, and explicit context-argument probes at HTTP handler, request.Context(), client call, and goroutine handoff boundaries for deterministic checks. Compilation/source transformation or Orchestrion can be investigated for wider selected-function probes; that is added work, not an already generic adapter. Record observed context argument and propagation identity. Methods without an observable context carrier should report unknown/not-applicable rather than absence. Cross-goroutine profiler labels and explicit context can disagree; test both deliberately.

## Decision

Both languages can feed a common capability portal and verified-scenario gates. Node.js has an ambient store seam; Go requires explicit carrier semantics and build-mode provenance. Reuse actual existing CI execution and assertions, augment it with collection. No claim that current exported profiles alone reconstruct generic Context coverage or exact method-entry counts.

Independent targeted source review confirmed the main claims. Additional Go caveat: under Orchestrion, SpanFromContext can consult GLS fallback; a carrier probe must distinguish explicit argument-carried state from fallback-resolved state rather than claim the argument contained the span.
