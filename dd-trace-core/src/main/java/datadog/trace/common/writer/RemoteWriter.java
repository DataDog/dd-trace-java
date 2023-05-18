package datadog.trace.common.writer;

import static datadog.trace.api.sampling.PrioritySampling.UNSET;

import datadog.trace.core.DDSpan;
import datadog.trace.core.monitor.HealthMetrics;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This writer buffers traces and sends them to the provided DDApi instance. Buffering is done with
 * a disruptor to limit blocking the application threads. Internally, the trace is serialized and
 * put onto a separate disruptor that does block to decouple the CPU intensive from the IO bound
 * threads.
 *
 * <p>[Application] -> [trace processing buffer] -> [serialized trace batching buffer] -> [dd-agent]
 *
 * <p>Note: the first buffer is non-blocking and will discard if full, the second is blocking and
 * will cause back pressure on the trace processing (serializing) thread.
 *
 * <p>If the buffer is filled traces are discarded before serializing. Once serialized every effort
 * is made to keep, to avoid wasting the serialization effort.
 */
public abstract class RemoteWriter implements Writer {

  private static final Logger log = LoggerFactory.getLogger(RemoteWriter.class);

  protected final TraceProcessingWorker traceProcessingWorker;
  private final PayloadDispatcher dispatcher;
  private final boolean alwaysFlush;
  private final int flushTimeout;
  private final TimeUnit flushTimeoutUnit;

  private volatile boolean closed;
  public final HealthMetrics healthMetrics;

  protected RemoteWriter(
      final TraceProcessingWorker traceProcessingWorker,
      final PayloadDispatcher dispatcher,
      final HealthMetrics healthMetrics,
      final int flushTimeout,
      final TimeUnit flushTimeoutUnit,
      final boolean alwaysFlush) {
    this.traceProcessingWorker = traceProcessingWorker;
    this.dispatcher = dispatcher;
    this.healthMetrics = healthMetrics;
    this.flushTimeout = flushTimeout;
    this.flushTimeoutUnit = flushTimeoutUnit;
    this.alwaysFlush = alwaysFlush;
  }

  protected RemoteWriter(
      final TraceProcessingWorker traceProcessingWorker,
      final PayloadDispatcher dispatcher,
      final HealthMetrics healthMetrics,
      final boolean alwaysFlush) {
    // Default constructor with 1 second of flush timeout. Used by the DDAgentWriter.
    this(traceProcessingWorker, dispatcher, healthMetrics, 1, TimeUnit.SECONDS, alwaysFlush);
  }

  @Override
  public void write(final List<DDSpan> trace) {
    // We can't add events after shutdown otherwise it will never complete shutting down.
    if (!closed) {
      if (trace.isEmpty()) {
        handleDroppedTrace("Trace was empty", trace, UNSET);
      } else {
        final DDSpan root = trace.get(0);
        final int samplingPriority = root.samplingPriority();
        switch (traceProcessingWorker.publish(root, samplingPriority, trace)) {
          case ENQUEUED_FOR_SERIALIZATION:
            log.debug("Enqueued for serialization: {}", trace);
            healthMetrics.onPublish(trace, samplingPriority);
            break;
          case ENQUEUED_FOR_SINGLE_SPAN_SAMPLING:
            log.debug("Enqueued for single span sampling: {}", trace);
            break;
          case DROPPED_BY_POLICY:
            handleDroppedTrace("Dropping policy is active", trace, samplingPriority);
            break;
          case DROPPED_BUFFER_OVERFLOW:
            handleDroppedTrace("Trace written to overfilled buffer", trace, samplingPriority);
            break;
        }
      }
    } else {
      handleDroppedTrace("Trace written after shutdown.", trace, UNSET);
    }
    if (alwaysFlush) {
      flush();
    }
  }

  private void handleDroppedTrace(
      final String reason, final List<DDSpan> trace, final int samplingPriority) {
    log.debug("{}. Counted but dropping trace: {}", reason, trace);
    healthMetrics.onFailedPublish(
        trace.isEmpty() ? 0 : trace.get(0).samplingPriority(), trace.size());
    incrementDropCounts(trace.size());
  }

  // Exposing some statistics for consumption by monitors
  public final long getCapacity() {
    return traceProcessingWorker.getCapacity();
  }

  @Override
  public boolean flush() {
    if (!closed) {
      if (traceProcessingWorker.flush(flushTimeout, flushTimeoutUnit)) {
        healthMetrics.onFlush(false);
        return true;
      }
    }
    return false;
  }

  @Override
  public void start() {
    if (!closed) {
      traceProcessingWorker.start();
      healthMetrics.start();
      healthMetrics.onStart((int) getCapacity());
    }
  }

  @Override
  public void close() {
    final boolean flushed = flush();
    closed = true;
    traceProcessingWorker.close();
    healthMetrics.onShutdown(flushed);
    healthMetrics.close();
  }

  @Override
  public void incrementDropCounts(int spanCount) {
    dispatcher.onDroppedTrace(spanCount);
  }

  // used by tests
  public Collection<Class<? extends RemoteApi>> getApis() {
    return dispatcher.getApis();
  }
}
