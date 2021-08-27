package datadog.trace.core;

import datadog.communication.monitor.Recording;
import datadog.trace.api.DDId;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentTrace;
import datadog.trace.core.util.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
public class PendingTrace implements AgentTrace, PendingTraceBuffer.Element {

  private static final Logger log = LoggerFactory.getLogger(PendingTrace.class);

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

  private static final List<DDSpan> EMPTY = new ArrayList<>(0);

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
  private volatile int completedSpanCount = 0;
  private static final AtomicIntegerFieldUpdater<PendingTrace> COMPLETED_SPAN_COUNT =
      AtomicIntegerFieldUpdater.newUpdater(PendingTrace.class, "completedSpanCount");

  private volatile int pendingReferenceCount = 0;
  private static final AtomicIntegerFieldUpdater<PendingTrace> PENDING_REFERENCE_COUNT =
      AtomicIntegerFieldUpdater.newUpdater(PendingTrace.class, "pendingReferenceCount");

  /**
   * During a trace there are cases where the root span must be accessed (e.g. priority sampling and
   * trace-search tags). These use cases are an obstacle to span-streaming.
   */
  private volatile DDSpan rootSpan = null;

  private static final AtomicReferenceFieldUpdater<PendingTrace, DDSpan> ROOT_SPAN =
      AtomicReferenceFieldUpdater.newUpdater(PendingTrace.class, DDSpan.class, "rootSpan");

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

  @Override
  public boolean lastReferencedNanosAgo(long nanos) {
    long currentNanoTicks = Clock.currentNanoTicks();
    long age = currentNanoTicks - lastReferenced;
    return nanos < age;
  }

  void registerSpan(final DDSpan span) {
    ROOT_SPAN.compareAndSet(this, null, span);
    PENDING_REFERENCE_COUNT.incrementAndGet(this);
    tracer.onStart(span);
  }

  void onFinish(final DDSpan span) {
    tracer.onFinish(span);
  }

  PublishState onPublish(final DDSpan span) {
    finishedSpans.addFirst(span);
    // There is a benign race here where the span added above can get written out by a writer in
    // progress before the count has been incremented. It's being taken care of in the internal
    // write method.
    COMPLETED_SPAN_COUNT.incrementAndGet(this);
    return decrementRefAndMaybeWrite(span == getRootSpan());
  }

  @Override
  public DDSpan getRootSpan() {
    return rootSpan;
  }

  /** @return Long.MAX_VALUE if no spans finished. */
  @Override
  public long oldestFinishedTime() {
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
    PENDING_REFERENCE_COUNT.incrementAndGet(this);
  }

  @Override
  public void cancelContinuation(final AgentScope.Continuation continuation) {
    decrementRefAndMaybeWrite(false);
  }

  enum PublishState {
    WRITTEN,
    PARTIAL_FLUSH,
    ROOT_BUFFERED,
    BUFFERED,
    PENDING
  }

  private PublishState decrementRefAndMaybeWrite(boolean isRootSpan) {
    final int count = PENDING_REFERENCE_COUNT.decrementAndGet(this);
    if (strictTraceWrites && count < 0) {
      throw new IllegalStateException("Pending reference count " + count + " is negative");
    }
    int partialFlushMinSpans = tracer.getPartialFlushMinSpans();

    if (count == 0 && (strictTraceWrites || !rootSpanWritten)) {
      // Finished with no pending work ... write immediately
      write();
      return PublishState.WRITTEN;
    } else if (isRootSpan) {
      // Finished root with pending work ... delay write
      pendingTraceBuffer.enqueue(this);
      return PublishState.ROOT_BUFFERED;
    } else if (0 < partialFlushMinSpans && partialFlushMinSpans < size()) {
      // Trace is getting too big, write anything completed.
      partialFlush();
      return PublishState.PARTIAL_FLUSH;
    } else if (rootSpanWritten) {
      // Late arrival span ... delay write
      pendingTraceBuffer.enqueue(this);
      return PublishState.BUFFERED;
    }
    return PublishState.PENDING;
  }

  /** Important to note: may be called multiple times. */
  private void partialFlush() {
    int size = write(true);
    if (log.isDebugEnabled()) {
      log.debug("t_id={} -> wrote partial trace of size {}", traceId, size);
    }
  }

  /** Important to note: may be called multiple times. */
  @Override
  public void write() {
    write(false);
  }

  private int write(boolean isPartial) {
    if (!finishedSpans.isEmpty()) {
      try (Recording recording = tracer.writeTimer()) {
        // Only one writer at a time
        final List<DDSpan> trace;
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
            trace = new ArrayList<>(size);
            DDSpan span = finishedSpans.pollFirst();
            while (null != span) {
              trace.add(span);
              span = finishedSpans.pollFirst();
            }
          } else {
            trace = EMPTY;
          }
        }
        if (!trace.isEmpty()) {
          COMPLETED_SPAN_COUNT.addAndGet(this, -trace.size());
          tracer.write(trace);
          return trace.size();
        }
      }
    }
    return 0;
  }

  public int size() {
    return completedSpanCount;
  }
}
