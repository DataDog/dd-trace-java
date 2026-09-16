---
name: fix-continuation-leakage
description: >
  Diagnose and fix scope or continuation lifecycle failures in dd-trace-java instrumentation
  tests. Use when a test reports a continuation leak, double resolution, activation after resolve,
  or an unclosed scope. Reads the automatic diagnostic timeline, finds the broken lifecycle edge,
  fixes it, and explains it with a compact Mermaid diagram.
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

# Fix continuation leakage

Instrumentation tests run the diagnostic automatically. A failure includes the capture, resume,
resolution, scope, thread, timing, and callsite data needed to find the missing lifecycle edge.

## Work the failure

1. Run the smallest failing test with full output:

```bash
./gradlew :dd-java-agent:instrumentation:<framework>-<minVersion>:test --tests '<FQCN-or-pattern>' --info 2>&1 | tee /tmp/scopediag-run.txt
```

2. Find `Scope/continuation timeline` in the output. If Gradle hides it, inspect the test XML's
   `<system-out>` under the module's `build/test-results` directory.
3. Follow the failing record from its first event:
   - `LEAKED` / `NEVER_CLOSED`: find the success, error, cancellation, and rejection exits that
     skipped `release()` or `close()`.
   - `DOUBLE_FINISH`: find two owners of the same cleanup.
   - `ACTIVATE_AFTER_RESOLVE`: find work scheduled after ownership ended.
   - `LATE_FINISH` / `CLOSE_WRONG_THREAD`: advisory evidence; verify whether ordering is valid.
   - `[deferred-cleanup]`: a root iteration scope transferred cleanup to the bounded iteration
     cleaner. It may remain open at the test boundary and is not a leak. Do not generalize this to
     other `ITERATION` scopes; an unregistered iteration scope must still close normally.
4. Classify the captured work before changing code:
   - For a real asynchronous operation, repair success, failure, cancellation, and rejection
     cleanup.
   - If the test started the work, wait for its terminal event and dispose or close it before the
     test ends.
   - If a framework initializer creates a permanent sentinel with no context consumer, disable
     propagation only around that creation boundary. Match the exact type and method, and update
     `knownMatchingTypes()` when shortcut matching is used.
   - If an executor replaces a task before delegating, avoid capturing the discarded task while
     preserving capture for the task actually submitted.
   - For intentionally delayed work, wait for its documented terminal event rather than
     suppressing propagation.
   - For a context swap, verify both restoration and resource cleanup. Restore or close the
     returned ownership object in `finally`; do not ignore every swap.
5. Prefer a test-lifecycle fix when production behavior is correct. Otherwise fix ownership where
   it breaks, with one owner and `try/finally` cleanup across every exit.
6. Rerun the failing test, then its module. Validate the leaked record and root-trace publication
   separately from trace-count or arrival-order assertions; fixing a leak may expose an unrelated
   flaky assertion.

## Fixture setup failures

Automatic recording may start after `setupSpec()` or equivalent fixture initialization. For an
initialization error or a trace wait inside setup, temporarily record around that setup block and
remove the diagnostic scaffolding after finding the owner.

Apply process-wide configuration before starting servers, actor systems, executors, or other
long-lived fixtures. Use a forked test or recreate the fixture when its static state cannot be
reset safely.

## Do not hide evidence

Instrumentation tests always use strict trace writes; there is no harness opt-out. Do not replace
the harness tracer or add a new escape hatch to weaken that invariant.

Fix the continuation lifecycle when possible. If it cannot be fixed in the current change,
quarantine the test with `@Flaky` and a useful reason or tracked issue instead of weakening strict
trace writes. Keep continuation tracking enabled so the failure remains diagnosable.

Use `@TrackScopeContinuations(enabled = false, reason = "...")` only when the diagnostic itself is
incompatible with the test, not when it has found a real unresolved leak. Scope the opt-out as
narrowly as possible. The reason must describe the incompatibility and when the opt-out can be
removed; strict trace writes remain enabled.

## Explain it to a human

Lead with one sentence: what was captured, which cleanup edge was missing, and where. Cite the
timeline callsites. Then include a small Mermaid `flowchart LR`; use green for healthy edges, red
for the broken edge, and label thread handoffs. Use a Gantt only when timing itself caused the bug.

End with the code fix and the exact tests that passed.
