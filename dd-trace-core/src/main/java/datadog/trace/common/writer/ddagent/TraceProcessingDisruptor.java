package datadog.trace.common.writer.ddagent;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.SleepingWaitStrategy;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import datadog.common.exec.CommonTaskExecutor;
import datadog.common.exec.DaemonThreadFactory;
import datadog.trace.common.writer.DDAgentWriter;
import datadog.trace.core.DDSpan;
import datadog.trace.core.interceptor.TraceStatsCollector;
import datadog.trace.core.processor.TraceProcessor;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;

/**
 * Disruptor that takes completed traces and applies processing to them. Upon completion, the
 * serialized trace is published to {@link DispatchingDisruptor}.
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
  private final TraceProcessor traceProcessor;

  private volatile ScheduledFuture<?> heartbeat;

  public TraceProcessingDisruptor(
      final int disruptorSize,
      final DispatchingDisruptor dispatchingDisruptor,
      final TraceStatsCollector statsCollector,
      final Monitor monitor,
      final DDAgentWriter writer,
      final StatefulSerializer serializer,
      final long flushInterval,
      final TimeUnit timeUnit,
      final boolean heartbeat) {
    traceProcessor = new TraceProcessor(statsCollector);

    disruptor =
        DisruptorUtils.create(
            new DisruptorEvent.Factory<List<DDSpan>>(),
            disruptorSize,
            DaemonThreadFactory.TRACE_PROCESSOR,
            ProducerType.MULTI,
            // use sleeping wait strategy because it reduces CPU usage,
            // and is cheaper for application threads publishing traces
            new SleepingWaitStrategy(0, MILLISECONDS.toNanos(10)));
    disruptor.handleEventsWith(
        new TraceSerializingHandler(
            dispatchingDisruptor,
            traceProcessor,
            monitor,
            writer,
            serializer,
            flushInterval,
            timeUnit));
    dataTranslator = new DisruptorEvent.DataTranslator<>();
    flushTranslator = new DisruptorEvent.FlushTranslator<>();
    doHeartbeat = heartbeat;
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

  public boolean flush(final long timeout, final TimeUnit timeUnit) {
    final CountDownLatch latch = new CountDownLatch(1);
    disruptor.publishEvent(flushTranslator, 0, latch);
    try {
      return latch.await(timeout, timeUnit);
    } catch (final InterruptedException e) {
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
    private final TraceProcessor traceProcessor;
    private final DispatchingDisruptor dispatchingDisruptor;
    private final Monitor monitor;
    private final DDAgentWriter writer;
    private final StatefulSerializer serializer;
    private final long flushIntervalMillis;
    private final boolean doTimeFlush;

    private long publicationTxn = -1;
    private int representativeCount = 0;
    private long nextFlushMillis;

    public TraceSerializingHandler(
        final DispatchingDisruptor dispatchingDisruptor,
        final TraceProcessor traceProcessor,
        final Monitor monitor,
        final DDAgentWriter writer,
        final StatefulSerializer serializer,
        final long flushInterval,
        final TimeUnit timeUnit) {
      this.dispatchingDisruptor = dispatchingDisruptor;
      this.traceProcessor = traceProcessor;
      this.monitor = monitor;
      this.writer = writer;
      this.serializer = serializer;
      doTimeFlush = flushInterval > 0;
      if (doTimeFlush) {
        flushIntervalMillis = timeUnit.toMillis(flushInterval);
        scheduleNextTimeFlush();
      } else {
        flushIntervalMillis = Long.MAX_VALUE;
      }
    }

    @Override
    public void onEvent(
        final DisruptorEvent<List<DDSpan>> event, final long sequence, final boolean endOfBatch) {
      if (-1L == publicationTxn) {
        beginTransaction();
      }
      try {
        if (representativeCount > 0 || event.flushLatch != null) {
          // publish the batch if
          // 1. the buffer is full
          // 2. we get a heartbeat, and it's time to send (early heartbeats will be ignored)
          // 3. a synchronous flush command is received (at shutdown)
          if (serializer.isAtCapacity()
              || (doTimeFlush && millisecondTime() > nextFlushMillis)
              || event.flushLatch != null) {
            commitTransaction(event.flushLatch);
          }
        }
        if (event.data != null) {
          serialize(event.data, event.representativeCount);
        }
      } catch (final Throwable e) {
        if (log.isDebugEnabled()) {
          log.debug("Error while serializing trace", e);
        }
        monitor.onFailedSerialize(writer, event.data, e);
      } finally {
        event.reset();
      }
    }

    private void serialize(List<DDSpan> trace, final int representativeCount) throws IOException {
      // TODO populate `_sample_rate` metric in a way that accounts for lost/dropped traces
      this.representativeCount += representativeCount;
      trace = traceProcessor.onTraceComplete(trace);
      final int sizeInBytes = serializer.serialize(trace);
      monitor.onSerialize(writer, trace, sizeInBytes);
    }

    private void commitTransaction(final CountDownLatch flushLatch) throws IOException {
      serializer.dropBuffer();
      final TraceBuffer buffer = dispatchingDisruptor.getTraceBuffer(publicationTxn);
      if (null != flushLatch) {
        buffer.setDispatchRunnable(
            new Runnable() {
              @Override
              public void run() {
                flushLatch.countDown();
              }
            });
      }
      buffer.setRepresentativeCount(representativeCount);
      if (log.isDebugEnabled()) {
        log.debug(
            "publish id={}, rc={}, tc={}",
            buffer.id(),
            buffer.representativeCount(),
            buffer.traceCount());
      }
      dispatchingDisruptor.commit(publicationTxn);
      beginTransaction();
    }

    private void beginTransaction() {
      publicationTxn = dispatchingDisruptor.beginTransaction();
      representativeCount = 0;
      serializer.reset(dispatchingDisruptor.getTraceBuffer(publicationTxn));
      scheduleNextTimeFlush();
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
