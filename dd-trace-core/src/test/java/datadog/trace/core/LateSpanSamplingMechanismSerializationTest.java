package datadog.trace.core;

import static datadog.trace.api.sampling.PrioritySampling.USER_KEEP;
import static datadog.trace.api.sampling.SamplingMechanism.REMOTE_ADAPTIVE_RULE;
import static datadog.trace.common.sampling.RuleBasedTraceSampler.SAMPLING_RULE_RATE;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.communication.serialization.ByteBufferConsumer;
import datadog.communication.serialization.FlushingBuffer;
import datadog.communication.serialization.msgpack.MsgPackWriter;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.common.writer.ddagent.TraceMapperV0_4;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessageUnpacker;

/**
 * Verifies that trace-level sampling metadata is preserved when a root and a later child are
 * exported in separate chunks (e.g. during partial sampling).
 */
class LateSpanSamplingMechanismSerializationTest extends DDCoreJavaSpecification {

  @Override
  protected boolean useStrictTraceWrites() {
    return false;
  }

  @Test
  void rootlessLateChunkRetainsAdaptiveSamplingMechanism()
      throws InterruptedException, TimeoutException {
    SerializingWriter writer = new SerializingWriter();
    CoreTracer tracer =
        tracerBuilder()
            .writer(writer)
            // Keep the partial-flush threshold above the trace size so the pending-trace idle
            // timeout controls the first write.
            .partialFlushMinSpans(1000)
            .build();

    try {
      DDSpan root = (DDSpan) tracer.buildSpan("test", "root").start();
      DDSpan lateChild =
          (DDSpan) tracer.buildSpan("test", "late-child").asChildOf(root.spanContext()).start();
      PendingTrace trace = (PendingTrace) root.spanContext().getTraceCollector();

      root.setSamplingPriority(USER_KEEP, REMOTE_ADAPTIVE_RULE);
      root.setMetric(SAMPLING_RULE_RATE, 0.25);

      // Root finishes while the child is still running -> root is buffered, not yet written.
      root.finish();
      assertTrue(writer.isEmpty());

      // Write the buffered root as the PendingTraceBuffer worker does after its idle timeout.
      trace.write();
      writer.waitForTraces(1);

      // The child finishes after the root was written, so it is exported in a root-less chunk.
      lateChild.finish();
      trace.write();
      writer.waitForTraces(2);

      assertEquals(singletonList(root), writer.get(0));
      assertEquals(singletonList(lateChild), writer.get(1));

      SerializedChunk rootChunk = writer.serializedChunks.get(0);
      SerializedChunk lateChunk = writer.serializedChunks.get(1);

      assertEquals(
          (int) USER_KEEP, rootChunk.metrics.get(DDSpanContext.PRIORITY_SAMPLING_KEY).intValue());
      assertEquals("-12", rootChunk.meta.get("_dd.p.dm"));

      assertEquals(
          (int) USER_KEEP, lateChunk.metrics.get(DDSpanContext.PRIORITY_SAMPLING_KEY).intValue());
      // The late chunk's decision maker must match its propagated trace-level sampling priority.
      assertEquals("-12", lateChunk.meta.get("_dd.p.dm"));
    } finally {
      tracer.close();
    }
  }

  private static final class SerializingWriter extends ListWriter {
    private final List<SerializedChunk> serializedChunks = new ArrayList<>();

    @Override
    public void write(List<DDSpan> trace) {
      serializedChunks.add(serialize(trace));
      super.write(trace);
    }

    private static SerializedChunk serialize(List<DDSpan> trace) {
      TraceMapperV0_4 mapper = new TraceMapperV0_4();
      CaptureBuffer capture = new CaptureBuffer();
      MsgPackWriter packer = new MsgPackWriter(new FlushingBuffer(16 * 1024, capture));
      assertTrue(packer.format(trace, mapper));
      packer.flush();

      try (MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(capture.bytes)) {
        assertEquals(1, unpacker.unpackArrayHeader());
        int fieldCount = unpacker.unpackMapHeader();
        Map<String, Number> metrics = new HashMap<>();
        Map<String, String> meta = new HashMap<>();

        for (int i = 0; i < fieldCount; i++) {
          String field = unpacker.unpackString();
          if ("metrics".equals(field)) {
            int size = unpacker.unpackMapHeader();
            for (int j = 0; j < size; j++) {
              metrics.put(unpacker.unpackString(), unpacker.unpackValue().asNumberValue().toInt());
            }
          } else if ("meta".equals(field)) {
            int size = unpacker.unpackMapHeader();
            for (int j = 0; j < size; j++) {
              meta.put(unpacker.unpackString(), unpacker.unpackString());
            }
          } else {
            unpacker.unpackValue();
          }
        }
        return new SerializedChunk(metrics, meta);
      } catch (Exception e) {
        throw new IllegalStateException("Unable to decode serialized trace chunk", e);
      } finally {
        mapper.reset();
      }
    }
  }

  private static final class CaptureBuffer implements ByteBufferConsumer {
    private byte[] bytes;

    @Override
    public void accept(int messageCount, ByteBuffer buffer) {
      assertEquals(1, messageCount);
      bytes = new byte[buffer.remaining()];
      buffer.get(bytes);
    }
  }

  private static final class SerializedChunk {
    private final Map<String, Number> metrics;
    private final Map<String, String> meta;

    private SerializedChunk(Map<String, Number> metrics, Map<String, String> meta) {
      this.metrics = metrics;
      this.meta = meta;
    }
  }
}
