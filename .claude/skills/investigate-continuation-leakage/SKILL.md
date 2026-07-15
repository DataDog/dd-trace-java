---
name: investigate-continuation-leakage
description: >
  Investigate scope-continuation leaks in an instrumentation. Use when asked to "investigate
  continuation leakage", "find scope leaks", "why does this integration leak continuations",
  "debug a leaked trace / pendingReferenceCount", or when a test needed strictTraceWrites(false)
  to pass. Runs the chosen instrumentation test with the scope-continuation diagnostic enabled,
  reads the logged full timeline, recaps the findings, and renders a Gantt or DAG (works whether
  or not anything leaked).
user-invocable: true
context: fork
allowed-tools:
  - Bash
  - Read
  - Edit
  - Glob
  - Grep
  - AskUserQuestion
---

# Investigate scope-continuation leakage

dd-trace-java moves trace scopes across threads via *continuations*: a scope is **captured** on
one thread (`ScopeContinuation`, bumping `PendingTrace.pendingReferenceCount`) and later
**activated** and/or **cancelled** on another. A continuation that is never resolved (the classic
leak), resolved twice, resolved after its root span was written, or activated after resolve, keeps
a trace alive or drops a late span — and in tests forces `strictTraceWrites(false)`, masking the
bug instead of locating it.

The test-time diagnostic in `datadog.trace.agent.test.scopediag` records the lifecycle and logs a
full timeline of every continuation and scope (regardless of whether anything leaked). This skill
drives that diagnostic, reads the logged timeline, recaps it in plain language, and renders a
diagram. **The Java code no longer renders Gantt/Mermaid — you (the LLM) produce the diagram from
the timeline.**

Background: `docs/superpowers/specs/2026-06-10-scope-continuation-leak-diagnostic-design.md`.
Test-run conventions: `docs/how_to_test.md`.

## Step 1 — Select the target

Identify the suspect instrumentation. If the user named it, resolve the module directory under
`dd-java-agent/instrumentation/<framework>/<framework>-<minVersion>` with Glob. If ambiguous, list
the candidate test classes (Glob `**/src/test/**/*Test.{java,groovy}` in the module) and ask the
user which test to run with `AskUserQuestion`. You want one concrete test class (and ideally one
method) plus its Gradle module path, e.g. `:dd-java-agent:instrumentation:google-pubsub-1.116`.

## Step 2 — Enable tracking (temporary)

With `Edit`, add the opt-in annotation to the chosen test class (or a single method):

- Add import `datadog.trace.agent.test.scopediag.TrackScopeContinuations`.
- Annotate the class/method with `@TrackScopeContinuations`. Leave the default `failOnLeak=false` —
  you want the report, not a failing test (a red test would still print the report, but the default
  keeps the run green so the build doesn't stop early).

This works for both JUnit 5 Java tests (extension is auto-registered on
`AbstractInstrumentationTest`) and Groovy `InstrumentationSpecification` subclasses. **Record the
exact file path** — you will revert it in Step 7.

## Step 3 — Run the test, capturing the diagnostic output

The diagnostic does **not** write a file; at the end of every tracked test it logs the **full
timeline** (`ScopeDiagnosticsReport.renderTimeline()`) — every continuation and scope with its
events, threads, relative timing, and callsites, regardless of whether anything leaked. Run with
output captured:

```bash
./gradlew :dd-java-agent:instrumentation:<framework>-<minVersion>:test --tests '<FQCN-or-pattern>' --info 2>&1 | tee /tmp/scopediag-run.txt
```

(For the diagnostic harness's own tests the module is `:dd-java-agent:instrumentation-testing`.)
If the SLF4J line is not visible in console output, read the per-test captured stdout under
`<module>/build/test-results/**/*.xml` (the `<system-out>` element) or the HTML report under
`<module>/build/reports/tests/`.

## Step 4 — Collect the diagnostic output

Grep the captured output for `Scope/continuation timeline` — one block per test. Shape:

```
Scope/continuation timeline (N continuations, M scopes; X leaked, Y late, Z double,
    W activate-after-resolve | scopes: P never-closed, Q wrong-thread)

#<seq> <STATUS> trace=<id> span=<id> "<spanName>" src=<INSTRUMENTATION|MANUAL|ITERATION|CONTEXT> [ORPHAN] [handoff] {failures}  cap->resume=<ms>  age=<ms>
  capture   +<Δms>  @ <thread>  at <Class.method(File.java:line)>
  resume    +<Δms>  @ <thread>  at <...>
  finish    +<Δms>  @ <thread>  at <...>          (or cancel / DOUBLE / act-fail)
  scope#<seq> <src> "<spanName>"  open +<Δms> @ <thread>  close +<Δms> @ <thread> (active <ms>) [handoff] {failures}
  LEAKED   (never finished or cancelled)          (only when unresolved)
...
Non-continuation scopes:
  scope#<seq> ...
```

Every record is listed (not just flagged ones), so you can reconstruct the full graph whether or not
anything leaked. `+Δms` is relative to the first recorded event. (`renderSummary()` — the
problem-only view — still backs `assertNoLeaks` failure messages, but the timeline is the feed.)

## Step 5 — Summarize ("resume")

Give a plain-language recap:

- The header counts (leaked / late / double / activate-after-resolve / never-closed / wrong-thread).
- The dominant flow: where continuations are captured (callsite/thread) and where they're resumed /
  resolved (thread), plus any thread handoffs.
- For each flagged record (if any): its failure set and capture/open callsite (cite `file:line`).
- A one-line hypothesis when there's a problem: which advice captured the continuation and where it
  should have resolved it.

## Step 6 — Visualize (auto-pick, user may override)

Build the diagram from the **timeline** (works whether or not there are leaks):

- **Gantt** — when the signal is **temporal / cross-thread** (thread handoffs, late-after-root,
  never-closed, or the user wants the time view). Mermaid `gantt`, one `section` per thread; a bar
  per continuation from capture→resolve and per scope from open→close using the `+Δms` offsets. Mark
  leaks / never-closed `crit` to the window end; late / wrong-thread `active`; resolved-on-time
  `done`; capture-only points as `milestone`.
- **DAG** — when the signal is **structural / ownership** (orphans, double-finish,
  activate-after-resolve, or continuation→scope lineage). Mermaid `flowchart LR`: a node per
  continuation (`#seq spanName`), its spawned scopes (linked via the nested `scope#` lines), edges
  capture→resume→resolve labelled with thread + `+Δms`. Color leaked / double red, late amber,
  resolved green.

If there are no problems, the diagram simply shows the healthy capture→continue→resolve flow (all
green) — that is the expected "regardless of leak" output. If unsure which shape, ask with
`AskUserQuestion`.

## Step 7 — Revert

Undo the temporary annotation so the working tree is clean:

```bash
git checkout -- <test file path from Step 2>
```

Report: the findings summary, the diagram, and that the annotation was reverted.
