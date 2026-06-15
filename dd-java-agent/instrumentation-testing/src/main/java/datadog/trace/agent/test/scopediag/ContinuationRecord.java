package datadog.trace.agent.test.scopediag;

import datadog.trace.api.DDTraceId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * The correlated <em>continuation</em> lifecycle: its capture, every resume (activation), any
 * failed activation, and its terminal resolution (finish/cancel). Scope activation lifetimes are
 * modelled separately by {@link ScopeRecord}; the scopes a continuation spawned are linked here by
 * their seq ids ({@link #scopeRecordSeqs()}).
 *
 * <p>Built incrementally as events arrive on different threads, so all mutating access is
 * synchronized on the instance.
 */
public final class ContinuationRecord {
  public final long seq;
  public final DDTraceId traceId;
  public final long spanId;
  public final String spanName;
  public final byte source;

  /** {@code true} when a resume/resolution was seen without a preceding capture in this window. */
  public final boolean orphan;

  private final ScopeEvent capture;
  private final List<ScopeEvent> resumes = new ArrayList<>(1);
  private final List<ScopeEvent> failedActivations = new ArrayList<>(0);
  private ScopeEvent terminal;
  private final List<ScopeEvent> extraTerminals = new ArrayList<>(0);
  private final List<Long> scopeRecordSeqs = new ArrayList<>(1);

  ContinuationRecord(
      long seq,
      DDTraceId traceId,
      long spanId,
      String spanName,
      byte source,
      boolean orphan,
      ScopeEvent capture) {
    this.seq = seq;
    this.traceId = traceId;
    this.spanId = spanId;
    this.spanName = spanName;
    this.source = source;
    this.orphan = orphan;
    this.capture = capture;
  }

  // ---- mutation ------------------------------------------------------------

  synchronized void addResume(ScopeEvent event) {
    resumes.add(event);
  }

  synchronized void addFailedActivation(ScopeEvent event) {
    failedActivations.add(event);
  }

  /** First terminal sets {@link #terminal}; any subsequent terminal is a double-finish signal. */
  synchronized void setTerminalOrExtra(ScopeEvent event) {
    if (terminal == null) {
      terminal = event;
    } else {
      extraTerminals.add(event);
    }
  }

  synchronized void linkScope(long scopeSeq) {
    scopeRecordSeqs.add(scopeSeq);
  }

  // ---- accessors -----------------------------------------------------------

  public synchronized ScopeEvent capture() {
    return capture;
  }

  public synchronized List<ScopeEvent> resumes() {
    return new ArrayList<>(resumes);
  }

  public synchronized List<ScopeEvent> failedActivations() {
    return new ArrayList<>(failedActivations);
  }

  public synchronized ScopeEvent terminal() {
    return terminal;
  }

  public synchronized List<ScopeEvent> extraTerminals() {
    return new ArrayList<>(extraTerminals);
  }

  public synchronized List<Long> scopeRecordSeqs() {
    return new ArrayList<>(scopeRecordSeqs);
  }

  public synchronized boolean isResumed() {
    return !resumes.isEmpty();
  }

  public synchronized boolean isResolved() {
    return terminal != null;
  }

  // ---- derived -------------------------------------------------------------

  public synchronized ContinuationStatus status() {
    if (terminal != null) {
      return terminal.type == ScopeEvent.Type.RESOLVE_CANCEL
          ? ContinuationStatus.CANCELLED
          : ContinuationStatus.FINISHED;
    }
    return ContinuationStatus.LEAKED;
  }

  /**
   * Derives the failure set for this continuation. {@code rootWrittenNanos} may be {@code null}.
   */
  public synchronized EnumSet<Failure> failures(Long rootWrittenNanos) {
    EnumSet<Failure> failures = EnumSet.noneOf(Failure.class);
    if (terminal == null) {
      failures.add(Failure.LEAKED);
    }
    if (!extraTerminals.isEmpty()) {
      failures.add(Failure.DOUBLE_FINISH);
    }
    if (!failedActivations.isEmpty() || resumedAfterTerminal()) {
      failures.add(Failure.ACTIVATE_AFTER_RESOLVE);
    }
    if (rootWrittenNanos != null
        && (laterThan(terminal, rootWrittenNanos) || laterThan(resumes, rootWrittenNanos))) {
      failures.add(Failure.LATE_FINISH);
    }
    return failures;
  }

  private boolean resumedAfterTerminal() {
    if (terminal == null) {
      return false;
    }
    for (ScopeEvent r : resumes) {
      if (r.nanos > terminal.nanos) {
        return true;
      }
    }
    return false;
  }

  /** {@code true} when capture and any resume/terminal happened on different threads. */
  public synchronized boolean threadHandoff() {
    if (capture == null) {
      return false;
    }
    String captureThread = capture.threadName;
    for (ScopeEvent r : resumes) {
      if (!captureThread.equals(r.threadName)) {
        return true;
      }
    }
    return terminal != null && !captureThread.equals(terminal.threadName);
  }

  /** Nanos between capture and the first resume, or {@code null} if not both observed. */
  public synchronized Long captureToFirstResumeNanos() {
    if (capture == null || resumes.isEmpty()) {
      return null;
    }
    return resumes.get(0).nanos - capture.nanos;
  }

  /** Nanos between capture and the terminal resolution, or {@code null} if not both observed. */
  public synchronized Long ageAtTerminalNanos() {
    if (capture == null || terminal == null) {
      return null;
    }
    return terminal.nanos - capture.nanos;
  }

  /** Earliest known event time for ordering the timeline. */
  public synchronized long firstNanos() {
    long min = capture != null ? capture.nanos : Long.MAX_VALUE;
    for (ScopeEvent e : resumes) {
      min = Math.min(min, e.nanos);
    }
    for (ScopeEvent e : failedActivations) {
      min = Math.min(min, e.nanos);
    }
    if (terminal != null) {
      min = Math.min(min, terminal.nanos);
    }
    for (ScopeEvent e : extraTerminals) {
      min = Math.min(min, e.nanos);
    }
    return min;
  }

  public String sourceName() {
    return ScopeSources.name(source);
  }

  private static boolean laterThan(ScopeEvent event, long nanos) {
    return event != null && event.nanos > nanos;
  }

  private static boolean laterThan(List<ScopeEvent> events, long nanos) {
    for (ScopeEvent e : events) {
      if (e.nanos > nanos) {
        return true;
      }
    }
    return false;
  }
}
