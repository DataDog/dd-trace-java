package datadog.trace.common.writer;

import static datadog.trace.api.sampling.PrioritySampling.UNSET;

import datadog.trace.core.DDSpan;
import datadog.trace.core.monitor.HealthMetrics;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This writer buffers traces and sends them to the provided DDApi instance. Buffering is done with
 * a distruptor to limit blocking the application threads. Internally, the trace is serialized and
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

  private final RemoteApi api;
  protected final TraceProcessingWorker traceProcessingWorker;
  private final PayloadDispatcher dispatcher;
  private final boolean alwaysFlush;

  private volatile boolean closed;

  public final HealthMetrics healthMetrics;

  protected RemoteWriter(
      final RemoteApi api,
      final TraceProcessingWorker traceProcessingWorker,
      final PayloadDispatcher dispatcher,
      final HealthMetrics healthMetrics,
      final boolean alwaysFlush) {
    this.api = api;
    this.traceProcessingWorker = traceProcessingWorker;
    this.dispatcher = dispatcher;
    this.healthMetrics = healthMetrics;
    this.alwaysFlush = alwaysFlush;
  }

  public void addResponseListener(final RemoteResponseListener listener) {
    api.addResponseListener(listener);
  }

  public RemoteApi getApi() {
    return api;
  }

  @Override
  public void write(final List<DDSpan> trace) {
    // We can't add events after shutdown otherwise it will never complete shutting down.
    if (!closed) {
      if (trace.isEmpty()) {
        handleDroppedTrace("Trace was empty", trace);
      } else {
        final DDSpan root = trace.get(0);
        final int samplingPriority = root.context().getSamplingPriority();
        if (traceProcessingWorker.publish(root, samplingPriority, trace)) {
          healthMetrics.onPublish(trace, samplingPriority);
        } else {
          handleDroppedTrace("Trace written to overfilled buffer", trace, samplingPriority);
        }
      }
    } else {
      handleDroppedTrace("Trace written after shutdown.", trace);
    }
    if (alwaysFlush) {
      flush();
    }
  }

  private void handleDroppedTrace(final String reason, final List<DDSpan> trace) {
    log.debug("{}. Counted but dropping trace: {}", reason, trace);
    healthMetrics.onFailedPublish(UNSET);
    incrementDropCounts(trace.size());
  }

  private void handleDroppedTrace(
      final String reason, final List<DDSpan> trace, final int samplingPriority) {
    log.debug("{}. Counted but dropping trace: {}", reason, trace);
    healthMetrics.onFailedPublish(samplingPriority);
    incrementDropCounts(trace.size());
  }

  // Exposing some statistics for consumption by monitors
  public final long getCapacity() {
    return traceProcessingWorker.getCapacity();
  }

  @Override
  public boolean flush() {
    if (!closed) { // give up after a second
      if (traceProcessingWorker.flush(1, TimeUnit.SECONDS)) {
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
    healthMetrics.close();
    healthMetrics.onShutdown(flushed);
  }

  @Override
  public void incrementDropCounts(int spanCount) {
    dispatcher.onDroppedTrace(spanCount);
  }
}
