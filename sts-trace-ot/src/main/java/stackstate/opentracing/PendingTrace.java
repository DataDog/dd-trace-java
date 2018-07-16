package stackstate.opentracing;

import stackstate.opentracing.scopemanager.ContinuableScope;
import stackstate.trace.common.util.Clock;
import java.io.Closeable;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class PendingTrace extends ConcurrentLinkedDeque<STSSpan> {
  private static final SpanCleaner SPAN_CLEANER;

  static {
    SPAN_CLEANER = new SpanCleaner();
    SPAN_CLEANER.start();
  }

  private final STSTracer tracer;
  private final long traceId;

  // TODO: consider moving these time fields into STSTracer to ensure that traces have precise relative time
  /** Trace start time in nano seconds measured up to a millisecond accuracy */
  private final long startTimeNano;
  /** Nano second ticks value at trace start */
  private final long startNanoTicks;

  private final ReferenceQueue referenceQueue = new ReferenceQueue();
  private final Set<WeakReference<?>> weakReferences =
      Collections.newSetFromMap(new ConcurrentHashMap<WeakReference<?>, Boolean>());

  private final AtomicInteger pendingReferenceCount = new AtomicInteger(0);
  private final AtomicReference<WeakReference<DDSpan>> rootSpan = new AtomicReference<>();

  /** Ensure a trace is never written multiple times */
  private final AtomicBoolean isWritten = new AtomicBoolean(false);

  PendingTrace(final STSTracer tracer, final long traceId) {
    this.tracer = tracer;
    this.traceId = traceId;

    this.startTimeNano = Clock.currentNanoTime();
    this.startNanoTicks = Clock.currentNanoTicks();

    SPAN_CLEANER.pendingTraces.add(this);
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

  public void registerSpan(final STSSpan span) {
    if (span.context().getTraceId() != traceId) {
      log.debug("{} - span registered for wrong trace ({})", span, traceId);
      return;
    }
    rootSpan.compareAndSet(null, new WeakReference<STSSpan>(span));
    synchronized (span) {
      if (null == span.ref) {
        span.ref = new WeakReference<STSSpan>(span, referenceQueue);
        weakReferences.add(span.ref);
        final int count = pendingReferenceCount.incrementAndGet();
        log.debug("traceId: {} -- registered span {}. count = {}", traceId, span, count);
      } else {
        log.debug("span {} already registered in trace {}", span, traceId);
      }
    }
  }

  private void expireSpan(final STSSpan span) {
    if (span.context().getTraceId() != traceId) {
      log.debug("{} - span expired for wrong trace ({})", span, traceId);
      return;
    }
    synchronized (span) {
      if (null == span.ref) {
        log.debug("span {} not registered in trace {}", span, traceId);
      } else {
        weakReferences.remove(span.ref);
        span.ref.clear();
        span.ref = null;
        expireReference();
      }
    }
  }

  public void addSpan(final STSSpan span) {
    if (span.getDurationNano() == 0) {
      log.debug("{} - added to trace, but not complete.", span);
      return;
    }
    if (traceId != span.getTraceId()) {
      log.debug("{} - added to a mismatched trace.", span);
      return;
    }

    if (!isWritten.get()) {
      addFirst(span);
    } else {
      log.debug("{} - finished after trace reported.", span);
    }
    expireSpan(span);
  }

  public STSSpan getRootSpan() {
    WeakReference<STSSpan> rootRef = rootSpan.get();
    return rootRef == null ? null : rootRef.get();
  }

  /**
   * When using continuations, it's possible one may be used after all existing spans are otherwise
   * completed, so we need to wait till continuations are de-referenced before reporting.
   */
  public void registerContinuation(final ContinuableScope.Continuation continuation) {
    synchronized (continuation) {
      if (continuation.ref == null) {
        continuation.ref =
            new WeakReference<ContinuableScope.Continuation>(continuation, referenceQueue);
        weakReferences.add(continuation.ref);
        final int count = pendingReferenceCount.incrementAndGet();
        log.debug(
            "traceId: {} -- registered continuation {}. count = {}", traceId, continuation, count);
      } else {
        log.debug("continuation {} already registered in trace {}", continuation, traceId);
      }
    }
  }

  public void cancelContinuation(final ContinuableScope.Continuation continuation) {
    synchronized (continuation) {
      if (continuation.ref == null) {
        log.debug("continuation {} not registered in trace {}", continuation, traceId);
      } else {
        weakReferences.remove(continuation.ref);
        continuation.ref.clear();
        continuation.ref = null;
        expireReference();
      }
    }
  }

  private void expireReference() {
    final int count = pendingReferenceCount.decrementAndGet();
    if (count == 0) {
      write();
    }
    log.debug("traceId: {} -- Expired reference. count = {}", traceId, count);
  }

  private void write() {
    if (isWritten.compareAndSet(false, true)) {
      SPAN_CLEANER.pendingTraces.remove(this);
      if (!isEmpty()) {
        log.debug("Writing {} spans to {}.", this.size(), tracer.writer);
        tracer.write(this);
      }
    }
  }

  public synchronized boolean clean() {
    Reference ref;
    int count = 0;
    while ((ref = referenceQueue.poll()) != null) {
      weakReferences.remove(ref);
      count++;
      expireReference();
    }
    if (count > 0) {
      log.debug("{} unfinished spans garbage collected!", count);
    }
    return count > 0;
  }

  /**
   * This method ensures that garbage collection takes place, unlike <code>{@link System#gc()}
   * </code>. Useful for testing.
   */
  public static void awaitGC() {
    System.gc(); // For good measure.
    Object obj = new Object();
    final WeakReference ref = new WeakReference<>(obj);
    obj = null;
    while (ref.get() != null) {
      System.gc();
    }
  }

  static void close() {
    SPAN_CLEANER.close();
  }

  private static class SpanCleaner implements Runnable, Closeable {
    private static final long CLEAN_FREQUENCY = 1;
    private static final ThreadFactory FACTORY =
        new ThreadFactory() {
          @Override
          public Thread newThread(final Runnable r) {
            final Thread thread = new Thread(r, "sts-span-cleaner");
            thread.setDaemon(true);
            return thread;
          }
        };

    private final ScheduledExecutorService executorService =
        Executors.newScheduledThreadPool(1, FACTORY);

    private final Set<PendingTrace> pendingTraces =
        Collections.newSetFromMap(new ConcurrentHashMap<PendingTrace, Boolean>());

    void start() {
      executorService.scheduleAtFixedRate(new SpanCleaner(), 0, CLEAN_FREQUENCY, TimeUnit.SECONDS);
      try {
        Runtime.getRuntime()
            .addShutdownHook(
                new Thread() {
                  @Override
                  public void run() {
                    PendingTrace.SpanCleaner.this.close();
                  }
                });
      } catch (final IllegalStateException ex) {
        // The JVM is already shutting down.
      }
    }

    @Override
    public void run() {
      for (final PendingTrace trace : pendingTraces) {
        trace.clean();
      }
    }

    @Override
    public void close() {
      executorService.shutdownNow();
      try {
        executorService.awaitTermination(500, TimeUnit.MILLISECONDS);
      } catch (final InterruptedException e) {
        log.info("Writer properly closed and async writer interrupted.");
      }
    }
  }
}
