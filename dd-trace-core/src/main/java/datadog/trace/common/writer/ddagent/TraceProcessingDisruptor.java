package datadog.trace.common.writer.ddagent;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

import com.lmax.disruptor.BlockingWaitStrategy;
import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import datadog.common.exec.CommonTaskExecutor;
import datadog.common.exec.DaemonThreadFactory;
import datadog.trace.core.DDSpan;
import datadog.trace.core.monitor.Monitor;
import datadog.trace.core.processor.TraceProcessor;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;

/**
 * Disruptor that takes completed traces and applies processing to them. Upon completion, the
 * serialized trace is published to the Datadog Agent}.
 *
 * <p>publishing to the buffer will not block the calling thread, but instead will return false if
 * the buffer is full. This is to avoid impacting an application thread.
 */
@Slf4j
public class TraceProcessingDisruptor implements AutoCloseable {

  private final Disruptor<DisruptorEvent<List<DDSpan>>> disruptor;
  private final DisruptorEvent.DataTranslator<List<DDSpan>> dataTranslator;
  private final DisruptorEvent.FlushTranslator<List<DDSpan>> flushTranslator;
  private final DisruptorEvent.HeartbeatTranslator<List<DDSpan>> heartbeatTranslator =
      new DisruptorEvent.HeartbeatTranslator<>();
  private final boolean doHeartbeat;

  private volatile ScheduledFuture<?> heartbeat;

  public TraceProcessingDisruptor(
      final int disruptorSize,
      final Monitor monitor,
      final DDAgentApi api,
      final long flushInterval,
      final TimeUnit timeUnit,
      final boolean heartbeat) {
    this.disruptor =
        DisruptorUtils.create(
            new DisruptorEvent.Factory<List<DDSpan>>(),
            disruptorSize,
            DaemonThreadFactory.TRACE_PROCESSOR,
            ProducerType.MULTI,
            // using blocking wait strategy because the processor will
            // spend some time doing IO anyway
            new BlockingWaitStrategy());
    disruptor.handleEventsWith(
        new TraceSerializingHandler(
            monitor, flushInterval, timeUnit, new PayloadDispatcher(api, monitor)));
    this.dataTranslator = new DisruptorEvent.DataTranslator<>();
    this.flushTranslator = new DisruptorEvent.FlushTranslator<>();
    this.doHeartbeat = heartbeat;
  }

  public void start() {
    if (doHeartbeat) {
      // This provides a steady stream of events to enable flushing with a low throughput.
      heartbeat =
          CommonTaskExecutor.INSTANCE.scheduleAtFixedRate(
              new HeartbeatTask(), this, 100, 100, MILLISECONDS, "disruptor heartbeat");
    }
    disruptor.start();
  }

  public boolean flush(long timeout, TimeUnit timeUnit) {
    CountDownLatch latch = new CountDownLatch(1);
    disruptor.publishEvent(flushTranslator, 0, latch);
    try {
      return latch.await(timeout, timeUnit);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  @Override
  public void close() {
    if (null != heartbeat) {
      heartbeat.cancel(true);
    }
    disruptor.halt();
  }

  public boolean publish(final List<DDSpan> data, final int representativeCount) {
    return disruptor.getRingBuffer().tryPublishEvent(dataTranslator, data, representativeCount);
  }

  void heartbeat() {
    disruptor.getRingBuffer().tryPublishEvent(heartbeatTranslator);
  }

  public int getDisruptorCapacity() {
    return disruptor.getRingBuffer().getBufferSize();
  }

  public long getDisruptorRemainingCapacity() {
    return disruptor.getRingBuffer().remainingCapacity();
  }

  public static class TraceSerializingHandler
      implements EventHandler<DisruptorEvent<List<DDSpan>>> {

    private final TraceProcessor processor = new TraceProcessor();
    private final Monitor monitor;
    private final long flushIntervalMillis;
    private final boolean doTimeFlush;
    private final PayloadDispatcher payloadDispatcher;
    private long nextFlushMillis;

    public TraceSerializingHandler(
        final Monitor monitor,
        final long flushInterval,
        final TimeUnit timeUnit,
        PayloadDispatcher payloadDispatcher) {
      this.monitor = monitor;
      this.doTimeFlush = flushInterval > 0;
      this.payloadDispatcher = payloadDispatcher;
      if (doTimeFlush) {
        this.flushIntervalMillis = timeUnit.toMillis(flushInterval);
        scheduleNextTimeFlush();
      } else {
        this.flushIntervalMillis = Long.MAX_VALUE;
      }
    }

    @Override
    public void onEvent(
        final DisruptorEvent<List<DDSpan>> event, final long sequence, final boolean endOfBatch) {
      try {
        // publish an incomplete batch if
        // 1. we get a heartbeat, and it's time to send (early heartbeats will be ignored)
        // 2. a synchronous flush command is received (at shutdown)
        if ((event.data == null && doTimeFlush && millisecondTime() > nextFlushMillis)) {
          payloadDispatcher.flush();
          scheduleNextTimeFlush();
        }
        if (event.data != null) {
          // TODO populate `_sample_rate` metric in a way that accounts for lost/dropped traces
          payloadDispatcher.addTrace(processor.onTraceComplete(event.data));
        }
        if (null != event.flushLatch) {
          payloadDispatcher.flush();
          event.flushLatch.countDown();
        }
      } catch (final Throwable e) {
        if (log.isDebugEnabled()) {
          log.debug("Error while serializing trace", e);
        }
        monitor.onFailedSerialize(event.data, e);
      } finally {
        event.reset();
      }
    }

    private void scheduleNextTimeFlush() {
      if (doTimeFlush) {
        nextFlushMillis = millisecondTime() + flushIntervalMillis;
      }
    }

    private long millisecondTime() {
      // important: nanoTime is monotonic, currentTimeMillis is not
      return NANOSECONDS.toMillis(System.nanoTime());
    }
  }

  // Important to use explicit class to avoid implicit hard references to TraceProcessingDisruptor
  private static final class HeartbeatTask
      implements CommonTaskExecutor.Task<TraceProcessingDisruptor> {
    @Override
    public void run(final TraceProcessingDisruptor traceProcessor) {
      traceProcessor.heartbeat();
    }
  }
}
