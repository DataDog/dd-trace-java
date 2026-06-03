package datadog.trace.common.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import datadog.metrics.agent.AgentMeter;
import datadog.metrics.api.Histograms;
import datadog.metrics.api.statsd.StatsDClient;
import datadog.metrics.impl.DDSketchHistograms;
import datadog.metrics.impl.MonitoringImpl;
import datadog.trace.api.WellKnownTags;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessageUnpacker;

/**
 * Verifies the {@code AdditionalMetricTags} wire field: shape is {@code repeated string} of {@code
 * "key:value"} entries; field is omitted when no slots are populated; null slots within a populated
 * array are skipped.
 */
class SerializingMetricWriterAdditionalTagsTest {

  @BeforeAll
  static void initAgentMeter() {
    MonitoringImpl monitoring = new MonitoringImpl(StatsDClient.NO_OP, 1, TimeUnit.SECONDS);
    AgentMeter.registerIfAbsent(StatsDClient.NO_OP, monitoring, DDSketchHistograms.FACTORY);
    monitoring.newTimer("test.init");
    Histograms.register(DDSketchHistograms.FACTORY);
  }

  @Test
  void additionalMetricTagsEmittedWhenSet() throws Exception {
    AdditionalTagsSchema schema =
        AdditionalTagsSchema.from(new LinkedHashSet<>(Arrays.asList("region", "tenant_id")));
    AggregateTable table = newTable(schema);

    AggregateEntry entry = table.findOrInsert(snapshot(schema, "us-east-1", "acme-corp"));
    entry.recordOneDuration(1L);

    List<String> additionalTags = parseAdditionalMetricTags(writeBucket(table));
    assertEquals(2, additionalTags.size());
    // Order matches schema (alphabetical): region first, then tenant_id.
    assertEquals("region:us-east-1", additionalTags.get(0));
    assertEquals("tenant_id:acme-corp", additionalTags.get(1));
  }

  @Test
  void additionalMetricTagsFieldOmittedWhenNoneSet() throws Exception {
    // Schema configured, but the span doesn't set any of the configured tags.
    AdditionalTagsSchema schema =
        AdditionalTagsSchema.from(new LinkedHashSet<>(Arrays.asList("region")));
    AggregateTable table = newTable(schema);

    AggregateEntry entry = table.findOrInsert(snapshot(schema, new String[] {null}));
    entry.recordOneDuration(1L);

    assertFalse(
        containsKey(writeBucket(table), "AdditionalMetricTags"),
        "AdditionalMetricTags should be omitted when no slots are populated");
  }

  @Test
  void additionalMetricTagsSkipsNullSlots() throws Exception {
    AdditionalTagsSchema schema =
        AdditionalTagsSchema.from(new LinkedHashSet<>(Arrays.asList("region", "tenant_id")));
    AggregateTable table = newTable(schema);

    // Set only tenant_id; leave region null.
    AggregateEntry entry =
        table.findOrInsert(
            snapshot(
                schema,
                new String[] {
                  /*region*/
                  null, /*tenant_id*/ "acme-corp"
                }));
    entry.recordOneDuration(1L);

    List<String> additionalTags = parseAdditionalMetricTags(writeBucket(table));
    assertEquals(1, additionalTags.size());
    assertEquals("tenant_id:acme-corp", additionalTags.get(0));
  }

  // ---------- helpers ----------

  private static AggregateTable newTable(AdditionalTagsSchema schema) {
    return new AggregateTable(64, schema);
  }

  private static SpanSnapshot snapshot(AdditionalTagsSchema schema, String... values) {
    String[] padded = new String[schema.size()];
    if (values != null) {
      System.arraycopy(values, 0, padded, 0, Math.min(values.length, padded.length));
    }
    return new SpanSnapshot(
        "resource",
        "service",
        "operation",
        null,
        "web",
        (short) 200,
        false,
        true,
        "client",
        null,
        null,
        null,
        null,
        null,
        padded,
        0L);
  }

  /**
   * Serializes a single-bucket payload via {@link SerializingMetricWriter} into a {@link
   * ByteBuffer}. The test's {@link CapturingSink} keeps the produced buffer for unpack.
   */
  private static ByteBuffer writeBucket(AggregateTable table) {
    CapturingSink sink = new CapturingSink();
    SerializingMetricWriter writer =
        new SerializingMetricWriter(
            new WellKnownTags("rid", "host", "env", "svc", "ver", "lang"), sink, 64 * 1024);
    writer.startBucket(table.size(), 0L, TimeUnit.SECONDS.toNanos(10));
    table.forEach(writer::add);
    writer.finishBucket();
    return sink.buffer;
  }

  private static List<String> parseAdditionalMetricTags(ByteBuffer payload) throws Exception {
    MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(payload);
    // Top-level map: skip to the per-stat entry. Structure mirrors SerializingMetricWriterTest.
    int topMapSize = unpacker.unpackMapHeader();
    for (int i = 0; i < topMapSize; i++) {
      String key = unpacker.unpackString();
      if ("Stats".equals(key)) {
        // Stats is a 1-element array of buckets; each bucket has Start/Duration/Stats(=array of
        // per-metric maps).
        unpacker.unpackArrayHeader();
        int bucketMapSize = unpacker.unpackMapHeader();
        for (int j = 0; j < bucketMapSize; j++) {
          String bucketKey = unpacker.unpackString();
          if ("Stats".equals(bucketKey)) {
            int statsCount = unpacker.unpackArrayHeader();
            // Take the first stat entry and walk its map looking for AdditionalMetricTags.
            for (int k = 0; k < statsCount; k++) {
              int entryMapSize = unpacker.unpackMapHeader();
              for (int m = 0; m < entryMapSize; m++) {
                String entryKey = unpacker.unpackString();
                if ("AdditionalMetricTags".equals(entryKey)) {
                  int n = unpacker.unpackArrayHeader();
                  List<String> result = new ArrayList<>(n);
                  for (int p = 0; p < n; p++) {
                    result.add(unpacker.unpackString());
                  }
                  return result;
                } else {
                  unpacker.skipValue();
                }
              }
              if (k == 0) break; // only inspecting the first stat entry
            }
          } else {
            unpacker.skipValue();
          }
        }
      } else {
        unpacker.skipValue();
      }
    }
    return new ArrayList<>();
  }

  private static boolean containsKey(ByteBuffer payload, String soughtKey) throws Exception {
    MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(payload);
    int topMapSize = unpacker.unpackMapHeader();
    for (int i = 0; i < topMapSize; i++) {
      String key = unpacker.unpackString();
      if ("Stats".equals(key)) {
        unpacker.unpackArrayHeader();
        int bucketMapSize = unpacker.unpackMapHeader();
        for (int j = 0; j < bucketMapSize; j++) {
          String bucketKey = unpacker.unpackString();
          if ("Stats".equals(bucketKey)) {
            int statsCount = unpacker.unpackArrayHeader();
            for (int k = 0; k < statsCount; k++) {
              int entryMapSize = unpacker.unpackMapHeader();
              for (int m = 0; m < entryMapSize; m++) {
                String entryKey = unpacker.unpackString();
                if (soughtKey.equals(entryKey)) {
                  return true;
                }
                unpacker.skipValue();
              }
              if (k == 0) return false; // checked the only entry
            }
          } else {
            unpacker.skipValue();
          }
        }
      } else {
        unpacker.skipValue();
      }
    }
    return false;
  }

  private static final class CapturingSink implements Sink {
    ByteBuffer buffer;

    @Override
    public void register(EventListener listener) {}

    @Override
    public void accept(int messageCount, ByteBuffer buffer) {
      this.buffer = buffer.duplicate();
    }
  }
}
