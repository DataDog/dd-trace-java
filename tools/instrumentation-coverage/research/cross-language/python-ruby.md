# Cross-tracer instrumentation coverage: Python and Ruby

Read-only local-source assessment, 2026-09-24. No tests/builds run and no tracer files changed. Paths below are relative to `/Users/andrea.marziali/go/src/github.com/DataDog/`. These are feasibility findings, not a validated implementation.

## Python — feasible, with a concrete Context distinction and runtime caveat

Inspected dd-trace-py commit `00c94e44824aa9aa302df962c7e1776f118c2038`; followed its AGENTS.md.

Evidence:
- `ddtrace/_trace/provider.py:16-19,61-79`: active storage is a ContextVar of `Span | Context | None`. A context-only state is real, so a span-only measurement would be wrong. `active()` may update stale finished-span state (`:81-97`); a supposedly passive observer must choose and document normalized-versus-raw semantics and avoid causing extra activations.
- `ddtrace/_trace/provider.py:37-38,66-69`: activation emits `ddtrace.context_provider.activate`, a useful existing observation seam. ContextVar inheritance/task switching still requires task-aware verification; activation callbacks alone should not be assumed to identify every logical task transition.
- `ddtrace/profiling/collector/stack.py:65-68` subscribes that event to profiler linking. `ddtrace/internal/datadog/profiling/stack/__init__.py:17-22` only links Span inputs; it does not encode generic Context state and does not explicitly clear non-Span inputs there. `.../stack/src/stack_renderer.cpp:54-57` emits span/local-root IDs into samples. Existing profiling correlation is a starting point, not proof of generic Context presence or absence.
- `ddtrace/internal/coverage/instrumentation_py3_12.py:93-120` already uses monitoring callbacks for coverage, but disables repeated callbacks after first observation. That is unsuitable unchanged for mixed context-state entry counts. `ddtrace/internal/coverage/code.py:43-58` explicitly documents Python 3.14 monitoring callbacks seeing snapshot ContextVars, and uses a thread-local workaround for code coverage. That workaround is NOT automatically safe for async task Context inspection.
- `tests/contrib/flask/test_flask_snapshot.py:30-33,48-64` launches the actual instrumented Flask application with ddtrace-run in a subprocess. Collection must run inside that server, not only in the pytest parent. `.gitlab/tests.yml:16-46` supplies existing Riot CI test runs, and `docs/contributing-testing.rst:39-54` gives supported run-tests entrypoints.

Proposed smallest pilot: one existing Flask request/snapshot suite on a pinned Python version, selected Flask/Werkzeug application functions, CI-only wrappers or a validated runtime callback capturing native Context state at function execution, PID/thread/task identity, bounded stacks, scenario identity and collector health. Inject through server startup for subprocess tests. Begin with deterministic callbacks; compare baseline test outcomes and existing snapshots. Explicitly validate callback ordering relative to monkey patches. Exercise context-only input, parent-span input, no-parent request, error, and async/task boundaries before calling it generic.

Do not claim full library coverage from loaded functions only. Inventory selected dependency version separately; report native/C code unsupported or unknown. Profiling can prioritize sampled activity but short test paths may not be sampled.

## Ruby — feasible; Context container presence is the wrong predicate

Inspected dd-trace-rb commit `79ce50f37e3ac3cf420a35dcd1e7f56cbd17bb84`. No repository AGENTS.md found in initial rg discovery.

Evidence:
- `lib/datadog/tracing/context_provider.rb:8-12,49-66` uses fiber-local storage via Thread attributes and lazily creates a Context. Therefore “Context object exists” is effectively always true after reading it. The adapter must distinguish an empty context container from active trace state; retain this native semantic distinction rather than pretend it is Java's root/non-root model.
- `lib/datadog/tracing/context.rb:21,40-48,62-64` holds active_trace, restores the prior trace in ensure, and rejects finished traces. `lib/datadog/tracing/tracer.rb:213-226` exposes active_trace separately from active_span.
- `ext/datadog_profiling_native_extension/collectors_thread_context.c:1373-1408` reads the sampled thread's context, active trace, root span and active span; missing container, trace, or necessary IDs all result in unavailable correlation. `:942-944` adds local-root/span labels. Thus existing unattributed samples cannot distinguish all Context states. This is particularly strong source evidence that a profiling adapter could be extended, but native sampling safety constraints matter.
- `lib/datadog/di/instrumenter.rb:379-413` uses targeted TracePoint callbacks and explicitly warns about the performance penalty of untargeted hooks. Existing runtime observation mechanisms make a CI collector plausible; this is not a claim that DI already supplies our evidence format.
- `spec/datadog/tracing/contrib/rack/integration_test_spec.rb:58-67,93-100` enables real Rack instrumentation and builds a Rack application; it also mocks external HTTP/agent transport, which should be retained as provenance. `.github/workflows/test.yml:154-159,192-194` already gathers and uploads JUnit test artifacts.

Proposed smallest pilot: CI-only RSpec around-example collector for selected Rack methods, using targeted callbacks/wrappers; record native context-container state, active trace and span independently, fiber/thread identity, test ID, calls and representative stacks. Use original instrumented tests, preserving agent mocks. Include successful request, exception, distributed context, and explicit no-parent/suppression scenarios. Add fiber switching as a required collector control; do not assume thread ID identifies an execution context. Keep native extension internals outside the exact Ruby-method denominator.

## Common decision

Feasible in both, with language-specific adapters and a shared evidence/presentation schema; not feasible as an unchanged Java observer or by reading existing trace snapshots alone. Existing test assertions primarily establish expected outputs, not execution outside attribution. Additional collection during the same CI run is required.

Keep separate: native context state; active trace/span; profiler correlation availability; verified lineage/restoration; executed versus sampled; scenario/provenance; eligible inventory; collector loss/errors. Functionality maps can group framework entry points and behavioral scenarios for the portal. Tests that use mocks and tests that run instrumented subprocess applications must be distinguishable.

Quality gates proposed (not existing): no collector errors/loss for gated scenarios, unchanged original test assertions, no newly violated expected context/restoration in selected verified scenarios, and preservation of scenario execution. Do not gate on zero unattributed execution, 100% methods, or unsampled paths. A context may legitimately be empty before request entry, after completion, or during suppressed/background work. Presence alone never verifies lineage.

Snapshot provenance: Python branch `emmett.butler/sqlite3-snapshot-2` is dirty: docker-compose.yml, scripts/ddtest, scripts/run-tests, two Avro schema fixtures modified; tests/contrib/django_celery/app/results.sqlite staged added. Ruby branch `andrea.marziali/base_service` has clean git status. Findings concern these local source snapshots, not independently verified upstream default branches. No cross-language runtime pilot was run.
