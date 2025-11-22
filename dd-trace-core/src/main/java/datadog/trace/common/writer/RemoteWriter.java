package datadog.trace.common.writer;

import static datadog.trace.api.sampling.PrioritySampling.UNSET;
import static java.util.concurrent.TimeUnit.MINUTES;

import com.antithesis.sdk.Assert;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import datadog.trace.core.DDSpan;
import datadog.trace.core.monitor.HealthMetrics;
import datadog.trace.relocate.api.RatelimitedLogger;
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

  private final RatelimitedLogger rlLog = new RatelimitedLogger(log, 1, MINUTES);

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
    // Antithesis: Assert that we should never attempt to write when writer is closed
    ObjectNode writeAttemptDetails = JsonNodeFactory.instance.objectNode();
    writeAttemptDetails.put("writer_closed", closed);
    writeAttemptDetails.put("trace_size", trace.size());
    writeAttemptDetails.put("has_traces", !trace.isEmpty());
    
    log.debug("ANTITHESIS_ASSERT: Checking writer not closed when writing (always) - closed: {}, trace_size: {}", closed, trace.size());
    Assert.always(
        !closed,
        "Writer should never be closed when attempting to write traces",
        writeAttemptDetails);
    
    if (closed) {
      // We can't add events after shutdown otherwise it will never complete shutting down.
      log.debug("Dropped due to shutdown: {}", trace);
      
      // Antithesis: Track when traces are dropped due to writer being closed
      ObjectNode shutdownDetails = JsonNodeFactory.instance.objectNode();
      shutdownDetails.put("trace_size", trace.size());
      shutdownDetails.put("reason", "writer_closed_during_shutdown");
      
      log.debug("ANTITHESIS_ASSERT: Traces dropped due to shutdown (sometimes) - closed: {}, trace_size: {}", closed, trace.size());
      Assert.sometimes(
          closed && !trace.isEmpty(),
          "Traces are dropped due to writer shutdown - tracking shutdown behavior",
          shutdownDetails);
      
      handleDroppedTrace(trace);
    } else {
      if (trace.isEmpty()) {
        log.debug("Dropped an empty trace.");
        handleDroppedTrace(trace);
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
            log.debug("Dropped by the policy: {}", trace);
            handleDroppedTrace(trace);
            break;
          case DROPPED_BUFFER_OVERFLOW:
            // Antithesis: Buffer overflow should NEVER happen - this indicates a serious problem
            ObjectNode overflowDetails = JsonNodeFactory.instance.objectNode();
            overflowDetails.put("trace_size", trace.size());
            overflowDetails.put("sampling_priority", samplingPriority);
            overflowDetails.put("buffer_capacity", traceProcessingWorker.getCapacity());
            overflowDetails.put("reason", "buffer_overflow_backpressure");
            
            log.debug("ANTITHESIS_ASSERT: Buffer overflow occurred (unreachable) - trace_size: {}, capacity: {}", trace.size(), traceProcessingWorker.getCapacity());
            Assert.unreachable(
                "Buffer overflow should never occur - traces are being dropped due to backpressure",
                overflowDetails);
            
            if (log.isDebugEnabled()) {
              log.debug("Dropped due to a buffer overflow: {}", trace);
            } else {
              rlLog.warn("Dropped due to a buffer overflow: [{} spans]", trace.size());
            }
            handleDroppedTrace(trace);
            break;
        }
      }
    }
    if (alwaysFlush) {
      flush();
    }
  }

  private void handleDroppedTrace(final List<DDSpan> trace) {
    int samplingPriority = trace.isEmpty() ? UNSET : trace.get(0).samplingPriority();
    healthMetrics.onFailedPublish(samplingPriority, trace.size());
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
  public Collection<RemoteApi> getApis() {
    return dispatcher.getApis();
  }
}
