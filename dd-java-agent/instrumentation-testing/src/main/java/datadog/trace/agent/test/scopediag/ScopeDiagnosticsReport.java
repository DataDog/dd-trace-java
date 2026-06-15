package datadog.trace.agent.test.scopediag;

import datadog.trace.api.DDTraceId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An immutable snapshot of the recorded continuation and scope lifecycles plus the derived failure
 * findings. Renders a text Gantt timeline, a problem-only summary, a Mermaid Gantt, and JSON.
 *
 * <p>The two lifecycles are kept separate: {@link ContinuationRecord} (captured → resumed →
 * finished) and {@link ScopeRecord} (opened → closed). A scope spawned by resuming a continuation
 * is linked to it ({@link ContinuationRecord#scopeRecordSeqs()} / {@link
 * ScopeRecord#continuationSeq}) and rendered nested under it; other scopes render in a separate
 * section.
 */
public final class ScopeDiagnosticsReport {
  private final List<ContinuationRecord> continuations;
  private final List<ScopeRecord> scopes;
  private final Map<DDTraceId, Long> rootWrittenNanos;
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
    this.rootWrittenNanos = rootWrittenNanos;
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

  // ---- rendering: text Gantt -----------------------------------------------

  private static final int GANTT_FRAMES = 3;

  /** Full cross-thread Gantt timeline: one block per continuation, with nested scope bars. */
  public String renderGantt() {
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
      for (int i = 1; i < stack.length && i < GANTT_FRAMES; i++) {
        sb.append("              from ").append(stack[i]).append('\n');
      }
    }
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

  // ---- rendering: Mermaid Gantt --------------------------------------------

  /**
   * Renders a Mermaid Gantt diagram (wrapped in a ```mermaid fence). Continuations and scopes are
   * grouped into swimlanes by thread; a continuation contributes a capture marker in its capture
   * lane and a run/finish bar in its resume/terminal lane, and each linked scope contributes an
   * open→close bar in its close lane. Leaks/never-closed extend to the end of the window and are
   * marked {@code crit}; late/wrong-thread are marked {@code active}.
   */
  public String toMermaidGantt() {
    StringBuilder sb = new StringBuilder();
    sb.append("```mermaid\n");
    sb.append("gantt\n");
    sb.append("  title Scope/continuations (")
        .append(continuations.size())
        .append(" cont, ")
        .append(scopes.size())
        .append(" scopes, ")
        .append(leakCount())
        .append(" leaked, ")
        .append(neverClosedScopeCount())
        .append(" never-closed)\n");
    sb.append("  dateFormat x\n");
    sb.append("  axisFormat %Lms\n");
    if (continuations.isEmpty() && scopes.isEmpty()) {
      sb.append("  section none\n    nothing captured :done, 0, 1\n```\n");
      return sb.toString();
    }

    long windowEnd = relMillisRounded(maxNanos());
    Map<String, List<String>> lanes = new LinkedHashMap<>();

    for (ContinuationRecord r : continuations) {
      EnumSet<Failure> failures =
          continuationFailures.getOrDefault(r, EnumSet.noneOf(Failure.class));
      ScopeEvent capture = r.capture();
      List<ScopeEvent> resumes = r.resumes();
      ScopeEvent terminal = r.terminal();
      boolean leak = failures.contains(Failure.LEAKED);

      if (capture != null) {
        long start = relMillisRounded(capture.nanos);
        if (leak && resumes.isEmpty()) {
          addTask(
              lanes,
              capture.threadName,
              "#" + r.seq + " LEAK cap " + where(capture),
              "crit",
              start,
              Math.max(start + 1, windowEnd));
        } else {
          addTask(
              lanes,
              capture.threadName,
              "cap #" + r.seq + " " + where(capture),
              "milestone",
              start,
              start);
        }
      }

      if (!resumes.isEmpty()) {
        ScopeEvent firstResume = resumes.get(0);
        long runStart = relMillisRounded(firstResume.nanos);
        if (terminal == null) {
          addTask(
              lanes,
              firstResume.threadName,
              "#" + r.seq + " LEAK ran " + where(firstResume),
              "crit",
              runStart,
              Math.max(runStart + 1, windowEnd));
        } else {
          long runEnd = Math.max(runStart + 1, relMillisRounded(terminal.nanos));
          String verb = terminal.type == ScopeEvent.Type.RESOLVE_CANCEL ? "cancel" : "fin";
          String tag = continuationTag(failures);
          addTask(
              lanes,
              terminal.threadName,
              "#" + r.seq + " " + verb + " " + where(terminal),
              tag,
              runStart,
              runEnd);
        }
      } else if (terminal != null) {
        long start = relMillisRounded(terminal.nanos);
        String verb = terminal.type == ScopeEvent.Type.RESOLVE_CANCEL ? "cancel" : "fin";
        addTask(
            lanes,
            terminal.threadName,
            "#" + r.seq + " " + verb + " " + where(terminal),
            "done",
            start,
            start + 1);
      }
    }

    for (ScopeRecord s : scopes) {
      EnumSet<Failure> failures = scopeFailures.getOrDefault(s, EnumSet.noneOf(Failure.class));
      ScopeEvent open = s.open();
      ScopeEvent close = s.close();
      if (open == null) {
        continue;
      }
      long start = relMillisRounded(open.nanos);
      String label = "scope#" + s.seq + " " + where(open);
      if (close == null) {
        addTask(
            lanes,
            open.threadName,
            label + " NEVER-CLOSED",
            "crit",
            start,
            Math.max(start + 1, windowEnd));
      } else {
        long end = Math.max(start + 1, relMillisRounded(close.nanos));
        String tag = failures.contains(Failure.CLOSE_WRONG_THREAD) ? "active" : "done";
        addTask(lanes, close.threadName, label, tag, start, end);
      }
    }

    for (Map.Entry<String, List<String>> lane : lanes.entrySet()) {
      sb.append("  section ").append(sanitize(lane.getKey())).append('\n');
      for (String task : lane.getValue()) {
        sb.append("    ").append(task).append('\n');
      }
    }
    sb.append("```\n");
    return sb.toString();
  }

  private static String continuationTag(EnumSet<Failure> failures) {
    if (failures.contains(Failure.DOUBLE_FINISH)
        || failures.contains(Failure.ACTIVATE_AFTER_RESOLVE)) {
      return "crit";
    }
    if (failures.contains(Failure.LATE_FINISH)) {
      return "active";
    }
    return "done";
  }

  private void addTask(
      Map<String, List<String>> lanes,
      String thread,
      String label,
      String tag,
      long start,
      long end) {
    lanes
        .computeIfAbsent(thread, k -> new ArrayList<>())
        .add(sanitize(label) + " :" + tag + ", " + start + ", " + end);
  }

  private static String where(ScopeEvent event) {
    StackTraceElement frame = event == null ? null : event.callsite();
    if (frame == null) {
      return "?";
    }
    String className = frame.getClassName();
    int dot = className.lastIndexOf('.');
    String simple = dot >= 0 ? className.substring(dot + 1) : className;
    return simple + "." + frame.getMethodName();
  }

  private long maxNanos() {
    long max = t0;
    for (ContinuationRecord r : continuations) {
      max = Math.max(max, lastNanos(r));
    }
    for (ScopeRecord s : scopes) {
      ScopeEvent open = s.open();
      ScopeEvent close = s.close();
      if (open != null) {
        max = Math.max(max, open.nanos);
      }
      if (close != null) {
        max = Math.max(max, close.nanos);
      }
    }
    return max;
  }

  private static long lastNanos(ContinuationRecord r) {
    long max = r.capture() != null ? r.capture().nanos : Long.MIN_VALUE;
    for (ScopeEvent e : r.resumes()) {
      max = Math.max(max, e.nanos);
    }
    if (r.terminal() != null) {
      max = Math.max(max, r.terminal().nanos);
    }
    for (ScopeEvent e : r.extraTerminals()) {
      max = Math.max(max, e.nanos);
    }
    return max == Long.MIN_VALUE ? 0 : max;
  }

  private long relMillisRounded(long nanos) {
    return Math.round((nanos - t0) / 1_000_000.0);
  }

  private static String sanitize(String s) {
    return s.replace(':', ' ').replace(',', ' ').replace(';', ' ').replace('\n', ' ');
  }

  // ---- rendering: JSON -----------------------------------------------------

  /** Machine-readable view for CI artifacts and later tooling. */
  public String toJson() {
    StringBuilder sb = new StringBuilder();
    sb.append("{\"continuations\":[");
    for (int i = 0; i < continuations.size(); i++) {
      if (i > 0) {
        sb.append(',');
      }
      appendContinuationJson(sb, continuations.get(i));
    }
    sb.append("],\"scopes\":[");
    for (int i = 0; i < scopes.size(); i++) {
      if (i > 0) {
        sb.append(',');
      }
      appendScopeJson(sb, scopes.get(i));
    }
    sb.append("],\"summary\":{\"continuations\":")
        .append(continuations.size())
        .append(",\"scopes\":")
        .append(scopes.size())
        .append(",\"leaked\":")
        .append(leakCount())
        .append(",\"lateFinish\":")
        .append(lateCount())
        .append(",\"doubleFinish\":")
        .append(doubleCount())
        .append(",\"activateAfterResolve\":")
        .append(activateAfterResolveCount())
        .append(",\"neverClosedScopes\":")
        .append(neverClosedScopeCount())
        .append(",\"closeWrongThread\":")
        .append(closeWrongThreadCount())
        .append("}}");
    return sb.toString();
  }

  private void appendContinuationJson(StringBuilder sb, ContinuationRecord r) {
    EnumSet<Failure> failures = continuationFailures.getOrDefault(r, EnumSet.noneOf(Failure.class));
    sb.append("{\"seq\":")
        .append(r.seq)
        .append(",\"status\":\"")
        .append(r.status())
        .append("\",\"traceId\":\"")
        .append(r.traceId)
        .append("\",\"spanId\":")
        .append(r.spanId)
        .append(",\"spanName\":")
        .append(jsonString(r.spanName))
        .append(",\"source\":\"")
        .append(r.sourceName())
        .append("\",\"orphan\":")
        .append(r.orphan)
        .append(",\"threadHandoff\":")
        .append(r.threadHandoff())
        .append(",\"captureToFirstResumeMs\":")
        .append(millisOrNull(r.captureToFirstResumeNanos()))
        .append(",\"ageAtTerminalMs\":")
        .append(millisOrNull(r.ageAtTerminalNanos()))
        .append(",\"scopeSeqs\":")
        .append(r.scopeRecordSeqs())
        .append(",\"failures\":[");
    appendFailuresJson(sb, failures);
    sb.append("],\"capture\":");
    appendEventJson(sb, r.capture());
    sb.append(",\"resumes\":[");
    appendEventsJson(sb, r.resumes());
    sb.append("],\"failedActivations\":[");
    appendEventsJson(sb, r.failedActivations());
    sb.append("],\"terminal\":");
    appendEventJson(sb, r.terminal());
    sb.append(",\"extraTerminals\":[");
    appendEventsJson(sb, r.extraTerminals());
    sb.append("]}");
  }

  private void appendScopeJson(StringBuilder sb, ScopeRecord s) {
    EnumSet<Failure> failures = scopeFailures.getOrDefault(s, EnumSet.noneOf(Failure.class));
    sb.append("{\"seq\":")
        .append(s.seq)
        .append(",\"closed\":")
        .append(s.closed())
        .append(",\"traceId\":\"")
        .append(s.traceId)
        .append("\",\"spanId\":")
        .append(s.spanId)
        .append(",\"spanName\":")
        .append(jsonString(s.spanName))
        .append(",\"source\":\"")
        .append(s.sourceName())
        .append("\",\"continuationSeq\":")
        .append(s.continuationSeq)
        .append(",\"threadHandoff\":")
        .append(s.threadHandoff())
        .append(",\"activeDurationMs\":")
        .append(millisOrNull(s.activeDurationNanos()))
        .append(",\"failures\":[");
    appendFailuresJson(sb, failures);
    sb.append("],\"open\":");
    appendEventJson(sb, s.open());
    sb.append(",\"close\":");
    appendEventJson(sb, s.close());
    sb.append("}");
  }

  private static void appendFailuresJson(StringBuilder sb, EnumSet<Failure> failures) {
    boolean first = true;
    for (Failure f : failures) {
      if (!first) {
        sb.append(',');
      }
      sb.append('"').append(f).append('"');
      first = false;
    }
  }

  private void appendEventsJson(StringBuilder sb, List<ScopeEvent> events) {
    for (int i = 0; i < events.size(); i++) {
      if (i > 0) {
        sb.append(',');
      }
      appendEventJson(sb, events.get(i));
    }
  }

  private void appendEventJson(StringBuilder sb, ScopeEvent event) {
    if (event == null) {
      sb.append("null");
      return;
    }
    sb.append("{\"type\":\"")
        .append(event.type)
        .append("\",\"thread\":\"")
        .append(jsonEscape(event.threadName))
        .append("\",\"relMillis\":")
        .append(relMillis(event.nanos))
        .append(",\"callsite\":\"")
        .append(jsonEscape(event.callsite() == null ? "" : event.callsite().toString()))
        .append("\"}");
  }

  private String relMillis(long nanos) {
    return String.format("%.3f", (nanos - t0) / 1_000_000.0);
  }

  private static String millis(long nanos) {
    return String.format("%.3f", nanos / 1_000_000.0);
  }

  private static String millisOrNull(Long nanos) {
    return nanos == null ? "null" : millis(nanos);
  }

  private static String jsonString(String s) {
    return s == null ? "null" : "\"" + jsonEscape(s) + "\"";
  }

  private static String jsonEscape(String s) {
    StringBuilder out = new StringBuilder(s.length() + 8);
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"':
          out.append("\\\"");
          break;
        case '\\':
          out.append("\\\\");
          break;
        case '\n':
          out.append("\\n");
          break;
        case '\r':
          out.append("\\r");
          break;
        case '\t':
          out.append("\\t");
          break;
        default:
          out.append(c);
      }
    }
    return out.toString();
  }
}
