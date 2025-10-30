package datadog.trace.core;

import datadog.communication.monitor.Recording;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.time.TimeSource;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.core.CoreTracer.ConfigSnapshot;
import datadog.trace.core.monitor.HealthMetrics;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
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
 * Delayed write is handled by PendingTraceBuffer.
 *
 * <p>When the long-running traces feature is enabled, periodic writes are triggered by the
 * PendingTraceBuffer in addition to the other write conditions. Running spans are also written in
 * that case. <br>
 */
public class PendingTrace extends TraceCollector implements PendingTraceBuffer.Element {

  private static final Logger log = LoggerFactory.getLogger(PendingTrace.class);

  static class Factory implements TraceCollector.Factory {
    private final CoreTracer tracer;
    private final PendingTraceBuffer pendingTraceBuffer;
    private final TimeSource timeSource;
    private final boolean strictTraceWrites;
    private final HealthMetrics healthMetrics;

    Factory(
        CoreTracer tracer,
        PendingTraceBuffer pendingTraceBuffer,
        TimeSource timeSource,
        boolean strictTraceWrites,
        HealthMetrics healthMetrics) {
      this.tracer = tracer;
      this.pendingTraceBuffer = pendingTraceBuffer;
      this.timeSource = timeSource;
      this.strictTraceWrites = strictTraceWrites;
      this.healthMetrics = healthMetrics;
    }

    @Override
    public PendingTrace create(@Nonnull DDTraceId traceId) {
      return create(traceId, null);
    }

    @Override
    public PendingTrace create(@Nonnull DDTraceId traceId, ConfigSnapshot traceConfig) {
      return new PendingTrace(
          tracer,
          traceId,
          pendingTraceBuffer,
          timeSource,
          traceConfig,
          strictTraceWrites,
          healthMetrics);
    }
  }

  private static final List<DDSpan> EMPTY = new ArrayList<>(0);

  private final DDTraceId traceId;
  private final PendingTraceBuffer pendingTraceBuffer;
  private final boolean strictTraceWrites;
  private final HealthMetrics healthMetrics;

  /**
   * Contains finished spans. If the long-running trace feature is enabled it also contains running
   * spans that can be written.
   */
  private final ConcurrentLinkedDeque<DDSpan> spans;

  private volatile int completedSpanCount = 0;
  private static final AtomicIntegerFieldUpdater<PendingTrace> COMPLETED_SPAN_COUNT =
      AtomicIntegerFieldUpdater.newUpdater(PendingTrace.class, "completedSpanCount");

  private volatile int pendingReferenceCount = 0;
  private static final AtomicIntegerFieldUpdater<PendingTrace> PENDING_REFERENCE_COUNT =
      AtomicIntegerFieldUpdater.newUpdater(PendingTrace.class, "pendingReferenceCount");

  private volatile int isEnqueued = 0;
  private static final AtomicIntegerFieldUpdater<PendingTrace> IS_ENQUEUED =
      AtomicIntegerFieldUpdater.newUpdater(PendingTrace.class, "isEnqueued");

  private volatile int longRunningTrackedState = LongRunningTracesTracker.UNDEFINED;
  private static final AtomicIntegerFieldUpdater<PendingTrace> LONG_RUNNING_STATE =
      AtomicIntegerFieldUpdater.newUpdater(PendingTrace.class, "longRunningTrackedState");

  private volatile long runningTraceStartTimeNano = 0;
  private static final AtomicLongFieldUpdater<PendingTrace> RUNNING_TRACE_START_TIME_NANO =
      AtomicLongFieldUpdater.newUpdater(PendingTrace.class, "runningTraceStartTimeNano");
  private volatile long lastWriteTimeNano = 0;
  private static final AtomicLongFieldUpdater<PendingTrace> LAST_WRITE_TIME_NANO =
      AtomicLongFieldUpdater.newUpdater(PendingTrace.class, "lastWriteTimeNano");

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
      @Nonnull DDTraceId traceId,
      @Nonnull PendingTraceBuffer pendingTraceBuffer,
      @Nonnull TimeSource timeSource,
      ConfigSnapshot traceConfig,
      boolean strictTraceWrites,
      HealthMetrics healthMetrics) {
    super(tracer, traceConfig != null ? traceConfig : tracer.captureTraceConfig(), timeSource);
    this.traceId = traceId;
    this.pendingTraceBuffer = pendingTraceBuffer;
    this.strictTraceWrites = strictTraceWrites;
    this.healthMetrics = healthMetrics;
    this.spans = new ConcurrentLinkedDeque<>();
  }

  /**
   * Current timestamp in nanoseconds; 'touches' the trace by updating {@link #lastReferenced}.
   *
   * <p>Note: This method uses trace start time as a reference and it gets time with nanosecond
   * precision after that. This means time measured within same Trace in different Spans is
   * relatively correct with nanosecond precision.
   *
   * @return timestamp in nanoseconds
   */
  @Override
  public long getCurrentTimeNano() {
    long nanoTicks = timeSource.getNanoTicks();
    lastReferenced = nanoTicks;
    return tracer.getTimeWithNanoTicks(nanoTicks);
  }

  @Override
  void touch() {
    lastReferenced = timeSource.getNanoTicks();
  }

  @Override
  public boolean lastReferencedNanosAgo(long nanos) {
    long currentNanoTicks = timeSource.getNanoTicks();
    long age = currentNanoTicks - lastReferenced;
    return nanos < age;
  }

  @Override
  void registerSpan(final DDSpan span) {
    ROOT_SPAN.compareAndSet(this, null, span);
    PENDING_REFERENCE_COUNT.incrementAndGet(this);
    healthMetrics.onCreateSpan();
    if (pendingTraceBuffer.longRunningSpansEnabled()) {
      spans.addFirst(span);
      trackRunningTrace(span);
    }
  }

  private void trackRunningTrace(final DDSpan span) {
    if (!compareAndSetLongRunningState(
        LongRunningTracesTracker.UNDEFINED, LongRunningTracesTracker.TO_TRACK)) {
      return;
    }
    RUNNING_TRACE_START_TIME_NANO.set(this, span.getStartTime());
    // If the pendingTraceBuffer is full, this trace won't be tracked by the
    // LongRunningTracesTracker.
    pendingTraceBuffer.enqueue(this);
  }

  Integer evaluateSamplingPriority() {
    DDSpan span = spans.peek();
    if (span == null) {
      return null;
    }
    Integer prio = span.getSamplingPriority();
    if (prio == null) {
      prio = span.forceSamplingDecision();
    }
    return prio;
  }

  boolean compareAndSetLongRunningState(int expected, int newState) {
    return LONG_RUNNING_STATE.compareAndSet(this, expected, newState);
  }

  boolean empty() {
    return 0 >= COMPLETED_SPAN_COUNT.get(this) + PENDING_REFERENCE_COUNT.get(this);
  }

  PublishState onPublish(final DDSpan span) {
    if (!pendingTraceBuffer.longRunningSpansEnabled()) {
      spans.addFirst(span);
    }
    // There is a benign race here where the span added above can get written out by a writer in
    // progress before the count has been incremented. It's being taken care of in the internal
    // write method.
    healthMetrics.onFinishSpan();
    COMPLETED_SPAN_COUNT.incrementAndGet(this);
    final DDSpan rootSpan = getRootSpan();
    if (span == rootSpan) {
      tracer.onRootSpanPublished(rootSpan);
    }
    return decrementRefAndMaybeWrite(span == rootSpan);
  }

  @Override
  public DDSpan getRootSpan() {
    return rootSpan;
  }

  /**
   * @return Long.MAX_VALUE if no spans finished.
   */
  @Override
  public long oldestFinishedTime() {
    long oldest = Long.MAX_VALUE;
    for (DDSpan span : spans) {
      if (span.isFinished()) {
        oldest = Math.min(oldest, span.getStartTime() + span.getDurationNano());
      }
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
  public void removeContinuation(final AgentScope.Continuation continuation) {
    decrementRefAndMaybeWrite(false);
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
    } else if (partialFlushMinSpans > 0 && size() >= partialFlushMinSpans) {
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
    healthMetrics.onPartialFlush(size);
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
    if (!spans.isEmpty()) {
      try (Recording recording = tracer.writeTimer()) {
        // Only one writer at a time
        final List<DDSpan> trace;
        int completedSpans = 0;
        synchronized (this) {
          if (!isPartial) {
            rootSpanWritten = true;
          }
          int size = size();
          boolean writeRunningSpans =
              LongRunningTracesTracker.WRITE_RUNNING_SPANS == LONG_RUNNING_STATE.get(this);
          if (writeRunningSpans) {
            size += pendingReferenceCount;
          }
          // If we get here and size is below 0, then the writer before us wrote out at least one
          // more trace than the size it had when it started. Those span(s) had been added to
          // finishedSpans by some other thread(s) while the existing spans were being written, but
          // the completedSpanCount has not yet been incremented. This means that eventually the
          // count(s) will be incremented, and any new spans added during the period that the count
          // was negative will be written by someone even if we don't write them right now.
          if (size > 0 && (!isPartial || size >= tracer.getPartialFlushMinSpans())) {
            trace = new ArrayList<>(size);
            completedSpans = enqueueSpansToWrite(trace, writeRunningSpans);
          } else {
            trace = EMPTY;
          }
        }
        if (!trace.isEmpty()) {
          COMPLETED_SPAN_COUNT.addAndGet(this, -completedSpans);
          tracer.write(trace);
          healthMetrics.onCreateTrace();
          return completedSpans;
        }
      }
    }
    return 0;
  }

  int enqueueSpansToWrite(List<DDSpan> trace, boolean writeRunningSpans) {
    int completedSpans = 0;
    boolean runningSpanSeen = false;
    long firstRunningSpanID = 0;
    long nowNano = 0;
    if (writeRunningSpans) {
      nowNano = getCurrentTimeNano();
      setLastWriteTime(nowNano);
    }

    DDSpan span = spans.pollFirst();
    while (null != span) {
      if (runningSpanSeen && span.getSpanId() == firstRunningSpanID) {
        // we iterated on all spans as we circled back to the first running span
        spans.addFirst(span);
        break;
      }
      if (span.isFinished()) {
        trace.add(span);
        completedSpans++;
      } else {
        // keep the running span in the list
        spans.add(span);
        if (!runningSpanSeen) {
          runningSpanSeen = true;
          firstRunningSpanID = span.getSpanId();
        }
        if (writeRunningSpans) {
          span.setLongRunningVersion(
              (int) TimeUnit.NANOSECONDS.toMillis(nowNano - span.getStartTime()));
          trace.add(span);
        }
      }
      span = spans.pollFirst();
    }
    return completedSpans;
  }

  public int size() {
    return completedSpanCount;
  }

  long getLastWriteTime() {
    return LAST_WRITE_TIME_NANO.get(this);
  }

  long getRunningTraceStartTime() {
    return RUNNING_TRACE_START_TIME_NANO.get(this);
  }

  private void setLastWriteTime(long now) {
    LAST_WRITE_TIME_NANO.set(this, now);
  }

  @Override
  public boolean setEnqueued(boolean enqueued) {
    int expected = enqueued ? 0 : 1;
    return IS_ENQUEUED.compareAndSet(this, expected, 1 - expected);
  }

  /**
   * Called when the pendingTraceBuffer is full and a pendingTrace is offered.
   *
   * <p>If the pendingTrace is not sent to the LongRunningTracesTracker, it will be immediately
   * written. Otherwise, the pendingTrace won't be tracked and no write is triggered.
   */
  @Override
  public boolean writeOnBufferFull() {
    return !compareAndSetLongRunningState(
        LongRunningTracesTracker.TO_TRACK, LongRunningTracesTracker.NOT_TRACKED);
  }

  /**
   * Calculates the duration of a span in nanoseconds for the transport layer
   *
   * <p>As the internal duration of a running span is 0, the duration is set to the difference
   * between the span's start time and the time of the write trigger.
   */
  public static long getDurationNano(CoreSpan<?> span) {
    long duration = span.getDurationNano();
    if (duration > 0) {
      return duration;
    }
    if (!(span instanceof DDSpan)) {
      return duration;
    }
    DDSpan ddSpan = (DDSpan) span;
    TraceCollector traceCollector = ddSpan.context().getTraceCollector();
    if (!(traceCollector instanceof PendingTrace)) {
      throw new IllegalArgumentException(
          "Expected "
              + PendingTrace.class.getName()
              + ", got "
              + traceCollector.getClass().getName());
    }
    PendingTrace trace = (PendingTrace) traceCollector;
    return trace.getLastWriteTime() - span.getStartTime();
  }

  Collection<DDSpan> getSpans() {
    return spans;
  }
}
