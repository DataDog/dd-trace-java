package datadog.trace.agent.test.scopediag;

import datadog.trace.api.DDTraceId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An immutable snapshot of the recorded continuation lifecycles plus the derived leak findings.
 * Renders three views: a text Gantt timeline, a leak-only summary, and a JSON document.
 */
public final class ScopeDiagnosticsReport {
  /** A derived problem classification for a single continuation. */
  public enum Flag {
    /** Captured but never resolved (neither finished nor cancelled) within the window. */
    LEAK,
    /** Activated or resolved after the root span of its trace had already been written. */
    LATE,
    /** Resolved more than once, or activated after being resolved. */
    DOUBLE
  }

  private final List<ContinuationRecord> records;
  private final Map<DDTraceId, Long> rootWrittenNanos;
  private final long t0;
  private final Map<ContinuationRecord, EnumSet<Flag>> findings;

  ScopeDiagnosticsReport(List<ContinuationRecord> records, Map<DDTraceId, Long> rootWrittenNanos) {
    this.records = new ArrayList<>(records);
    this.records.sort((a, b) -> Long.compare(a.firstNanos(), b.firstNanos()));
    this.rootWrittenNanos = rootWrittenNanos;
    this.t0 = computeT0(this.records);
    this.findings = classify(this.records, rootWrittenNanos);
  }

  private static long computeT0(List<ContinuationRecord> records) {
    long min = Long.MAX_VALUE;
    for (ContinuationRecord r : records) {
      min = Math.min(min, r.firstNanos());
    }
    return min == Long.MAX_VALUE ? 0 : min;
  }

  private static Map<ContinuationRecord, EnumSet<Flag>> classify(
      List<ContinuationRecord> records, Map<DDTraceId, Long> rootWrittenNanos) {
    Map<ContinuationRecord, EnumSet<Flag>> result = new LinkedHashMap<>();
    for (ContinuationRecord r : records) {
      EnumSet<Flag> flags = EnumSet.noneOf(Flag.class);

      if (!r.isResolved()) {
        flags.add(Flag.LEAK);
      }

      List<ScopeEvent> resolutions = r.resolutions();
      List<ScopeEvent> activations = r.activations();
      if (resolutions.size() > 1) {
        flags.add(Flag.DOUBLE);
      }
      if (!resolutions.isEmpty()) {
        long firstResolve = resolutions.get(0).nanos;
        for (ScopeEvent a : activations) {
          if (a.nanos > firstResolve) {
            flags.add(Flag.DOUBLE);
            break;
          }
        }
      }

      Long rootNanos = rootWrittenNanos.get(r.traceId);
      if (rootNanos != null) {
        if (laterThan(activations, rootNanos) || laterThan(resolutions, rootNanos)) {
          flags.add(Flag.LATE);
        }
      }

      if (!flags.isEmpty()) {
        result.put(r, flags);
      }
    }
    return result;
  }

  private static boolean laterThan(List<ScopeEvent> events, long nanos) {
    for (ScopeEvent e : events) {
      if (e.nanos > nanos) {
        return true;
      }
    }
    return false;
  }

  public List<ContinuationRecord> records() {
    return new ArrayList<>(records);
  }

  public Map<ContinuationRecord, EnumSet<Flag>> findings() {
    return new LinkedHashMap<>(findings);
  }

  public int leakCount() {
    return countWith(Flag.LEAK);
  }

  public int lateCount() {
    return countWith(Flag.LATE);
  }

  public int doubleCount() {
    return countWith(Flag.DOUBLE);
  }

  private int countWith(Flag flag) {
    int n = 0;
    for (EnumSet<Flag> flags : findings.values()) {
      if (flags.contains(flag)) {
        n++;
      }
    }
    return n;
  }

  /** True when there is a genuine bug to fail on: a never-resolved leak or a double resolution. */
  public boolean hasProblems() {
    return leakCount() > 0 || doubleCount() > 0;
  }

  // ---- rendering -----------------------------------------------------------

  /** Full cross-thread Gantt timeline, one block per continuation. */
  public String renderGantt() {
    StringBuilder sb = new StringBuilder();
    sb.append("Scope continuation timeline (")
        .append(records.size())
        .append(" continuations, ")
        .append(leakCount())
        .append(" leaked, ")
        .append(lateCount())
        .append(" late-after-root, ")
        .append(doubleCount())
        .append(" double/invalid)\n");
    if (records.isEmpty()) {
      sb.append("  (no continuations captured)\n");
      return sb.toString();
    }
    for (ContinuationRecord r : records) {
      EnumSet<Flag> flags = findings.getOrDefault(r, EnumSet.noneOf(Flag.class));
      sb.append("\n#")
          .append(r.seq)
          .append(" trace=")
          .append(r.traceId)
          .append(" span=")
          .append(r.spanId)
          .append(" src=")
          .append(r.sourceName());
      if (r.orphan) {
        sb.append(" [ORPHAN]");
      }
      if (!flags.isEmpty()) {
        sb.append(" ").append(flags);
      }
      sb.append('\n');
      appendEvent(sb, "capture ", r.capture());
      for (ScopeEvent a : r.activations()) {
        appendEvent(sb, "activate", a);
      }
      for (ScopeEvent res : r.resolutions()) {
        appendEvent(sb, res.type == ScopeEvent.Type.RESOLVE_CANCEL ? "cancel  " : "finish  ", res);
      }
      if (!r.isResolved()) {
        sb.append("  ").append("LEAKED  ").append(" (never finished or cancelled)\n");
      }
    }
    return sb.toString();
  }

  private static final int GANTT_FRAMES = 3;

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
    // a couple of caller frames give context when the top frame is generic (Thread.run, a
    // listener dispatch, etc.) — they reveal which library/app code drove the event
    StackTraceElement[] stack = event.stack;
    if (stack != null) {
      for (int i = 1; i < stack.length && i < GANTT_FRAMES; i++) {
        sb.append("              from ").append(stack[i]).append('\n');
      }
    }
  }

  /** Leak-only summary: just the flagged continuations with their callsites. */
  public String renderSummary() {
    StringBuilder sb = new StringBuilder();
    sb.append("Scope continuation problems: ")
        .append(leakCount())
        .append(" leaked, ")
        .append(lateCount())
        .append(" late-after-root, ")
        .append(doubleCount())
        .append(" double/invalid\n");
    if (findings.isEmpty()) {
      sb.append("  (none)\n");
      return sb.toString();
    }
    for (Map.Entry<ContinuationRecord, EnumSet<Flag>> e : findings.entrySet()) {
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
    return sb.toString();
  }

  /**
   * Renders a Mermaid Gantt diagram (wrapped in a ```mermaid fence) that renders inline in GitHub,
   * GitLab, and most IDEs. Continuations are grouped into swimlanes by the thread where they were
   * activated/resolved; each is a bar from capture to resolution. Leaks extend to the end of the
   * window and are marked {@code crit} (red); late-after-root is marked {@code active}. Times are
   * milliseconds relative to the first event (rounded — this is an overview, not a profiler).
   */
  public String toMermaidGantt() {
    StringBuilder sb = new StringBuilder();
    sb.append("```mermaid\n");
    sb.append("gantt\n");
    sb.append("  title Scope continuations (")
        .append(records.size())
        .append(" total, ")
        .append(leakCount())
        .append(" leaked, ")
        .append(lateCount())
        .append(" late, ")
        .append(doubleCount())
        .append(" double)\n");
    sb.append("  dateFormat x\n");
    sb.append("  axisFormat %Lms\n");
    if (records.isEmpty()) {
      sb.append("  section none\n    no continuations captured :done, 0, 1\n```\n");
      return sb.toString();
    }

    long windowEnd = relMillisRounded(maxNanos());

    // A continuation contributes a "cap" marker in its capture-thread lane and (when it ran) a
    // run/finish bar in its activation/resolution-thread lane. The same #seq appearing in two lanes
    // makes the cross-thread hop visible: captured here, continued/cancelled there.
    Map<String, List<String>> lanes = new LinkedHashMap<>();
    for (ContinuationRecord r : records) {
      EnumSet<Flag> flags = findings.getOrDefault(r, EnumSet.noneOf(Flag.class));
      ScopeEvent capture = r.capture();
      List<ScopeEvent> activations = r.activations();
      List<ScopeEvent> resolutions = r.resolutions();
      boolean leak = flags.contains(Flag.LEAK);

      if (capture != null) {
        long start = relMillisRounded(capture.nanos);
        if (leak && activations.isEmpty()) {
          // captured but never even activated: leak lives, open-ended, in the capture lane
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

      if (!activations.isEmpty()) {
        ScopeEvent firstAct = activations.get(0);
        long runStart = relMillisRounded(firstAct.nanos);
        if (resolutions.isEmpty()) {
          // activated but never closed: open-ended leak in the activation lane
          addTask(
              lanes,
              firstAct.threadName,
              "#" + r.seq + " LEAK ran " + where(firstAct),
              "crit",
              runStart,
              Math.max(runStart + 1, windowEnd));
        } else {
          ScopeEvent lastRes = resolutions.get(resolutions.size() - 1);
          long runEnd = relMillisRounded(lastRes.nanos);
          if (runEnd <= runStart) {
            runEnd = runStart + 1;
          }
          String verb = lastRes.type == ScopeEvent.Type.RESOLVE_CANCEL ? "cancel" : "fin";
          String tag =
              flags.contains(Flag.DOUBLE) ? "crit" : flags.contains(Flag.LATE) ? "active" : "done";
          addTask(
              lanes,
              lastRes.threadName,
              "#" + r.seq + " " + verb + " " + where(lastRes),
              tag,
              runStart,
              runEnd);
        }
      } else if (!resolutions.isEmpty()) {
        // resolved without an observed activation (e.g. cancelled before use)
        ScopeEvent lastRes = resolutions.get(resolutions.size() - 1);
        long start = relMillisRounded(lastRes.nanos);
        String verb = lastRes.type == ScopeEvent.Type.RESOLVE_CANCEL ? "cancel" : "fin";
        addTask(
            lanes,
            lastRes.threadName,
            "#" + r.seq + " " + verb + " " + where(lastRes),
            "done",
            start,
            start + 1);
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

  /** Short "SimpleClass.method" for a Mermaid label, or "?" when the callsite was filtered out. */
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
    for (ContinuationRecord r : records) {
      ScopeEvent c = r.capture();
      if (c != null) {
        max = Math.max(max, c.nanos);
      }
      for (ScopeEvent e : r.activations()) {
        max = Math.max(max, e.nanos);
      }
      for (ScopeEvent e : r.resolutions()) {
        max = Math.max(max, e.nanos);
      }
    }
    return max;
  }

  private long relMillisRounded(long nanos) {
    return Math.round((nanos - t0) / 1_000_000.0);
  }

  /** Strips characters that would break Mermaid task/section syntax. */
  private static String sanitize(String s) {
    return s.replace(':', ' ').replace(',', ' ').replace(';', ' ').replace('\n', ' ');
  }

  /** Machine-readable view for CI artifacts and later tooling. */
  public String toJson() {
    StringBuilder sb = new StringBuilder();
    sb.append("{\"continuations\":[");
    for (int i = 0; i < records.size(); i++) {
      if (i > 0) {
        sb.append(',');
      }
      appendRecordJson(sb, records.get(i));
    }
    sb.append("],\"summary\":{\"total\":")
        .append(records.size())
        .append(",\"leaked\":")
        .append(leakCount())
        .append(",\"lateAfterRoot\":")
        .append(lateCount())
        .append(",\"doubleOrInvalid\":")
        .append(doubleCount())
        .append("}}");
    return sb.toString();
  }

  private void appendRecordJson(StringBuilder sb, ContinuationRecord r) {
    EnumSet<Flag> flags = findings.getOrDefault(r, EnumSet.noneOf(Flag.class));
    sb.append("{\"seq\":")
        .append(r.seq)
        .append(",\"traceId\":\"")
        .append(r.traceId)
        .append("\",\"spanId\":")
        .append(r.spanId)
        .append(",\"source\":\"")
        .append(r.sourceName())
        .append("\",\"orphan\":")
        .append(r.orphan)
        .append(",\"flags\":[");
    boolean first = true;
    for (Flag f : flags) {
      if (!first) {
        sb.append(',');
      }
      sb.append('"').append(f).append('"');
      first = false;
    }
    sb.append("],\"capture\":");
    appendEventJson(sb, r.capture());
    sb.append(",\"activations\":[");
    appendEventsJson(sb, r.activations());
    sb.append("],\"resolutions\":[");
    appendEventsJson(sb, r.resolutions());
    sb.append("]}");
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
    double ms = (nanos - t0) / 1_000_000.0;
    return String.format("%.3f", ms);
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
