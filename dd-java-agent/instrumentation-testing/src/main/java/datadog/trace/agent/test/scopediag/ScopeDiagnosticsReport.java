package datadog.trace.agent.test.scopediag;

import datadog.trace.api.DDTraceId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An immutable snapshot of the recorded continuation and scope lifecycles plus the derived failure
 * findings. Exposes two text renderings: a problem-only summary ({@link #renderSummary()}) for
 * quick triage / assertion messages, and a complete timeline ({@link #renderTimeline()}) that dumps
 * <em>every</em> continuation and scope with its full event lineage — emitted on every tracked test
 * regardless of whether anything leaked, so a graph or report can always be produced from it.
 *
 * <p>The two lifecycles are kept separate: {@link ContinuationRecord} (captured → resumed →
 * finished) and {@link ScopeRecord} (opened → closed). A scope spawned by resuming a continuation
 * is linked to it ({@link ContinuationRecord#scopeRecordSeqs()} / {@link
 * ScopeRecord#continuationSeq}) and rendered nested under it in the timeline.
 */
public final class ScopeDiagnosticsReport {
  private final List<ContinuationRecord> continuations;
  private final List<ScopeRecord> scopes;
  private final long t0;
  private final Map<ContinuationRecord, EnumSet<Failure>> continuationFailures;
  private final Map<ScopeRecord, EnumSet<Failure>> scopeFailures;
  private final Map<Long, ScopeRecord> scopeBySeq;

  ScopeDiagnosticsReport(
      List<ContinuationRecord> continuations,
      List<ScopeRecord> scopes,
      Map<DDTraceId, Long> rootWrittenNanos) {
    this.continuations = new ArrayList<>(continuations);
    this.continuations.sort((a, b) -> Long.compare(a.firstNanos(), b.firstNanos()));
    this.scopes = new ArrayList<>(scopes);
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

  // ---- rendering: complete timeline ----------------------------------------

  private static final int TIMELINE_FRAMES = 3;

  /**
   * Complete cross-thread timeline: one block per continuation (capture → resume(s) → terminal),
   * with the scopes it spawned nested under it, followed by any non-continuation scopes. Unlike
   * {@link #renderSummary()} this lists <em>all</em> records, not just the flagged ones, so a graph
   * (Gantt/DAG) or report can be reconstructed from it whether or not anything leaked. Each event
   * carries its relative time ({@code +Δms} from the first recorded event), thread, and callsite.
   */
  public String renderTimeline() {
    StringBuilder sb = new StringBuilder();
    appendHeader(sb, "Scope/continuation timeline");
    if (continuations.isEmpty() && scopes.isEmpty()) {
      sb.append("  (nothing captured)\n");
      return sb.toString();
    }

    for (ContinuationRecord r : continuations) {
      EnumSet<Failure> failures =
          continuationFailures.getOrDefault(r, EnumSet.noneOf(Failure.class));
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
            terminal.type == ScopeEvent.Type.RESOLVE_CANCEL ? "cancel  " : "finish  ",
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
        sb.append("  LEAKED   (never finished or cancelled)\n");
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
    EnumSet<Failure> failures = scopeFailures.getOrDefault(scope, EnumSet.noneOf(Failure.class));
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
    if (!failures.isEmpty()) {
      sb.append(' ').append(failures);
    }
    sb.append('\n');
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
