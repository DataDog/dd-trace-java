---
name: fix-continuation-leakage
description: >
  Diagnose and fix scope or continuation lifecycle failures in dd-trace-java instrumentation
  tests. Use when a test reports a continuation leak, double resolution, activation after resolve,
  or an unclosed scope, or when strictTraceWrites(false) appears to hide one. Reads the automatic
  diagnostic timeline, finds the broken lifecycle edge, fixes it, and explains it with a compact
  Mermaid diagram.
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
4. Fix ownership where it breaks. Prefer one owner and `try/finally` cleanup across every exit.
5. Rerun the failing test, then its module.

Do not make the test green with `strictTraceWrites(false)` or
`@TrackScopeContinuations(enabled=false, reason="...")`. Those hide evidence. The opt-out requires
a reason and is only for a proven diagnostic incompatibility. If the failure is genuinely
intermittent, treat that as a flaky-test finding, keep diagnostics enabled, and link the `@Flaky`
annotation to a tracked issue.

## Explain it to a human

Lead with one sentence: what was captured, which cleanup edge was missing, and where. Cite the
timeline callsites. Then include a small Mermaid `flowchart LR`; use green for healthy edges, red
for the broken edge, and label thread handoffs. Use a Gantt only when timing itself caused the bug.

End with the code fix and the exact tests that passed.
