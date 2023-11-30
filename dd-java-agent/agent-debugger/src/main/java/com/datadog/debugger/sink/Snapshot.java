package com.datadog.debugger.sink;

import com.datadog.debugger.agent.Generated;
import datadog.trace.bootstrap.debugger.CapturedContext;
import datadog.trace.bootstrap.debugger.CapturedStackFrame;
import datadog.trace.bootstrap.debugger.EvaluationError;
import datadog.trace.bootstrap.debugger.ProbeImplementation;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Data class representing all data collected at a probe location */
public class Snapshot {
  private static final String LANGUAGE = "java";
  private static final int VERSION = 2;

  private String id;
  private final transient int version;
  private final long timestamp;
  private transient long duration;
  private final List<CapturedStackFrame> stack = new ArrayList<>();
  private final Captures captures;
  private final ProbeImplementation probe;
  private final String language;
  private final transient CapturedThread thread;
  private transient String traceId;
  private transient String spanId;
  private List<EvaluationError> evaluationErrors;
  private transient String message;
  private final transient int maxDepth;

  public Snapshot(java.lang.Thread thread, ProbeImplementation probeImplementation, int maxDepth) {
    this.id = UUID.randomUUID().toString();
    this.version = VERSION;
    this.timestamp = System.currentTimeMillis();
    this.captures = new Captures();
    this.language = LANGUAGE;
    this.thread = new CapturedThread(thread);
    this.probe = probeImplementation;
    this.maxDepth = maxDepth;
  }

  public Snapshot(
      String id,
      int version,
      long timestamp,
      long duration,
      List<CapturedStackFrame> stack,
      Snapshot.Captures captures,
      ProbeImplementation probeImplementation,
      String language,
      Snapshot.CapturedThread thread,
      String traceId,
      String spanId,
      int maxDepth) {
    this.id = id;
    this.version = version;
    this.timestamp = timestamp;
    this.duration = duration;
    this.stack.addAll(stack);
    this.captures = captures;
    this.probe = probeImplementation;
    this.language = language;
    this.thread = thread;
    this.traceId = traceId;
    this.spanId = spanId;
    this.maxDepth = maxDepth;
  }

  public void setEntry(CapturedContext context) {
    captures.setEntry(context);
  }

  public void setExit(CapturedContext context) {
    captures.setReturn(context);
  }

  public void setDuration(long duration) {
    this.duration = duration;
  }

  public void setMessage(String message) {
    this.message = message;
  }

  public void addLine(CapturedContext context, int line) {
    captures.addLine(line, context);
  }

  public void addCaughtExceptions(List<CapturedContext.CapturedThrowable> throwables) {
    if (throwables == null) {
      return;
    }
    for (CapturedContext.CapturedThrowable throwable : throwables) {
      captures.addCaughtException(throwable);
    }
  }

  public String getId() {
    return id;
  }

  public int getVersion() {
    return version;
  }

  public long getTimestamp() {
    return timestamp;
  }

  public long getDuration() {
    return duration;
  }

  public List<CapturedStackFrame> getStack() {
    return stack;
  }

  public Captures getCaptures() {
    return captures;
  }

  public ProbeImplementation getProbe() {
    return probe;
  }

  public String getLanguage() {
    return language;
  }

  public CapturedThread getThread() {
    return thread;
  }

  public String getTraceId() {
    return traceId;
  }

  public String getSpanId() {
    return spanId;
  }

  public List<EvaluationError> getEvaluationErrors() {
    return evaluationErrors;
  }

  public void addEvaluationErrors(List<EvaluationError> errors) {
    if (errors == null || errors.isEmpty()) {
      return;
    }
    if (evaluationErrors == null) {
      evaluationErrors = new ArrayList<>();
    }
    evaluationErrors.addAll(errors);
  }

  public String getMessage() {
    return message;
  }

  public int getMaxDepth() {
    return maxDepth;
  }

  public void recordStackTrace(int offset) {
    stack.clear();
    int cntr = 0;
    for (StackTraceElement ste : Thread.currentThread().getStackTrace()) {
      if (cntr++ < offset) {
        continue;
      }
      stack.add(CapturedStackFrame.from(ste));
    }
  }

  public void setTraceId(String traceId) {
    this.traceId = traceId;
  }

  public void setSpanId(String spanId) {
    this.spanId = spanId;
  }

  public enum Kind {
    ENTER,
    RETURN,
    UNHANDLED_EXCEPTION,
    HANDLED_EXCEPTION,
    BEFORE,
    AFTER;
  }

  /** Stores all collected data at different location (method entry/exit, lines, exceptions) */
  public static class Captures {
    private CapturedContext entry;
    private Map<Integer, CapturedContext> lines;
    // returnValue encoded into a local of CapturedContext
    private CapturedContext _return;

    private List<CapturedContext.CapturedThrowable> caughtExceptions;

    public CapturedContext getEntry() {
      return entry;
    }

    public Map<Integer, CapturedContext> getLines() {
      return lines;
    }

    public CapturedContext getReturn() {
      return _return;
    }

    public List<CapturedContext.CapturedThrowable> getCaughtExceptions() {
      return caughtExceptions;
    }

    public void setEntry(CapturedContext context) {
      entry = context;
    }

    public void setReturn(CapturedContext context) {
      _return = context;
    }

    public void addLine(int line, CapturedContext context) {
      if (lines == null) {
        lines = new HashMap<>();
      }
      lines.put(line, context); // /!\ boxing /!\
    }

    public void addCaughtException(CapturedContext.CapturedThrowable context) {
      if (caughtExceptions == null) {
        caughtExceptions = new ArrayList<>();
      }
      caughtExceptions.add(context);
    }

    @Generated
    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      Captures captures = (Captures) o;
      return Objects.equals(entry, captures.entry)
          && Objects.equals(lines, captures.lines)
          && Objects.equals(_return, captures._return)
          && Objects.equals(caughtExceptions, captures.caughtExceptions);
    }

    @Generated
    @Override
    public int hashCode() {
      return Objects.hash(entry, lines, _return, caughtExceptions);
    }

    @Generated
    @Override
    public String toString() {
      return "Captures{"
          + "entry="
          + entry
          + ", lines="
          + lines
          + ", exit="
          + _return
          + ", caughtExceptions="
          + caughtExceptions
          + '}';
    }
  }

  public static class CapturedThread {
    private final long id;
    private final String name;

    public CapturedThread(java.lang.Thread thread) {
      this(thread.getId(), thread.getName());
    }

    public CapturedThread(long id, String name) {
      this.id = id;
      this.name = name;
    }

    public long getId() {
      return id;
    }

    public String getName() {
      return name;
    }

    @Generated
    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      CapturedThread that = (CapturedThread) o;
      return id == that.id && Objects.equals(name, that.name);
    }

    @Generated
    @Override
    public int hashCode() {
      return Objects.hash(id, name);
    }

    @Generated
    @Override
    public String toString() {
      return "CapturedThread{" + "id=" + id + ", name='" + name + '\'' + '}';
    }
  }
}
