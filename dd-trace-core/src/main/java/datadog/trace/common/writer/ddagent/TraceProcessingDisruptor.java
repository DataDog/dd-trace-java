package datadog.trace.common.writer.ddagent;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

import com.lmax.disruptor.BlockingWaitStrategy;
import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import datadog.common.exec.CommonTaskExecutor;
import datadog.common.exec.DaemonThreadFactory;
import datadog.trace.common.writer.DDAgentWriter;
import datadog.trace.core.DDSpan;
import datadog.trace.core.processor.TraceProcessor;
import datadog.trace.core.serialization.msgpack.ByteBufferConsumer;
import datadog.trace.core.serialization.msgpack.Packer;
import java.nio.ByteBuffer;
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

  static final int DEFAULT_BUFFER_SIZE = 2 << 20; // 2MB

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
      final DDAgentWriter writer,
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
            // use sleeping wait strategy because it reduces CPU usage,
            // and is cheaper for application threads publishing traces
            new BlockingWaitStrategy());
    disruptor.handleEventsWith(
        new TraceSerializingHandler(monitor, writer, flushInterval, timeUnit, api));
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
      implements EventHandler<DisruptorEvent<List<DDSpan>>>, ByteBufferConsumer {

    private final TraceProcessor processor = new TraceProcessor();
    private final Monitor monitor;
    private final DDAgentWriter writer;
    private final long flushIntervalMillis;
    private final boolean doTimeFlush;
    private final DDAgentApi api;
    private int representativeCount = 0;
    private long nextFlushMillis;
    private final TraceMapper traceMapper = new TraceMapper();

    private Packer packer;

    public TraceSerializingHandler(
        final Monitor monitor,
        final DDAgentWriter writer,
        final long flushInterval,
        final TimeUnit timeUnit,
        DDAgentApi api) {
      this.monitor = monitor;
      this.writer = writer;
      this.doTimeFlush = flushInterval > 0;
      this.api = api;
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
      if (null == packer) {
        packer = new Packer(this, ByteBuffer.allocate(DEFAULT_BUFFER_SIZE));
      }
      try {
        if (representativeCount > 0) {
          // publish an incomplete batch if
          // 1. we get a heartbeat, and it's time to send (early heartbeats will be ignored)
          // 2. a synchronous flush command is received (at shutdown)
          if ((event.data == null && doTimeFlush && millisecondTime() > nextFlushMillis)) {
            packer.flush();
            scheduleNextTimeFlush();
          }
        }
        if (event.data != null) {
          serialize(event.data, event.representativeCount);
        }
        if (null != event.flushLatch) {
          packer.flush();
          event.flushLatch.countDown();
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

    private void serialize(List<DDSpan> trace, int representativeCount) {
      // TODO populate `_sample_rate` metric in a way that accounts for lost/dropped traces
      packer.format(processor.onTraceComplete(trace), traceMapper);
      this.representativeCount += representativeCount;
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

    @Override
    public void accept(int messageCount, ByteBuffer buffer) {
      // the packer calls this when the buffer is full
      if (messageCount > 0) {
        final int sizeInBytes = buffer.limit() - buffer.position();
        monitor.onSerialize(sizeInBytes);
        DDAgentApi.Response response =
            api.sendSerializedTraces(messageCount, representativeCount, buffer);
        if (response.success()) {
          if (log.isDebugEnabled()) {
            log.debug("Successfully sent {} traces to the API", messageCount);
          }
          monitor.onSend(writer, representativeCount, sizeInBytes, response);
        } else {
          if (log.isDebugEnabled()) {
            log.debug(
                "Failed to send {} traces (representing {}) of size {} bytes to the API",
                messageCount,
                representativeCount,
                sizeInBytes);
          }
          monitor.onFailedSend(writer, representativeCount, sizeInBytes, response);
        }
        this.representativeCount = 0;
      }
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
