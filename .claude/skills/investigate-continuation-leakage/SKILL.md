---
name: investigate-continuation-leakage
description: >
  Investigate scope-continuation leaks in an instrumentation. Use when asked to "investigate
  continuation leakage", "find scope leaks", "why does this integration leak continuations",
  "debug a leaked trace / pendingReferenceCount", or when a test needed strictTraceWrites(false)
  to pass. Runs the chosen instrumentation test with the scope-continuation diagnostic enabled,
  reads the logged problem summary, recaps the findings, and renders a DAG of the leaks.
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
problem summary. This skill drives that diagnostic, reads the logged summary, recaps it in plain
language, and renders a diagram. **The Java code no longer renders Gantt/Mermaid — you (the LLM)
produce the diagram from the recorded data.**

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

The diagnostic does **not** write a file; it logs a problem summary at the end of each test. Run
with output captured so you can read that summary:

```bash
./gradlew :dd-java-agent:instrumentation:<framework>-<minVersion>:test --tests '<FQCN-or-pattern>' --info 2>&1 | tee /tmp/scopediag-run.txt
```

(For the diagnostic harness's own tests the module is `:dd-java-agent:instrumentation-testing`.)
If the SLF4J line is not visible in console output, read the per-test captured stdout under
`<module>/build/test-results/**/*.xml` (the `<system-out>` element) or the HTML report under
`<module>/build/reports/tests/`.

## Step 4 — Collect the diagnostic output

Find the summary block emitted by `ScopeDiagnosticsReport.renderSummary()` (Grep the captured output
for `Scope/continuation problems`). Its shape:

```
Scope/continuation problems (N continuations, M scopes; X leaked, Y late, Z double,
    W activate-after-resolve | scopes: P never-closed, Q wrong-thread)
  [LEAKED] #<seq> trace=<id> src=<INSTRUMENTATION|MANUAL|ITERATION|CONTEXT> captured at <Class.method(File.java:line)>
  [NEVER_CLOSED] scope#<seq> trace=<id> src=<...> opened at <Class.method(File.java:line)>
  ...
```

> **Note (current limitation):** the machine-readable JSON feed was removed, so only the
> **problem-only** summary is available — flagged continuations/scopes with their failure set and
> capture/open callsite. The full per-event timeline (every resume/resolve, threads, per-event
> timestamps) is **not** in this output, so a time-accurate Gantt cannot be reconstructed. If a
> Gantt or richer analysis is needed, a structured data feed must be re-enabled in
> `ScopeDiagnosticsReport` first.

## Step 5 — Summarize ("resume")

Give a plain-language recap:

- The header counts (leaked / late / double / activate-after-resolve / never-closed / wrong-thread).
- For each flagged continuation/scope: its failure set, the **capture/open callsite** (cite it as
  `file:line`), and the `source`.
- A one-line hypothesis: which instrumentation advice captured the continuation and where it should
  have resolved it.

## Step 6 — Visualize

Render a **DAG** (the summary lacks the per-event timing a Gantt needs). Mermaid `flowchart LR`:
a node per flagged continuation (`#seq spanName` + failure) and per flagged scope, an edge from each
to its trace/root, and from a scope to its owning continuation when that link is evident. Color
`LEAKED` / `DOUBLE_FINISH` / `NEVER_CLOSED` nodes red, `LATE_FINISH` / `CLOSE_WRONG_THREAD` amber.
Annotate nodes with the callsite. If the user explicitly wants a Gantt, explain the timeline data is
not currently emitted (see the Step 4 note) and offer to re-enable the feed.

## Step 7 — Revert

Undo the temporary annotation so the working tree is clean:

```bash
git checkout -- <test file path from Step 2>
```

Report: the findings summary, the diagram, and that the annotation was reverted.
