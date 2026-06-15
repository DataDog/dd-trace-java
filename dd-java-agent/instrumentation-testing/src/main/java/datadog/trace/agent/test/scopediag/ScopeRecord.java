package datadog.trace.agent.test.scopediag;

import datadog.trace.api.DDTraceId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * The <em>scope</em> activation lifecycle: a scope opened (first activation) → closed (popped from
 * its thread's stack). Distinct from the continuation lifecycle ({@link ContinuationRecord}); when
 * a scope was spawned by resuming a continuation, {@link #continuationSeq} links back to that
 * continuation's {@link ContinuationRecord#seq}.
 */
public final class ScopeRecord {
  public final long seq;
  public final DDTraceId traceId;
  public final long spanId;
  public final String spanName;
  public final byte source;

  /**
   * The seq of the continuation that spawned this scope, or {@code null} for a plain activation.
   */
  public final Long continuationSeq;

  private final ScopeEvent open;
  private ScopeEvent close;
  private final List<ScopeEvent> wrongThreadCloses = new ArrayList<>(0);

  ScopeRecord(
      long seq,
      DDTraceId traceId,
      long spanId,
      String spanName,
      byte source,
      Long continuationSeq,
      ScopeEvent open) {
    this.seq = seq;
    this.traceId = traceId;
    this.spanId = spanId;
    this.spanName = spanName;
    this.source = source;
    this.continuationSeq = continuationSeq;
    this.open = open;
  }

  // ---- mutation ------------------------------------------------------------

  synchronized void setClose(ScopeEvent event) {
    if (close == null) {
      close = event;
    }
  }

  synchronized void addWrongThreadClose(ScopeEvent event) {
    wrongThreadCloses.add(event);
  }

  // ---- accessors -----------------------------------------------------------

  public synchronized ScopeEvent open() {
    return open;
  }

  public synchronized ScopeEvent close() {
    return close;
  }

  public synchronized List<ScopeEvent> wrongThreadCloses() {
    return new ArrayList<>(wrongThreadCloses);
  }

  public synchronized boolean closed() {
    return close != null;
  }

  // ---- derived -------------------------------------------------------------

  /** {@code true} when the scope was opened and closed on different threads. */
  public synchronized boolean threadHandoff() {
    return open != null && close != null && !open.threadName.equals(close.threadName);
  }

  /** Nanos the scope was active, or {@code null} if it was never closed. */
  public synchronized Long activeDurationNanos() {
    if (open == null || close == null) {
      return null;
    }
    return close.nanos - open.nanos;
  }

  public synchronized EnumSet<Failure> failures() {
    EnumSet<Failure> failures = EnumSet.noneOf(Failure.class);
    if (open != null && close == null) {
      failures.add(Failure.NEVER_CLOSED);
    }
    if (!wrongThreadCloses.isEmpty()) {
      failures.add(Failure.CLOSE_WRONG_THREAD);
    }
    return failures;
  }

  public synchronized long firstNanos() {
    long min = open != null ? open.nanos : Long.MAX_VALUE;
    if (close != null) {
      min = Math.min(min, close.nanos);
    }
    for (ScopeEvent e : wrongThreadCloses) {
      min = Math.min(min, e.nanos);
    }
    return min;
  }

  public String sourceName() {
    return ScopeSources.name(source);
  }
}
