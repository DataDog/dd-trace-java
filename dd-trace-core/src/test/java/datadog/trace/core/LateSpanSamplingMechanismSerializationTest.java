package datadog.trace.core;

import static datadog.trace.api.sampling.PrioritySampling.USER_KEEP;
import static datadog.trace.api.sampling.SamplingMechanism.REMOTE_ADAPTIVE_RULE;
import static datadog.trace.common.sampling.RuleBasedTraceSampler.SAMPLING_RULE_RATE;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.communication.serialization.GrowableBuffer;
import datadog.communication.serialization.msgpack.MsgPackWriter;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.common.writer.ddagent.TraceMapperV0_4;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessageUnpacker;
import org.msgpack.value.MapValue;
import org.msgpack.value.Value;
import org.msgpack.value.ValueFactory;

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

      MapValue rootChunk = writer.serializedSpans.get(0);
      MapValue lateChunk = writer.serializedSpans.get(1);

      assertEquals(
          (int) USER_KEEP,
          numberValue(rootChunk, "metrics", DDSpanContext.PRIORITY_SAMPLING_KEY).intValue());
      assertEquals("-12", stringValue(rootChunk, "meta", "_dd.p.dm"));

      assertEquals(
          (int) USER_KEEP,
          numberValue(lateChunk, "metrics", DDSpanContext.PRIORITY_SAMPLING_KEY).intValue());
      // The late chunk's decision maker must match its propagated trace-level sampling priority.
      assertEquals("-12", stringValue(lateChunk, "meta", "_dd.p.dm"));
    } finally {
      tracer.close();
    }
  }

  private static Number numberValue(MapValue span, String mapName, String key) {
    Value value = value(span, mapName, key);
    return value == null ? null : value.asNumberValue().toInt();
  }

  private static String stringValue(MapValue span, String mapName, String key) {
    Value value = value(span, mapName, key);
    return value == null ? null : value.asStringValue().asString();
  }

  private static Value value(MapValue span, String mapName, String key) {
    Value map = span.map().get(ValueFactory.newString(mapName));
    return map.asMapValue().map().get(ValueFactory.newString(key));
  }

  private static final class SerializingWriter extends ListWriter {
    private final List<MapValue> serializedSpans = new ArrayList<>();

    @Override
    public void write(List<DDSpan> trace) {
      serializedSpans.add(serialize(trace));
      super.write(trace);
    }

    private static MapValue serialize(List<DDSpan> trace) {
      TraceMapperV0_4 mapper = new TraceMapperV0_4();
      GrowableBuffer buffer = new GrowableBuffer(16 * 1024);
      MsgPackWriter packer = new MsgPackWriter(buffer);
      assertTrue(packer.format(trace, mapper));

      try (MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(buffer.slice())) {
        return unpacker.unpackValue().asArrayValue().get(0).asMapValue();
      } catch (IOException e) {
        throw new IllegalStateException("Unable to decode serialized trace chunk", e);
      } finally {
        mapper.reset();
      }
    }
  }
}
