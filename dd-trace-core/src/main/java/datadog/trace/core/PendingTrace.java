package datadog.trace.core;

import datadog.trace.api.DDId;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentTrace;
import datadog.trace.core.monitor.Recording;
import datadog.trace.core.util.Clock;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nonnull;
import lombok.extern.slf4j.Slf4j;

/**
 * This class implements the following data flow rules when a Span is finished:
 *
 * <ul>
 *   <li>Immediate Write
 *       <ul>
 *         <li>pending ref count == 0 && trace not already written
 *         <li>not root span && size exceeds partial flush
 *       </ul>
 *   <li>Delayed Write
 *       <ul>
 *         <li>is root span && pending ref count > 0
 *         <li>not root span && pending ref count > 0 && trace already written
 *       </ul>
 * </ul>
 *
 * Delayed write is handled by PendingTraceBuffer. <br>
 */
@Slf4j
public class PendingTrace implements AgentTrace {

  static class Factory {
    private final CoreTracer tracer;
    private final PendingTraceBuffer pendingTraceBuffer;
    private final boolean strictTraceWrites;

    Factory(CoreTracer tracer, PendingTraceBuffer pendingTraceBuffer, boolean strictTraceWrites) {
      this.tracer = tracer;
      this.pendingTraceBuffer = pendingTraceBuffer;
      this.strictTraceWrites = strictTraceWrites;
    }

    PendingTrace create(@Nonnull DDId traceId) {
      return new PendingTrace(tracer, traceId, pendingTraceBuffer, strictTraceWrites);
    }
  }

  private final CoreTracer tracer;
  private final DDId traceId;
  private final PendingTraceBuffer pendingTraceBuffer;
  private final boolean strictTraceWrites;

  // TODO: consider moving these time fields into DDTracer to ensure that traces have precise
  // relative time
  /** Trace start time in nano seconds measured up to a millisecond accuracy */
  private final long startTimeNano;
  /** Nano second ticks value at trace start */
  private final long startNanoTicks;

  private final ConcurrentLinkedDeque<DDSpan> finishedSpans = new ConcurrentLinkedDeque<>();

  // We must maintain a separate count because ConcurrentLinkedDeque.size() is a linear operation.
  private final AtomicInteger completedSpanCount = new AtomicInteger(0);

  private final AtomicInteger pendingReferenceCount = new AtomicInteger(0);

  /**
   * During a trace there are cases where the root span must be accessed (e.g. priority sampling and
   * trace-search tags). These use cases are an obstacle to span-streaming.
   */
  private volatile DDSpan rootSpan = null;

  private volatile boolean rootSpanWritten = false;

  /**
   * Updated with the latest nanoTicks each time getCurrentTimeNano is called (at the start and
   * finish of each span).
   */
  private volatile long lastReferenced = 0;

  private PendingTrace(
      @Nonnull CoreTracer tracer,
      @Nonnull DDId traceId,
      @Nonnull PendingTraceBuffer pendingTraceBuffer,
      boolean strictTraceWrites) {
    this.tracer = tracer;
    this.traceId = traceId;
    this.pendingTraceBuffer = pendingTraceBuffer;
    this.strictTraceWrites = strictTraceWrites;

    startTimeNano = Clock.currentNanoTime();
    startNanoTicks = Clock.currentNanoTicks();
  }

  CoreTracer getTracer() {
    return tracer;
  }

  /**
   * Current timestamp in nanoseconds.
   *
   * <p>Note: it is not possible to get 'real' nanosecond time. This method uses trace start time
   * (which has millisecond precision) as a reference and it gets time with nanosecond precision
   * after that. This means time measured within same Trace in different Spans is relatively correct
   * with nanosecond precision.
   *
   * @return timestamp in nanoseconds
   */
  public long getCurrentTimeNano() {
    long nanoTicks = Clock.currentNanoTicks();
    lastReferenced = nanoTicks;
    return startTimeNano + Math.max(0, nanoTicks - startNanoTicks);
  }

  public void touch() {
    lastReferenced = Clock.currentNanoTicks();
  }

  public boolean lastReferencedNanosAgo(long nanos) {
    long currentNanoTicks = Clock.currentNanoTicks();
    long age = currentNanoTicks - lastReferenced;
    return nanos < age;
  }

  void registerSpan(final DDSpan span) {
    if (null == rootSpan) {
      synchronized (this) {
        if (!rootSpanWritten && null == rootSpan) {
          rootSpan = span;
        }
      }
    }

    pendingReferenceCount.incrementAndGet();
  }

  void addFinishedSpan(final DDSpan span) {
    finishedSpans.addFirst(span);
    // There is a benign race here where the span added above can get written out by a writer in
    // progress before the count has been incremented. It's being taken care of in the internal
    // write method.
    completedSpanCount.incrementAndGet();
    decrementRefAndMaybeWrite(span == getRootSpan());
  }

  public DDSpan getRootSpan() {
    return rootSpan;
  }

  /** @return Long.MAX_VALUE if no spans finished. */
  long oldestFinishedTime() {
    long oldest = Long.MAX_VALUE;
    for (DDSpan span : finishedSpans) {
      oldest = Math.min(oldest, span.getStartTime() + span.getDurationNano());
    }
    return oldest;
  }

  /**
   * When using continuations, it's possible one may be used after all existing spans are otherwise
   * completed, so we need to wait till continuations are de-referenced before reporting.
   */
  @Override
  public void registerContinuation(final AgentScope.Continuation continuation) {
    pendingReferenceCount.incrementAndGet();
  }

  @Override
  public void cancelContinuation(final AgentScope.Continuation continuation) {
    decrementRefAndMaybeWrite(false);
  }

  private void decrementRefAndMaybeWrite(boolean isRootSpan) {
    final int count = pendingReferenceCount.decrementAndGet();
    if (strictTraceWrites && count < 0) {
      throw new IllegalStateException("Pending reference count " + count + " is negative");
    }
    int partialFlushMinSpans = tracer.getPartialFlushMinSpans();

    if (count == 0 && (strictTraceWrites || !rootSpanWritten)) {
      // Finished with no pending work ... write immediately
      write();
    } else if (isRootSpan) {
      // Finished root with pending work ... delay write
      pendingTraceBuffer.enqueue(this);
    } else if (0 < partialFlushMinSpans && partialFlushMinSpans < size()) {
      // Trace is getting too big, write anything completed.
      partialFlush();
    } else if (rootSpanWritten) {
      // Late arrival span ... delay write
      pendingTraceBuffer.enqueue(this);
    }
  }

  /** Important to note: may be called multiple times. */
  private void partialFlush() {
    int size = write(true);
    if (log.isDebugEnabled()) {
      log.debug("t_id={} -> wrote partial trace of size {}", traceId, size);
    }
  }

  /** Important to note: may be called multiple times. */
  void write() {
    write(false);
  }

  private int write(boolean isPartial) {
    if (!finishedSpans.isEmpty()) {
      try (Recording recording = tracer.writeTimer()) {
        // Only one writer at a time
        synchronized (this) {
          if (!isPartial) {
            rootSpanWritten = true;
          }
          int size = size();
          // If we get here and size is below 0, then the writer before us wrote out at least one
          // more trace than the size it had when it started. Those span(s) had been added to
          // finishedSpans by some other thread(s) while the existing spans were being written, but
          // the completedSpanCount has not yet been incremented. This means that eventually the
          // count(s) will be incremented, and any new spans added during the period that the count
          // was negative will be written by someone even if we don't write them right now.
          if (size > 0 && (!isPartial || size > tracer.getPartialFlushMinSpans())) {
            List<DDSpan> trace = new ArrayList<>(size);
            final Iterator<DDSpan> it = finishedSpans.iterator();
            int i = 0;
            while (it.hasNext()) {
              final DDSpan span = it.next();
              trace.add(span);
              completedSpanCount.decrementAndGet();
              it.remove();
              i++;
            }
            tracer.write(trace);
            return i;
          }
        }
      }
    }
    return 0;
  }

  public int size() {
    return completedSpanCount.get();
  }
}
