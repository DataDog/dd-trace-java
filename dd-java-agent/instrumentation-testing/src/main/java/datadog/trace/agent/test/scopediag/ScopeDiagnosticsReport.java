package datadog.trace.agent.test.scopediag;

import datadog.trace.api.DDTraceId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An immutable snapshot of the recorded continuation and scope lifecycles plus the derived failure
 * findings. Exposes a problem-only text summary ({@link #renderSummary()}); richer visualizations
 * (Gantt, DAG) are produced by the {@code investigate-continuation-leakage} skill from the recorded
 * data rather than rendered here.
 *
 * <p>The two lifecycles are kept separate: {@link ContinuationRecord} (captured → resumed →
 * finished) and {@link ScopeRecord} (opened → closed). A scope spawned by resuming a continuation
 * is linked to it ({@link ContinuationRecord#scopeRecordSeqs()} / {@link
 * ScopeRecord#continuationSeq}).
 */
public final class ScopeDiagnosticsReport {
  private final List<ContinuationRecord> continuations;
  private final List<ScopeRecord> scopes;
  private final Map<ContinuationRecord, EnumSet<Failure>> continuationFailures;
  private final Map<ScopeRecord, EnumSet<Failure>> scopeFailures;

  ScopeDiagnosticsReport(
      List<ContinuationRecord> continuations,
      List<ScopeRecord> scopes,
      Map<DDTraceId, Long> rootWrittenNanos) {
    this.continuations = new ArrayList<>(continuations);
    this.continuations.sort((a, b) -> Long.compare(a.firstNanos(), b.firstNanos()));
    this.scopes = new ArrayList<>(scopes);
    this.scopes.sort((a, b) -> Long.compare(a.firstNanos(), b.firstNanos()));
    this.continuationFailures = classifyContinuations(this.continuations, rootWrittenNanos);
    this.scopeFailures = classifyScopes(this.scopes);
  }

  private static Map<ContinuationRecord, EnumSet<Failure>> classifyContinuations(
      List<ContinuationRecord> records, Map<DDTraceId, Long> rootWrittenNanos) {
    Map<ContinuationRecord, EnumSet<Failure>> result = new LinkedHashMap<>();
    for (ContinuationRecord r : records) {
      EnumSet<Failure> failures = r.failures(rootWrittenNanos.get(r.traceId));
      if (!failures.isEmpty()) {
        result.put(r, failures);
      }
    }
    return result;
  }

  private static Map<ScopeRecord, EnumSet<Failure>> classifyScopes(List<ScopeRecord> scopes) {
    Map<ScopeRecord, EnumSet<Failure>> result = new LinkedHashMap<>();
    for (ScopeRecord s : scopes) {
      EnumSet<Failure> failures = s.failures();
      if (!failures.isEmpty()) {
        result.put(s, failures);
      }
    }
    return result;
  }

  // ---- accessors -----------------------------------------------------------

  public List<ContinuationRecord> records() {
    return new ArrayList<>(continuations);
  }

  public List<ScopeRecord> scopeRecords() {
    return new ArrayList<>(scopes);
  }

  public Map<ContinuationRecord, EnumSet<Failure>> findings() {
    return new LinkedHashMap<>(continuationFailures);
  }

  public Map<ScopeRecord, EnumSet<Failure>> scopeFindings() {
    return new LinkedHashMap<>(scopeFailures);
  }

  public int leakCount() {
    return countWith(continuationFailures, Failure.LEAKED);
  }

  public int lateCount() {
    return countWith(continuationFailures, Failure.LATE_FINISH);
  }

  public int doubleCount() {
    return countWith(continuationFailures, Failure.DOUBLE_FINISH);
  }

  public int activateAfterResolveCount() {
    return countWith(continuationFailures, Failure.ACTIVATE_AFTER_RESOLVE);
  }

  public int neverClosedScopeCount() {
    return countWith(scopeFailures, Failure.NEVER_CLOSED);
  }

  public int closeWrongThreadCount() {
    return countWith(scopeFailures, Failure.CLOSE_WRONG_THREAD);
  }

  private static <K> int countWith(Map<K, EnumSet<Failure>> findings, Failure failure) {
    int n = 0;
    for (EnumSet<Failure> f : findings.values()) {
      if (f.contains(failure)) {
        n++;
      }
    }
    return n;
  }

  /**
   * True when there is a genuine bug to fail on: a never-resolved leak, a double finish, an
   * activation after resolve, or a scope that was never closed. {@link Failure#LATE_FINISH} and
   * {@link Failure#CLOSE_WRONG_THREAD} are reported but do not fail (frequently legitimate async or
   * teardown ordering).
   */
  public boolean hasProblems() {
    return leakCount() > 0
        || doubleCount() > 0
        || activateAfterResolveCount() > 0
        || neverClosedScopeCount() > 0;
  }

  // ---- rendering: text summary ---------------------------------------------

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
        .append(closeWrongThreadCount())
        .append(" wrong-thread)\n");
  }

  /** Problem-only summary: just the flagged continuations and scopes with their callsites. */
  public String renderSummary() {
    StringBuilder sb = new StringBuilder();
    appendHeader(sb, "Scope/continuation problems");
    if (continuationFailures.isEmpty() && scopeFailures.isEmpty()) {
      sb.append("  (none)\n");
      return sb.toString();
    }
    for (Map.Entry<ContinuationRecord, EnumSet<Failure>> e : continuationFailures.entrySet()) {
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
    for (Map.Entry<ScopeRecord, EnumSet<Failure>> e : scopeFailures.entrySet()) {
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
}
