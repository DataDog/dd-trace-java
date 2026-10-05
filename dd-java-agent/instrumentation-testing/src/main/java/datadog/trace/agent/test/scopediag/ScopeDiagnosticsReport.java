package datadog.trace.agent.test.scopediag;

import datadog.trace.api.DDTraceId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Immutable scope and continuation lifecycle snapshot with derived failures. */
public final class ScopeDiagnosticsReport {
  private static final EnumSet<ScopeDiagnosticsCheck> ENFORCED_CHECKS =
      EnumSet.of(
          ScopeDiagnosticsCheck.LEAKED,
          ScopeDiagnosticsCheck.DOUBLE_FINISH,
          ScopeDiagnosticsCheck.ACTIVATE_AFTER_RESOLVE,
          ScopeDiagnosticsCheck.NEVER_CLOSED);

  private final List<ContinuationRecord> continuations;
  private final List<ScopeRecord> scopes;
  private final long t0;
  private final Map<ContinuationRecord, EnumSet<ScopeDiagnosticsCheck>> continuationFailures;
  private final Map<ScopeRecord, EnumSet<ScopeDiagnosticsCheck>> scopeFailures;
  private final Map<Long, ScopeRecord> scopeBySeq;

  ScopeDiagnosticsReport(
      List<ContinuationRecord> continuations,
      List<ScopeRecord> scopes,
      Map<DDTraceId, Long> rootWrittenNanos) {
    this.continuations = new ArrayList<>(continuations.size());
    for (ContinuationRecord continuation : continuations) {
      this.continuations.add(continuation.snapshot());
    }
    this.continuations.sort((a, b) -> Long.compare(a.firstNanos(), b.firstNanos()));
    this.scopes = new ArrayList<>(scopes.size());
    for (ScopeRecord scope : scopes) {
      this.scopes.add(scope.snapshot());
    }
    this.scopes.sort((a, b) -> Long.compare(a.firstNanos(), b.firstNanos()));
    this.t0 = computeT0(this.continuations, this.scopes);
    this.continuationFailures = classifyContinuations(this.continuations, rootWrittenNanos);
    this.scopeFailures = classifyScopes(this.scopes);
    this.scopeBySeq = new LinkedHashMap<>();
    for (ScopeRecord s : this.scopes) {
      scopeBySeq.put(s.seq, s);
    }
  }

  private static long computeT0(List<ContinuationRecord> continuations, List<ScopeRecord> scopes) {
    long min = Long.MAX_VALUE;
    for (ContinuationRecord r : continuations) {
      min = Math.min(min, r.firstNanos());
    }
    for (ScopeRecord s : scopes) {
      min = Math.min(min, s.firstNanos());
    }
    return min == Long.MAX_VALUE ? 0 : min;
  }

  private static Map<ContinuationRecord, EnumSet<ScopeDiagnosticsCheck>> classifyContinuations(
      List<ContinuationRecord> records, Map<DDTraceId, Long> rootWrittenNanos) {
    Map<ContinuationRecord, EnumSet<ScopeDiagnosticsCheck>> result = new LinkedHashMap<>();
    for (ContinuationRecord r : records) {
      EnumSet<ScopeDiagnosticsCheck> failures = r.failures(rootWrittenNanos.get(r.traceId));
      if (!failures.isEmpty()) {
        result.put(r, failures);
      }
    }
    return result;
  }

  private static Map<ScopeRecord, EnumSet<ScopeDiagnosticsCheck>> classifyScopes(
      List<ScopeRecord> scopes) {
    Map<ScopeRecord, EnumSet<ScopeDiagnosticsCheck>> result = new LinkedHashMap<>();
    for (ScopeRecord s : scopes) {
      EnumSet<ScopeDiagnosticsCheck> failures = s.failures();
      if (!failures.isEmpty()) {
        result.put(s, failures);
      }
    }
    return result;
  }

  public List<ContinuationRecord> records() {
    return new ArrayList<>(continuations);
  }

  public List<ScopeRecord> scopeRecords() {
    return new ArrayList<>(scopes);
  }

  public Map<ContinuationRecord, EnumSet<ScopeDiagnosticsCheck>> findings() {
    return new LinkedHashMap<>(continuationFailures);
  }

  public Map<ScopeRecord, EnumSet<ScopeDiagnosticsCheck>> scopeFindings() {
    return new LinkedHashMap<>(scopeFailures);
  }

  public int leakCount() {
    return countWith(continuationFailures, ScopeDiagnosticsCheck.LEAKED);
  }

  public int lateCount() {
    return countWith(continuationFailures, ScopeDiagnosticsCheck.LATE_FINISH);
  }

  public int doubleCount() {
    return countWith(continuationFailures, ScopeDiagnosticsCheck.DOUBLE_FINISH);
  }

  public int activateAfterResolveCount() {
    return countWith(continuationFailures, ScopeDiagnosticsCheck.ACTIVATE_AFTER_RESOLVE);
  }

  public int neverClosedScopeCount() {
    return countWith(scopeFailures, ScopeDiagnosticsCheck.NEVER_CLOSED);
  }

  public int closeWrongThreadCount() {
    return countWith(scopeFailures, ScopeDiagnosticsCheck.CLOSE_WRONG_THREAD);
  }

  public int deferredCleanupScopeCount() {
    int count = 0;
    for (ScopeRecord scope : scopes) {
      if (scope.deferredCleanup() && !scope.closed()) {
        count++;
      }
    }
    return count;
  }

  boolean hasIncompleteLifecycles() {
    return leakCount() > 0 || neverClosedScopeCount() > 0;
  }

  private static <K> int countWith(
      Map<K, EnumSet<ScopeDiagnosticsCheck>> findings, ScopeDiagnosticsCheck check) {
    int n = 0;
    for (EnumSet<ScopeDiagnosticsCheck> f : findings.values()) {
      if (f.contains(check)) {
        n++;
      }
    }
    return n;
  }

  /** Returns whether the report contains a violation that should fail the test. */
  public boolean hasViolations() {
    return hasViolations(EnumSet.allOf(ScopeDiagnosticsCheck.class));
  }

  /** Returns whether an enabled check contains a finding enforced by the current policy. */
  boolean hasViolations(Set<ScopeDiagnosticsCheck> enabledChecks) {
    for (ScopeDiagnosticsCheck check : enabledChecks) {
      if (ENFORCED_CHECKS.contains(check) && count(check) > 0) {
        return true;
      }
    }
    return false;
  }

  private int count(ScopeDiagnosticsCheck check) {
    switch (check) {
      case LEAKED:
        return leakCount();
      case LATE_FINISH:
        return lateCount();
      case DOUBLE_FINISH:
        return doubleCount();
      case ACTIVATE_AFTER_RESOLVE:
        return activateAfterResolveCount();
      case CLOSE_WRONG_THREAD:
        return closeWrongThreadCount();
      case NEVER_CLOSED:
        return neverClosedScopeCount();
      default:
        throw new IllegalStateException("Unknown scope diagnostic check: " + check);
    }
  }

  /** True when the report contains either a failing problem or an advisory signal. */
  public boolean hasFindings() {
    return !continuationFailures.isEmpty() || !scopeFailures.isEmpty();
  }

  private void appendHeader(StringBuilder sb, String title) {
    sb.append(title)
        .append(" (")
        .append(continuations.size())
        .append(" continuations, ")
        .append(scopes.size())
        .append(" scopes; ")
        .append(leakCount())
        .append(" leaked, ")
        .append(lateCount())
        .append(" late, ")
        .append(doubleCount())
        .append(" double, ")
        .append(activateAfterResolveCount())
        .append(" activate-after-resolve | scopes: ")
        .append(neverClosedScopeCount())
        .append(" never-closed, ")
        .append(deferredCleanupScopeCount())
        .append(" deferred, ")
        .append(closeWrongThreadCount())
        .append(" wrong-thread)\n");
  }

  /** Renders flagged continuations and scopes with their call sites. */
  public String renderSummary() {
    StringBuilder sb = new StringBuilder();
    appendHeader(sb, "Scope/continuation problems");
    if (continuationFailures.isEmpty() && scopeFailures.isEmpty()) {
      sb.append("  (none)\n");
      return sb.toString();
    }
    for (Map.Entry<ContinuationRecord, EnumSet<ScopeDiagnosticsCheck>> e :
        continuationFailures.entrySet()) {
      ContinuationRecord r = e.getKey();
      ScopeEvent capture = r.capture();
      sb.append("  ")
          .append(e.getValue())
          .append(" #")
          .append(r.seq)
          .append(" trace=")
          .append(r.traceId)
          .append(" src=")
          .append(r.sourceName())
          .append(" captured at ")
          .append(capture == null || capture.callsite() == null ? "<unknown>" : capture.callsite())
          .append('\n');
    }
    for (Map.Entry<ScopeRecord, EnumSet<ScopeDiagnosticsCheck>> e : scopeFailures.entrySet()) {
      ScopeRecord s = e.getKey();
      ScopeEvent open = s.open();
      sb.append("  ")
          .append(e.getValue())
          .append(" scope#")
          .append(s.seq)
          .append(" trace=")
          .append(s.traceId)
          .append(" src=")
          .append(s.sourceName())
          .append(" opened at ")
          .append(open == null || open.callsite() == null ? "<unknown>" : open.callsite())
          .append('\n');
    }
    return sb.toString();
  }

  private static final int TIMELINE_FRAMES = 3;

  /** Renders all events with relative times, threads, call sites, and linked scopes. */
  public String renderTimeline() {
    StringBuilder sb = new StringBuilder();
    appendHeader(sb, "Scope/continuation timeline");
    if (continuations.isEmpty() && scopes.isEmpty()) {
      sb.append("  (nothing captured)\n");
      return sb.toString();
    }

    for (ContinuationRecord r : continuations) {
      EnumSet<ScopeDiagnosticsCheck> failures =
          continuationFailures.getOrDefault(r, EnumSet.noneOf(ScopeDiagnosticsCheck.class));
      sb.append("\n#")
          .append(r.seq)
          .append(' ')
          .append(r.status())
          .append(" trace=")
          .append(r.traceId)
          .append(" span=")
          .append(r.spanId);
      if (r.spanName != null) {
        sb.append(" \"").append(r.spanName).append('"');
      }
      sb.append(" src=").append(r.sourceName());
      if (r.orphan) {
        sb.append(" [ORPHAN]");
      }
      if (r.threadHandoff()) {
        sb.append(" [handoff]");
      }
      if (!failures.isEmpty()) {
        sb.append(' ').append(failures);
      }
      sb.append(timing(r)).append('\n');

      appendEvent(sb, "capture ", r.capture());
      for (ScopeEvent a : r.resumes()) {
        appendEvent(sb, "resume  ", a);
      }
      for (ScopeEvent f : r.failedActivations()) {
        appendEvent(sb, "act-fail", f);
      }
      ScopeEvent terminal = r.terminal();
      if (terminal != null) {
        appendEvent(
            sb,
            terminal.type == ScopeEvent.Type.RESOLVE_RELEASE ? "release " : "finish  ",
            terminal);
      }
      for (ScopeEvent extra : r.extraTerminals()) {
        appendEvent(sb, "DOUBLE  ", extra);
      }
      for (long scopeSeq : r.scopeRecordSeqs()) {
        ScopeRecord scope = scopeBySeq.get(scopeSeq);
        if (scope != null) {
          appendScopeLine(sb, "  ", scope);
        }
      }
      if (terminal == null) {
        sb.append("  LEAKED   (never finished or released)\n");
      }
    }

    List<ScopeRecord> orphanScopes = new ArrayList<>();
    for (ScopeRecord s : scopes) {
      if (s.continuationSeq == null) {
        orphanScopes.add(s);
      }
    }
    if (!orphanScopes.isEmpty()) {
      sb.append("\nNon-continuation scopes:\n");
      for (ScopeRecord s : orphanScopes) {
        appendScopeLine(sb, "  ", s);
      }
    }
    return sb.toString();
  }

  private String timing(ContinuationRecord r) {
    StringBuilder sb = new StringBuilder();
    Long capToResume = r.captureToFirstResumeNanos();
    Long age = r.ageAtTerminalNanos();
    if (capToResume != null) {
      sb.append("  cap->resume=").append(millis(capToResume)).append("ms");
    }
    if (age != null) {
      sb.append("  age=").append(millis(age)).append("ms");
    }
    return sb.toString();
  }

  private void appendScopeLine(StringBuilder sb, String indent, ScopeRecord scope) {
    EnumSet<ScopeDiagnosticsCheck> failures =
        scopeFailures.getOrDefault(scope, EnumSet.noneOf(ScopeDiagnosticsCheck.class));
    sb.append(indent).append("scope#").append(scope.seq).append(' ').append(scope.sourceName());
    if (scope.spanName != null) {
      sb.append(" \"").append(scope.spanName).append('"');
    }
    ScopeEvent open = scope.open();
    ScopeEvent close = scope.close();
    if (open != null) {
      sb.append("  open +").append(relMillis(open.nanos)).append("ms @ ").append(open.threadName);
    }
    if (close != null) {
      sb.append("  close +")
          .append(relMillis(close.nanos))
          .append("ms @ ")
          .append(close.threadName);
      Long active = scope.activeDurationNanos();
      if (active != null) {
        sb.append(" (active ").append(millis(active)).append("ms)");
      }
    }
    if (scope.threadHandoff()) {
      sb.append(" [handoff]");
    }
    if (scope.deferredCleanup() && !scope.closed()) {
      sb.append(" [deferred-cleanup]");
    }
    if (!failures.isEmpty()) {
      sb.append(' ').append(failures);
    }
    sb.append('\n');
    for (ScopeEvent event : scope.wrongThreadCloses()) {
      appendEvent(sb, indent + "wrong-thread close", event);
    }
  }

  private void appendEvent(StringBuilder sb, String label, ScopeEvent event) {
    if (event == null) {
      sb.append("  ").append(label).append("  (not observed)\n");
      return;
    }
    sb.append("  ")
        .append(label)
        .append("  +")
        .append(relMillis(event.nanos))
        .append("ms  @ ")
        .append(event.threadName)
        .append("  at ")
        .append(event.callsite() == null ? "<filtered>" : event.callsite())
        .append('\n');
    StackTraceElement[] stack = event.stack;
    if (stack != null) {
      for (int i = 1; i < stack.length && i < TIMELINE_FRAMES; i++) {
        sb.append("              from ").append(stack[i]).append('\n');
      }
    }
  }

  private String relMillis(long nanos) {
    return String.format("%.3f", (nanos - t0) / 1_000_000.0);
  }

  private static String millis(long nanos) {
    return String.format("%.3f", nanos / 1_000_000.0);
  }
}
