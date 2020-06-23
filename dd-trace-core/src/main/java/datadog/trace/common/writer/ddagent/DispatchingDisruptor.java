package datadog.trace.common.writer.ddagent;

import com.lmax.disruptor.BlockingWaitStrategy;
import com.lmax.disruptor.EventFactory;
import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import datadog.common.exec.DaemonThreadFactory;
import datadog.trace.common.writer.DDAgentWriter;
import lombok.extern.slf4j.Slf4j;

/**
 * Disruptor that takes serialized traces and dispatches them to the DD agent
 *
 * <p>publishing to the buffer will block if the buffer is full.
 */
@Slf4j
public class DispatchingDisruptor implements AutoCloseable {

  private final Disruptor<TraceBuffer> disruptor;

  public DispatchingDisruptor(
      int disruptorSize,
      EventFactory<TraceBuffer> eventFactory,
      DDAgentApi api,
      Monitor monitor,
      DDAgentWriter writer) {
    this.disruptor =
        DisruptorUtils.create(
            eventFactory,
            disruptorSize,
            DaemonThreadFactory.TRACE_WRITER,
            ProducerType.SINGLE,
            // block (and use no resources) until there's a batch of data to dispatch
            new BlockingWaitStrategy());
    disruptor.handleEventsWith(new TraceDispatchingHandler(api, monitor, writer));
  }

  public void start() {
    disruptor.start();
  }

  @Override
  public void close() {
    disruptor.halt();
  }

  long beginTransaction() {
    return disruptor.getRingBuffer().next();
  }

  TraceBuffer getTraceBuffer(long sequence) {
    return disruptor.getRingBuffer().get(sequence);
  }

  void commit(long sequence) {
    disruptor.getRingBuffer().publish(sequence);
  }

  // Intentionally not thread safe.
  private static class TraceDispatchingHandler implements EventHandler<TraceBuffer> {

    private final DDAgentApi api;
    private final Monitor monitor;
    private final DDAgentWriter writer;

    private TraceDispatchingHandler(
        final DDAgentApi api, final Monitor monitor, final DDAgentWriter writer) {
      this.api = api;
      this.monitor = monitor;
      this.writer = writer;
    }

    @Override
    public void onEvent(final TraceBuffer event, final long sequence, final boolean endOfBatch) {
      sendData(event);
    }

    private void sendData(TraceBuffer traces) {
      if (log.isDebugEnabled()) {
        log.debug(
            "receive id={}, rc={}, tc={}",
            traces.id(),
            traces.representativeCount(),
            traces.traceCount());
      }
      try {
        if (traces.traceCount() > 0) {
          final DDAgentApi.Response response = api.sendSerializedTraces(traces);
          if (response.success()) {
            if (log.isDebugEnabled()) {
              log.debug(
                  "Successfully sent {} traces {} to the API", traces.traceCount(), traces.id());
            }
            monitor.onSend(writer, traces.representativeCount(), traces.sizeInBytes(), response);
          } else {
            if (log.isDebugEnabled()) {
              log.debug(
                  "Failed to send {} traces (representing {}) of size {} bytes to the API",
                  traces.traceCount(),
                  traces.representativeCount(),
                  traces.sizeInBytes());
            }
            monitor.onFailedSend(
                writer, traces.representativeCount(), traces.sizeInBytes(), response);
          }
        } else if (log.isDebugEnabled()) {
          log.debug("buffer {} was empty", traces.id());
        }
      } catch (final Throwable e) {
        log.debug("Failed to send traces to the API: {}", e.getMessage(), e);

        // DQH - 10/2019 - DDApi should wrap most exceptions itself, so this really
        // shouldn't occur.
        // However, just to be safe to start, create a failed Response to handle any
        // spurious Throwable-s.
        monitor.onFailedSend(
            writer,
            traces.representativeCount(),
            traces.sizeInBytes(),
            DDAgentApi.Response.failed(e));
      } finally {
        traces.onDispatched();
      }
    }
  }
}
