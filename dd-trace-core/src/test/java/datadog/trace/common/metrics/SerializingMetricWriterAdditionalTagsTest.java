package datadog.trace.common.metrics;

import static java.util.Collections.emptyList;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import datadog.metrics.api.Histograms;
import datadog.metrics.impl.DDSketchHistograms;
import datadog.trace.api.WellKnownTags;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLongArray;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessageUnpacker;

class SerializingMetricWriterAdditionalTagsTest {

  @BeforeAll
  static void registerHistograms() {
    Histograms.register(DDSketchHistograms.FACTORY);
  }

  @Test
  void emptyAdditionalTagsOmitTheField() throws Exception {
    List<String> emitted =
        roundTripAdditionalTags(new String[] {"region", "tenant_id"}, new String[] {null, null});
    assertNull(emitted);
  }

  @Test
  void populatedAdditionalTagsAreEmittedInOrder() throws Exception {
    String[] tagKeys = {"region", "tenant_id"};
    String[] values = {"us-east-1", "acme"};
    List<String> emitted = roundTripAdditionalTags(tagKeys, values);
    assertEquals(Arrays.asList("region:us-east-1", "tenant_id:acme"), emitted);
  }

  @Test
  void partiallyPopulatedTagsOnlyEmitNonNullEntries() throws Exception {
    String[] tagKeys = {"region", "tenant_id"};
    String[] values = {null, "acme"};
    List<String> emitted = roundTripAdditionalTags(tagKeys, values);
    assertEquals(Arrays.asList("tenant_id:acme"), emitted);
  }

  private List<String> roundTripAdditionalTags(String[] tagKeys, String[] values) throws Exception {
    WellKnownTags wellKnownTags =
        new WellKnownTags("runtimeid", "hostname", "env", "service", "version", "language");
    CapturingSink sink = new CapturingSink();
    byte[][] tagKeyBytes = new byte[tagKeys.length][];
    for (int i = 0; i < tagKeys.length; i++) {
      tagKeyBytes[i] = tagKeys[i].getBytes(StandardCharsets.UTF_8);
    }
    SerializingMetricWriter writer =
        new SerializingMetricWriter(wellKnownTags, tagKeyBytes, sink, 128);
    MetricKey key =
        new MetricKey(
            "resource",
            "service",
            "operation",
            null,
            "web",
            200,
            false,
            false,
            "server",
            emptyList(),
            null,
            null,
            null,
            values);
    AggregateMetric aggregate =
        new AggregateMetric().recordDurations(1, new AtomicLongArray(new long[] {1L}));
    long start = MILLISECONDS.toNanos(System.currentTimeMillis());
    long duration = SECONDS.toNanos(10);
    writer.startBucket(1, start, duration);
    writer.add(key, aggregate);
    writer.finishBucket();
    assertNotNull(sink.captured);
    return readAdditionalTagsFromPayload(sink.captured);
  }

  private static List<String> readAdditionalTagsFromPayload(ByteBuffer buffer) throws Exception {
    MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(buffer);
    int envelopeSize = unpacker.unpackMapHeader();
    for (int i = 0; i < envelopeSize; i++) {
      String envelopeKey = unpacker.unpackString();
      if ("Stats".equals(envelopeKey)) {
        int bucketCount = unpacker.unpackArrayHeader();
        assertEquals(1, bucketCount);
        int bucketMapSize = unpacker.unpackMapHeader();
        for (int b = 0; b < bucketMapSize; b++) {
          String bk = unpacker.unpackString();
          if ("Stats".equals(bk)) {
            int statCount = unpacker.unpackArrayHeader();
            assertEquals(1, statCount);
            int metricMapSize = unpacker.unpackMapHeader();
            for (int m = 0; m < metricMapSize; m++) {
              String mk = unpacker.unpackString();
              if ("AdditionalMetricTags".equals(mk)) {
                int tagCount = unpacker.unpackArrayHeader();
                List<String> result = new ArrayList<>(tagCount);
                for (int t = 0; t < tagCount; t++) {
                  result.add(unpacker.unpackString());
                }
                return result;
              } else {
                unpacker.skipValue();
              }
            }
          } else {
            unpacker.skipValue();
          }
        }
      } else {
        unpacker.skipValue();
      }
    }
    return null;
  }

  private static final class CapturingSink implements Sink {
    ByteBuffer captured;
    final List<EventListener> listeners = new ArrayList<>();

    @Override
    public void register(EventListener listener) {
      listeners.add(listener);
    }

    @Override
    public void accept(int messageCount, ByteBuffer buffer) {
      this.captured = buffer;
    }
  }
}
