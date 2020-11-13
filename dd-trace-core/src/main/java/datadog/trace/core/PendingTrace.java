package datadog.trace.core;

import datadog.trace.api.DDId;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentTrace;
import datadog.trace.core.monitor.Recording;
import datadog.trace.core.util.Clock;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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

    Factory(CoreTracer tracer, PendingTraceBuffer pendingTraceBuffer) {
      this.tracer = tracer;
      this.pendingTraceBuffer = pendingTraceBuffer;
    }

    PendingTrace create(final DDId traceId) {
      return new PendingTrace(tracer, traceId, pendingTraceBuffer);
    }
  }

  private final CoreTracer tracer;
  private final DDId traceId;
  private final PendingTraceBuffer pendingTraceBuffer;

  // TODO: consider moving these time fields into DDTracer to ensure that traces have precise
  // relative time
  /** Trace start time in nano seconds measured up to a millisecond accuracy */
  private final long startTimeNano;
  /** Nano second ticks value at trace start */
  private final long startNanoTicks;

  private final ConcurrentLinkedDeque<DDSpan> finishedSpans = new ConcurrentLinkedDeque();

  // We must maintain a separate count because ConcurrentLinkedDeque.size() is a linear operation.
  private final AtomicInteger completedSpanCount = new AtomicInteger(0);

  private final AtomicInteger pendingReferenceCount = new AtomicInteger(0);

  // FIXME: In async frameworks we may have situations where traces do not report due to references
  //  being held by async operators. In order to support testing in these cases we should have a way
  //  to keep track of the fact that this trace is ready to report but is still pending. This would
  //  likely require a change to the writer interface to allow signaling this intent. This could
  //  also give us the benefit of being able to recover for reporting traces that get stuck due to
  //  references being held for long periods of time.

  /**
   * During a trace there are cases where the root span must be accessed (e.g. priority sampling and
   * trace-search tags).
   *
   * <p>Use a weak ref because we still need to handle buggy cases where the root span is not
   * correctly closed (see SpanCleaner).
   *
   * <p>The root span will be available in non-buggy cases because it has either finished and
   * strongly ref'd in this queue or is unfinished and ref'd in a ContinuableScope.
   */
  private final AtomicReference<WeakReference<DDSpan>> rootSpan = new AtomicReference<>();

  private final AtomicBoolean rootSpanWritten = new AtomicBoolean(false);

  // FIXME: This can be removed when we change behavior for gc'd spans.
  private final AtomicBoolean traceValid = new AtomicBoolean(true);

  /**
   * Updated with the latest nanoTicks each time getCurrentTimeNano is called (at the start and
   * finish of each span).
   */
  private volatile long lastReferenced = 0;

  private PendingTrace(
      final CoreTracer tracer, final DDId traceId, PendingTraceBuffer pendingTraceBuffer) {
    this.tracer = tracer;
    this.traceId = traceId;
    this.pendingTraceBuffer = pendingTraceBuffer;

    startTimeNano = Clock.currentNanoTime();
    startNanoTicks = Clock.currentNanoTicks();
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

  public void registerSpan(final DDSpan span) {
    if (traceId == null || span.context() == null) {
      log.error(
          "Failed to register span ({}) due to null PendingTrace traceId or null span context",
          span);
      return;
    }
    if (!traceId.equals(span.context().getTraceId())) {
      log.debug("t_id={} -> registered for wrong trace {}", traceId, span);
      return;
    }

    if (!rootSpanWritten.get()) {
      rootSpan.compareAndSet(null, new WeakReference<>(span));
    }

    final int count = pendingReferenceCount.incrementAndGet();
    if (log.isDebugEnabled()) {
      log.debug("t_id={} -> registered span {}. count = {}", traceId, span, count);
    }
  }

  public void addFinishedSpan(final DDSpan span) {
    if (span.getDurationNano() == 0) {
      log.debug("t_id={} -> added to trace, but not complete: {}", traceId, span);
      return;
    }
    if (traceId == null || span.context() == null) {
      log.error(
          "Failed to add span ({}) due to null PendingTrace traceId or null span context", span);
      return;
    }
    if (!traceId.equals(span.getTraceId())) {
      log.debug("t_id={} -> span expired for wrong trace {}", traceId, span);
      return;
    }
    if (traceId == null || span.context() == null) {
      log.error(
          "Failed to expire span ({}) due to null PendingTrace traceId or null span context", span);
      return;
    }

    finishedSpans.addFirst(span);
    completedSpanCount.incrementAndGet();
    decrementRefAndMaybeWrite(span == getRootSpan());
  }

  public DDSpan getRootSpan() {
    final WeakReference<DDSpan> rootRef = rootSpan.get();
    return rootRef == null ? null : rootRef.get();
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
    final int count = pendingReferenceCount.incrementAndGet();
    if (log.isDebugEnabled()) {
      log.debug(
          "t_id={} -> registered continuation {} -- count = {}", traceId, continuation, count);
    }
  }

  @Override
  public void cancelContinuation(final AgentScope.Continuation continuation) {
    decrementRefAndMaybeWrite(false);
  }

  private void decrementRefAndMaybeWrite(boolean isRootSpan) {
    if (!traceValid.get()) {
      return;
    }
    final int count = pendingReferenceCount.decrementAndGet();
    if (count == 0 && !rootSpanWritten.get()) {
      // Finished with no pending work ... write immediately
      write();
    } else {
      if (isRootSpan) {
        // Finished root with pending work ... delay write
        pendingTraceBuffer.enqueue(this);
      } else {
        int partialFlushMinSpans = tracer.getPartialFlushMinSpans();
        if (0 < partialFlushMinSpans && partialFlushMinSpans < size()) {
          // Trace is getting too big, write anything completed.
          partialFlush();
        } else if (rootSpanWritten.get()) {
          // Late arrival span ... delay write
          pendingTraceBuffer.enqueue(this);
        }
      }
    }
    if (log.isDebugEnabled()) {
      log.debug("t_id={} -> expired reference. pending count={}", traceId, count);
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
    rootSpanWritten.set(true);
    int size = write(false);
    if (log.isDebugEnabled()) {
      log.debug("t_id={} -> wrote {} spans to {}.", traceId, size, tracer.writer);
    }
  }

  private int write(boolean isPartial) {
    if (!finishedSpans.isEmpty()) {
      try (Recording recording = tracer.writeTimer()) {
        synchronized (this) {
          int size = size();
          if (!isPartial || size > tracer.getPartialFlushMinSpans()) {
            List<DDSpan> trace = new ArrayList<>(size);

            final Iterator<DDSpan> it = finishedSpans.iterator();
            while (it.hasNext()) {
              final DDSpan span = it.next();
              trace.add(span);
              completedSpanCount.decrementAndGet();
              it.remove();
            }
            tracer.write(trace);
            return size;
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
