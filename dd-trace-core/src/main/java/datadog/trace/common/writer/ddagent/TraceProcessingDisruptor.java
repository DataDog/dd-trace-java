package datadog.trace.common.writer.ddagent;

import com.lmax.disruptor.EventHandler;
import datadog.common.exec.DaemonThreadFactory;
import datadog.trace.common.writer.DDAgentWriter;
import datadog.trace.core.DDSpan;
import datadog.trace.core.processor.TraceProcessor;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * Disruptor that takes completed traces and applies processing to them. Upon completion, the
 * serialized trace is published to {@link BatchWritingDisruptor}.
 *
 * <p>publishing to the buffer will not block the calling thread, but instead will return false if
 * the buffer is full. This is to avoid impacting an application thread.
 */
@Slf4j
public class TraceProcessingDisruptor extends AbstractDisruptor<List<DDSpan>> {

  public TraceProcessingDisruptor(
      final int disruptorSize,
      final BatchWritingDisruptor batchWritingDisruptor,
      final Monitor monitor,
      final DDAgentWriter writer,
      final StatefulSerializer serializer) {
    // TODO: add config to enable control over serialization overhead.
    super(
        disruptorSize,
        new TraceSerializingHandler(batchWritingDisruptor, monitor, writer, serializer));
  }

  @Override
  protected DaemonThreadFactory getThreadFactory() {
    return DaemonThreadFactory.TRACE_PROCESSOR;
  }

  @Override
  public boolean publish(final List<DDSpan> data, final int representativeCount) {
    return disruptor.getRingBuffer().tryPublishEvent(dataTranslator, data, representativeCount);
  }

  // This class is threadsafe if we want to enable more processors.
  public static class TraceSerializingHandler
      implements EventHandler<DisruptorEvent<List<DDSpan>>> {
    private final TraceProcessor processor = new TraceProcessor();
    private final BatchWritingDisruptor batchWritingDisruptor;
    private final Monitor monitor;
    private final DDAgentWriter writer;
    private final StatefulSerializer serializer;

    public TraceSerializingHandler(
        final BatchWritingDisruptor batchWritingDisruptor,
        final Monitor monitor,
        final DDAgentWriter writer,
        final StatefulSerializer serializer) {
      this.batchWritingDisruptor = batchWritingDisruptor;
      this.monitor = monitor;
      this.writer = writer;
      this.serializer = serializer;
    }

    @Override
    public void onEvent(
        final DisruptorEvent<List<DDSpan>> event, final long sequence, final boolean endOfBatch) {
      try {
        if (event.data != null) {
          // TODO populate `_sample_rate` metric in a way that accounts for lost/dropped traces
          try {
            event.data = processor.onTraceComplete(event.data);
            // the intention is that batching ends up being handled here,
            // rather than on the BatchWritingDisruptor, which would
            // be responsible for sending trace buffers to the agent
            // synchronously, before returning the trace buffer for
            // reuse.
            serializer.serialize(event.data);
            TraceBuffer serializedTrace = serializer.getBuffer();
            int sizeInBytes = serializedTrace.sizeInBytes();
            batchWritingDisruptor.publish(serializedTrace, event.representativeCount);
            monitor.onSerialize(writer, event.data, sizeInBytes);
            event.representativeCount = 0; // reset in case flush is invoked below.
          } catch (final Throwable e) {
            log.debug("Error while serializing trace", e);
            monitor.onFailedSerialize(writer, event.data, e);
          }
        }

        if (event.flushLatch != null) {
          if (batchWritingDisruptor.running) {
            // propagate the flush.
            batchWritingDisruptor.flush(event.representativeCount, event.flushLatch);
          }
          if (!batchWritingDisruptor.running) { // check again to protect against race condition.
            // got shutdown early somehow?
            event.flushLatch.countDown();
          }
        }
      } finally {
        event.reset();
      }
    }
  }
}
