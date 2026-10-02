package datadog.trace.agent.test.scopediag;

import datadog.trace.api.DDTraceId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/** Records a scope's activation and close events. */
public final class ScopeRecord {
  public final long seq;
  public final DDTraceId traceId;
  public final long spanId;
  public final String spanName;
  public final byte source;

  /** The continuation that spawned this scope, or {@code null} for a plain activation. */
  public final Long continuationSeq;

  private final ScopeEvent open;
  private ScopeEvent close;
  private boolean deferredCleanup;
  private final List<ScopeEvent> wrongThreadCloses = new ArrayList<>(0);
  private final List<ScopeEvent> outOfOrderCloses = new ArrayList<>(0);

  ScopeRecord(
      long seq,
      DDTraceId traceId,
      long spanId,
      String spanName,
      byte source,
      Long continuationSeq,
      boolean deferredCleanup,
      ScopeEvent open) {
    this.seq = seq;
    this.traceId = traceId;
    this.spanId = spanId;
    this.spanName = spanName;
    this.source = source;
    this.continuationSeq = continuationSeq;
    this.deferredCleanup = deferredCleanup;
    this.open = open;
  }

  synchronized void setClose(ScopeEvent event) {
    if (close == null) {
      close = event;
    }
  }

  synchronized void addWrongThreadClose(ScopeEvent event) {
    wrongThreadCloses.add(event);
  }

  synchronized void addOutOfOrderClose(ScopeEvent event) {
    outOfOrderCloses.add(event);
  }

  synchronized ScopeRecord snapshot() {
    ScopeRecord copy =
        new ScopeRecord(
            seq,
            traceId,
            spanId,
            spanName,
            source,
            continuationSeq,
            deferredCleanup,
            open == null ? null : open.snapshot());
    if (close != null) {
      copy.setClose(close.snapshot());
    }
    for (ScopeEvent event : wrongThreadCloses) {
      copy.wrongThreadCloses.add(event.snapshot());
    }
    for (ScopeEvent event : outOfOrderCloses) {
      copy.outOfOrderCloses.add(event.snapshot());
    }
    return copy;
  }

  public synchronized ScopeEvent open() {
    return open;
  }

  public synchronized ScopeEvent close() {
    return close;
  }

  public synchronized List<ScopeEvent> wrongThreadCloses() {
    return new ArrayList<>(wrongThreadCloses);
  }

  public synchronized List<ScopeEvent> outOfOrderCloses() {
    return new ArrayList<>(outOfOrderCloses);
  }

  public synchronized boolean closed() {
    return close != null;
  }

  synchronized void markDeferredCleanup() {
    deferredCleanup = true;
  }

  public synchronized boolean deferredCleanup() {
    return deferredCleanup;
  }

  /** {@code true} when the scope was opened and closed on different threads. */
  public synchronized boolean threadHandoff() {
    return open != null && close != null && open.threadId != close.threadId;
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
    if (open != null && close == null && !deferredCleanup) {
      failures.add(Failure.NEVER_CLOSED);
    }
    if (!wrongThreadCloses.isEmpty()) {
      failures.add(Failure.CLOSE_WRONG_THREAD);
    }
    if (!outOfOrderCloses.isEmpty()) {
      failures.add(Failure.CLOSE_OUT_OF_ORDER);
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
    for (ScopeEvent e : outOfOrderCloses) {
      min = Math.min(min, e.nanos);
    }
    return min;
  }

  public String sourceName() {
    return ScopeSources.name(source);
  }
}
