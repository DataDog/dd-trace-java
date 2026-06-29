# Cross-thread scope-continuation leak diagnostic — design

**Date:** 2026-06-10
**Status:** Approved (design); implementation pending
**Scope:** Test-time only

## Problem

dd-trace-java transfers trace scopes between threads via *continuations*: a scope is
**captured** on one thread (`ScopeContinuation`, registered with the trace collector,
bumping `PendingTrace.pendingReferenceCount`) and later **activated** and/or **cancelled**
on another thread/executor. When a captured continuation is never resolved (neither
activated nor cancelled), or is resolved after the root span has already finished, the
`PendingTraceBuffer` either keeps the trace alive past the root close or discards a span
that arrives too late. In tests this surfaces as the need to disable `strictTraceWrites`,
masking the underlying leak instead of locating it.

There is no way today to see **where** a continuation was captured, on **which thread** it
was activated/cancelled, and **whether** it leaked. The existing `HealthMetrics`
callbacks (`onCaptureContinuation` / `onFinishContinuation` / `onCancelContinuation`) are
**no-arg** — they carry no continuation identity and no trace id, so capture→activate→cancel
cannot be correlated and "late after root close" cannot be computed.

## Goal

A passive, test-time diagnostic that records the lifecycle of every scope continuation with
correlated identity, captures the capture/activate/resolve **callsites** (stack traces),
and renders a Gantt-style timeline plus a leak summary, so a developer can locate the
instrumentation that leaks continuations across threads.

### Findings the diagnostic must derive
1. **Never-resolved captures** — captured but never activated *and* never cancelled within
   the recording window. The classic leak that keeps `pendingReferenceCount > 0` and forces
   `strictTraceWrites(false)`.
2. **Cross-thread timeline** — full Gantt of every capture→activate/resolve: capture
   thread + callsite, activation thread + callsite, latency, and whether resolution happened
   on a different thread/executor (even for non-leaking continuations).
3. **Late activation after root close** — captures that *are* resolved, but activated/closed
   after the root span of their trace already finished (`rootSpanWritten`).
4. **Double / invalid resolution** — a continuation activated after cancel, resolved more
   than once, or otherwise mishandled in the capture/activate handshake.

## Non-goals
- Production runtime diagnostic (this is test-time only; production stays inert).
- HTML/visual timeline rendering (text + JSON only).
- Failing tests by default — passive/report-only unless explicitly opted in.
- Parallel in-JVM test execution support (instrumentation tests run one-at-a-time per JVM).

## Architecture

Four layers: a minimal production **seam** in `dd-trace-core`, a test-side **recorder
engine**, **renderers**, and **harness integration** (JUnit5 + Spock + static API).

### 1. Production seam — `dd-trace-core` (`datadog.trace.core.scopemanager`)

A new `ContinuationDiagnostics` holder, inert in production (listener is `null` unless test
code installs one):

```java
public final class ContinuationDiagnostics {
  public interface Listener {
    void onCapture(AgentScope.Continuation id, DDTraceId traceId, long spanId, byte source);
    void onActivate(AgentScope.Continuation id, DDTraceId traceId, long spanId, byte source);
    void onResolve(AgentScope.Continuation id, boolean cancelled); // finish vs cancel
    void onRootWritten(DDTraceId traceId);
  }
  private static volatile Listener LISTENER;            // null in prod
  public static void install(Listener l) { LISTENER = l; }
  public static void clear() { LISTENER = null; }
  static Listener listener() { return LISTENER; }       // single volatile read
}
```

**Identity** is the `AgentScope.Continuation` instance itself (`ScopeContinuation implements
AgentScope.Continuation`), used only as an identity key downstream (never `equals`/`hashCode`).
`AgentScope.Continuation` lives in `internal-api`, so it is a safe public identity type.

**Call sites** — each guarded by `Listener l = listener(); if (l != null) { ... }`:

| Location | Method | Notify |
|---|---|---|
| `ScopeContinuation.register()` | after `traceCollector.registerContinuation(this)` | `onCapture(this, traceId, spanId, source)` |
| `ScopeContinuation.activate()` | successful branch (`COUNT.incrementAndGet(this) > 0`) | `onActivate(this, traceId, spanId, source)` |
| `ScopeContinuation.cancel()` | mirror `onFinishContinuation` (count==0) → `onResolve(this,false)`; mirror `onCancelContinuation` → `onResolve(this,true)` |
| `ScopeContinuation.cancelFromContinuedScopeClose()` | where it calls `onFinishContinuation` | `onResolve(this, false)` |
| `PendingTrace.write()` | non-partial path where `rootSpanWritten = true` | `onRootWritten(traceId)` |

`traceId`/`spanId` at capture/activate are obtained from the available `context` via
`spanFromContext(context)` (already imported in that package). `source` is the existing
`byte` source field (`INSTRUMENTATION`/`MANUAL`/`ITERATION`/`CONTEXT`).

**The seam carries only identity + ids + source.** Timestamp, thread, and stack trace are
captured by the recorder *inside* the callback — callbacks run synchronously on the event's
own thread, so `Thread.currentThread()` and `new Throwable().getStackTrace()` are accurate
for the capturing/activating/resolving thread. This keeps the production footprint to a
handful of null-guarded calls with zero behavior change and zero allocation when off.

### 2. Recorder engine — `:dd-java-agent:instrumentation-testing` (`datadog.trace.agent.test.scopediag`)

`ScopeDiagnostics` — static facade and the `Listener` implementation that installs itself:

- API: `startRecording()`, `reset()`, `stop()`, `report() -> ScopeDiagnosticsReport`,
  `assertNoLeaks()`.
- Correlation store: `Collections.synchronizedMap(new IdentityHashMap<Continuation,
  ContinuationRecord>())` (identity-keyed; never calls `equals`/`hashCode` on the continuation).
- `ContinuationRecord`: monotonic seq id, `traceId`, `spanId`, `source`, the capture
  `Event`, a list of activation `Event`s, and the resolution `Event`(s).
- `Event`: `{ threadName, nanos (System.nanoTime), filtered StackTraceElement[] }`.
- `Map<DDTraceId, Long> rootWrittenNanos` for late-after-root detection.
- A global, time-ordered event list backing the timeline.
- **Stack filter**: drops frames in `datadog.trace.core.scopemanager`, the diagnostics
  package, and `java.util.concurrent` executor internals; keeps the top N meaningful frames
  (default 5, configurable). Goal: surface the integration advice + app callsite.

**Derived findings** computed at `report()` time from the records + `rootWrittenNanos`:
never-resolved, late-after-root, double/invalid, and the full cross-thread timeline.

### 3. Renderers

> **Addendum (2026-06):** The in-Java Gantt renderers (text `renderGantt()` and Mermaid
> `toMermaidGantt()`) were removed, and subsequently the **JSON output was dropped too** (its only
> consumer was an LLM, which reads structured text fine, and the hand-rolled serializer was a
> liability). Java now emits **only the leak-only text summary** (`renderSummary()`), logged at the
> end of each tracked test. The `investigate-continuation-leakage` skill reads that summary and
> renders a DAG of the flagged leaks. Note: the summary is problem-only and carries no per-event
> timeline, so a time-accurate Gantt requires re-enabling a structured feed. The
> `@TrackScopeContinuations` attributes were reduced to just `failOnLeak` (default `false`);
> `gantt()`/`mermaid()`/`json()` were all removed.

- **Leak-only summary**: only the flagged problems, each with its callsite(s) — a quick scan
  / failure message, and the sole output consumed by the skill.

### 4. Harness integration (passive by default — no impact on existing tests)

- **Static API** (works anywhere, incl. Groovy and non-JUnit code):
  `ScopeDiagnostics.startRecording()` / `report()` / `assertNoLeaks()`.
- **JUnit5**: `ScopeDiagnosticsExtension` (`BeforeEachCallback`, `AfterEachCallback`)
  registered on `AbstractInstrumentationTest` via `@ExtendWith`, but **dormant** unless the
  test class or method carries `@TrackScopeContinuations`. When enabled: `reset()` +
  `startRecording()` beforeEach; afterEach renders the text Gantt to the log, writes the JSON
  file, and calls `assertNoLeaks()` only if `@TrackScopeContinuations(failOnLeak = true)`.
- **Spock**: `InstrumentationSpecification.setup()/cleanup()` honor the same
  `@TrackScopeContinuations` annotation (and/or an overridable `trackScopeContinuations()`
  returning `false` by default), reusing the same static `ScopeDiagnostics` engine.

`@TrackScopeContinuations` attributes: `failOnLeak` (default `false`). (The `gantt`/`mermaid`/`json`
attributes were removed — see the Renderers addendum.)

## Data flow

```
capture thread:   captureSpan() -> new ScopeContinuation -> register()
                    -> Listener.onCapture(this, traceId, spanId, source)
                    -> recorder: create ContinuationRecord{seq, ids, source, captureEvent(thread, t, stack)}

worker thread:    continuation.activate() (COUNT>0)
                    -> Listener.onActivate(this, ...) -> recorder: append activation Event

worker thread:    continued scope close / cancel
                    -> Listener.onResolve(this, cancelled) -> recorder: append resolution Event

any thread:       PendingTrace.write() (rootSpanWritten=true)
                    -> Listener.onRootWritten(traceId) -> recorder: rootWrittenNanos[traceId] = now

report():         walk records -> classify (never-resolved / late-after-root / double) -> render
```

## Error handling
- Recorder callbacks must never throw into tracer code: each `Listener` method body is
  wrapped so any diagnostic failure is swallowed/logged, never propagated into
  `ScopeContinuation`/`PendingTrace`.
- An `onActivate`/`onResolve` for a continuation with no recorded capture (e.g. captured
  before `startRecording()` in the previous test) is recorded as an "orphan" entry rather
  than dropped — itself a useful cross-test leak signal.

## Testing strategy
- Unit tests (JUnit5) for the recorder/classifier: feed synthetic `Listener` event sequences
  (capture-only; capture+activate+resolve; capture+late-resolve vs `onRootWritten`;
  activate-after-cancel) and assert the derived findings and rendered summary.
- Unit tests for the stack filter (frames in/out).
- Integration test: a small instrumentation test annotated `@TrackScopeContinuations` that
  deliberately captures a continuation and never resolves it (e.g. submit to an executor that
  drops the task), asserting `report()` flags exactly one never-resolved leak with the
  expected capture callsite. Mirror in one Spock spec to verify the Groovy hook.
- Verify production inertness: with no listener installed, the guarded call sites are
  exercised by existing tracer-core tests with no behavior change (no new failures).

## Cost & caveats
- **When off:** one volatile read per scope lifecycle event, no allocation, no behavior change.
- **When on:** a stack-trace capture per event (test-time, acceptable).
- Assumes **one test at a time per JVM** (holds for instrumentation tests). `reset()` isolates
  tests; events from a previous test's leaked continuation arriving after `reset()` are
  attributed to the new test as orphans — a useful, not harmful, signal.

## Module placement summary
| Piece | Module |
|---|---|
| `ContinuationDiagnostics` + seam call sites | `dd-trace-core` (`datadog.trace.core.scopemanager`, + `PendingTrace`) |
| `ScopeDiagnostics` engine, records, classifier, renderers | `:dd-java-agent:instrumentation-testing` (`datadog.trace.agent.test.scopediag`) |
| `ScopeDiagnosticsExtension`, `@TrackScopeContinuations` | `:dd-java-agent:instrumentation-testing` |
| `AbstractInstrumentationTest` / `InstrumentationSpecification` wiring | `:dd-java-agent:instrumentation-testing` |
