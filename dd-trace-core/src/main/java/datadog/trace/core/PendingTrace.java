package datadog.trace.core;

import datadog.common.exec.AgentTaskScheduler;
import datadog.common.exec.AgentTaskScheduler.Task;
import datadog.trace.api.DDId;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentTrace;
import datadog.trace.core.monitor.Recording;
import datadog.trace.core.util.Clock;
import java.io.Closeable;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class PendingTrace extends ConcurrentLinkedDeque<DDSpan> implements AgentTrace {

  static PendingTrace create(final CoreTracer tracer, final DDId traceId) {
    final PendingTrace pendingTrace = new PendingTrace(tracer, traceId);
    pendingTrace.addPendingTrace();
    return pendingTrace;
  }

  private static final AtomicReference<SpanCleaner> SPAN_CLEANER = new AtomicReference<>();

  private final CoreTracer tracer;
  private final DDId traceId;

  // TODO: consider moving these time fields into DDTracer to ensure that traces have precise
  // relative time
  /** Trace start time in nano seconds measured up to a millisecond accuracy */
  private final long startTimeNano;
  /** Nano second ticks value at trace start */
  private final long startNanoTicks;

  private final ReferenceQueue spanReferenceQueue = new ReferenceQueue();
  private final Set<WeakReference<DDSpan>> weakSpans =
      Collections.newSetFromMap(new ConcurrentHashMap<WeakReference<DDSpan>, Boolean>());
  private final ReferenceQueue continuationReferenceQueue = new ReferenceQueue();
  private final Set<WeakReference<AgentScope.Continuation>> weakContinuations =
      Collections.newSetFromMap(
          new ConcurrentHashMap<WeakReference<AgentScope.Continuation>, Boolean>());

  private final AtomicInteger pendingReferenceCount = new AtomicInteger(0);

  // We must maintain a separate count because ConcurrentLinkedDeque.size() is a linear operation.
  private final AtomicInteger completedSpanCount = new AtomicInteger(0);

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

  /** Ensure a trace is never written multiple times */
  private final AtomicBoolean isWritten = new AtomicBoolean(false);

  private PendingTrace(final CoreTracer tracer, final DDId traceId) {
    this.tracer = tracer;
    this.traceId = traceId;

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
    return startTimeNano + Math.max(0, Clock.currentNanoTicks() - startNanoTicks);
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
    rootSpan.compareAndSet(null, new WeakReference<>(span));
    synchronized (span) {
      if (null == span.ref) {
        span.ref = new WeakReference<DDSpan>(span, spanReferenceQueue);
        weakSpans.add(span.ref);
        final int count = pendingReferenceCount.incrementAndGet();
        if (log.isDebugEnabled()) {
          log.debug("t_id={} -> registered span {}. count = {}", traceId, span, count);
        }
      } else {
        log.debug("t_id={} -> span already registered {}", traceId, span);
      }
    }
  }

  private void expireSpan(final DDSpan span) {
    if (traceId == null || span.context() == null) {
      log.error(
          "Failed to expire span ({}) due to null PendingTrace traceId or null span context", span);
      return;
    }
    if (!traceId.equals(span.context().getTraceId())) {
      log.debug("t_id={} -> span expired for wrong trace {}", traceId, span);
      return;
    }
    synchronized (span) {
      if (null == span.ref) {
        log.debug("t_id={} -> not registered in trace: {}", traceId, span);
      } else {
        weakSpans.remove(span.ref);
        span.ref.clear();
        span.ref = null;
        expireReference();
      }
    }
  }

  public void addSpan(final DDSpan span) {
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
      log.debug("t_id={} -> added to a mismatched trace: {}", traceId, span);
      return;
    }

    if (!isWritten.get()) {
      addFirst(span);
    } else {
      log.debug("t_id={} -> finished after trace reported: {}", traceId, span);
    }
    expireSpan(span);
  }

  public DDSpan getRootSpan() {
    final WeakReference<DDSpan> rootRef = rootSpan.get();
    return rootRef == null ? null : rootRef.get();
  }

  /**
   * When using continuations, it's possible one may be used after all existing spans are otherwise
   * completed, so we need to wait till continuations are de-referenced before reporting.
   */
  @Override
  public void registerContinuation(final AgentScope.Continuation continuation) {
    synchronized (continuation) {
      if (!continuation.isRegistered()) {
        weakContinuations.add(continuation.register(continuationReferenceQueue));
        final int count = pendingReferenceCount.incrementAndGet();
        if (log.isDebugEnabled()) {
          log.debug(
              "t_id={} -> registered continuation {} -- count = {}", traceId, continuation, count);
        }
      } else {
        log.debug("continuation {} already registered in trace {}", continuation, traceId);
      }
    }
  }

  @Override
  public void cancelContinuation(final AgentScope.Continuation continuation) {
    synchronized (continuation) {
      if (continuation.isRegistered()) {
        continuation.cancel(weakContinuations);
        expireReference();
      } else {
        log.debug("t_id={} -> not registered in trace: {}", traceId, continuation);
      }
    }
  }

  private void expireReference() {
    final int count = pendingReferenceCount.decrementAndGet();
    if (count == 0) {
      try (Recording recording = tracer.writeTimer()) {
        write();
      }
    } else {
      if (tracer.getPartialFlushMinSpans() > 0 && size() > tracer.getPartialFlushMinSpans()) {
        try (Recording recording = tracer.writeTimer()) {
          synchronized (this) {
            int size = size();
            if (size > tracer.getPartialFlushMinSpans()) {
              final DDSpan rootSpan = getRootSpan();
              final List<DDSpan> partialTrace = new ArrayList<>(size);
              final Iterator<DDSpan> it = iterator();
              while (it.hasNext()) {
                final DDSpan span = it.next();
                if (span != rootSpan) {
                  partialTrace.add(span);
                  completedSpanCount.decrementAndGet();
                  // TODO spans are removed here
                  //  but not when the whole trace is written!
                  it.remove();
                }
              }
              if (log.isDebugEnabled()) {
                log.debug("Writing partial trace {} of size {}", traceId, partialTrace.size());
              }
              tracer.write(partialTrace);
            }
          }
        }
      }
    }
    if (log.isDebugEnabled()) {
      log.debug(
          "t_id={} -> expired reference. count={} spans={} continuations={}",
          traceId,
          count,
          weakSpans.size(),
          weakContinuations.size());
    }
  }

  private synchronized void write() {
    if (isWritten.compareAndSet(false, true)) {
      removePendingTrace();
      if (!isEmpty()) {
        int size = size();
        if (log.isDebugEnabled()) {
          log.debug("Writing {} spans to {}.", size, tracer.writer);
        }
        List<DDSpan> trace = new ArrayList<>(size);
        trace.addAll(this);
        // TODO - strange that tests expect the contents
        //  NOT to be cleared here. Keeping the spans around
        //  could lead to them all being promoted by nepotism,
        //  whereas some of them might avoid this if they're
        //  dropped when we write
        tracer.write(trace);
      }
    }
  }

  public synchronized boolean clean() {
    Reference ref;
    int count = 0;
    while ((ref = continuationReferenceQueue.poll()) != null) {
      weakContinuations.remove(ref);
      count++;
      expireReference();
    }
    if (count > 0) {
      log.debug("t_id={} -> {} unfinished continuations garbage collected.", traceId, count);
    }

    count = 0;
    while ((ref = spanReferenceQueue.poll()) != null) {
      weakSpans.remove(ref);
      if (isWritten.compareAndSet(false, true)) {
        removePendingTrace();
        // preserve throughput count.
        // Don't report the trace because the data comes from buggy uses of the api and is suspect.
        tracer.incrementTraceCount();
      }
      count++;
      expireReference();
    }
    if (count > 0) {
      // TODO attempt to flatten and report if top level spans are finished. (for accurate metrics)
      log.debug(
          "t_id={} -> {} unfinished spans garbage collected. Trace will not be reported.",
          traceId,
          count);
    }
    return count > 0;
  }

  @Override
  public void addFirst(final DDSpan span) {
    super.addFirst(span);
    completedSpanCount.incrementAndGet();
  }

  @Override
  public int size() {
    return completedSpanCount.get();
  }

  private void addPendingTrace() {
    final SpanCleaner cleaner = SPAN_CLEANER.get();
    if (cleaner != null) {
      cleaner.pendingTraces.add(this);
    }
  }

  private void removePendingTrace() {
    final SpanCleaner cleaner = SPAN_CLEANER.get();
    if (cleaner != null) {
      cleaner.pendingTraces.remove(this);
    }
  }

  static void initialize() {
    final SpanCleaner oldCleaner = SPAN_CLEANER.getAndSet(new SpanCleaner());
    if (oldCleaner != null) {
      oldCleaner.close();
    }
  }

  static void close() {
    final SpanCleaner cleaner = SPAN_CLEANER.getAndSet(null);
    if (cleaner != null) {
      cleaner.close();
    }
  }

  // FIXME: it should be possible to simplify this logic and avoid having SpanCleaner and
  // SpanCleanerTask
  private static class SpanCleaner implements Runnable, Closeable {
    private static final long CLEAN_FREQUENCY = 1;

    private final Set<PendingTrace> pendingTraces =
        Collections.newSetFromMap(new ConcurrentHashMap<PendingTrace, Boolean>());

    public SpanCleaner() {
      AgentTaskScheduler.INSTANCE.scheduleAtFixedRate(
          SpanCleanerTask.INSTANCE, this, 0, CLEAN_FREQUENCY, TimeUnit.SECONDS);
    }

    @Override
    public void run() {
      for (final PendingTrace trace : pendingTraces) {
        trace.clean();
      }
    }

    @Override
    public void close() {
      // Make sure that whatever was left over gets cleaned up
      run();
    }
  }

  /*
   * Important to use explicit class to avoid implicit hard references to cleaners from within executor.
   */
  private static class SpanCleanerTask implements Task<SpanCleaner> {

    static final SpanCleanerTask INSTANCE = new SpanCleanerTask();

    @Override
    public void run(final SpanCleaner target) {
      target.run();
    }
  }
}
