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
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import java.nio.ByteBuffer;
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
    List<String> emitted = roundTripAdditionalTags(emptyList());
    assertNull(emitted);
  }

  @Test
  void populatedAdditionalTagsAreEmittedInOrder() throws Exception {
    List<UTF8BytesString> tags =
        Arrays.asList(
            UTF8BytesString.create("region:us-east-1"), UTF8BytesString.create("tenant_id:acme"));
    List<String> emitted = roundTripAdditionalTags(tags);
    assertEquals(Arrays.asList("region:us-east-1", "tenant_id:acme"), emitted);
  }

  private List<String> roundTripAdditionalTags(List<UTF8BytesString> additionalTags)
      throws Exception {
    WellKnownTags wellKnownTags =
        new WellKnownTags("runtimeid", "hostname", "env", "service", "version", "language");
    CapturingSink sink = new CapturingSink();
    SerializingMetricWriter writer = new SerializingMetricWriter(wellKnownTags, sink, 128);
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
            additionalTags);
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
