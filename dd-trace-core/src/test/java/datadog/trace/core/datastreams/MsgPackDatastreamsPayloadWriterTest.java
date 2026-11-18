package datadog.trace.core.datastreams;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.WellKnownTags;
import datadog.trace.api.datastreams.DataStreamsTags;
import datadog.trace.api.datastreams.StatsPoint;
import datadog.trace.common.metrics.EventListener;
import datadog.trace.common.metrics.Sink;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class MsgPackDatastreamsPayloadWriterTest {

  private static final WellKnownTags TAGS =
      new WellKnownTags("runtimeid", "hostname", "env", "service", "version", "language");

  @Test
  void payloadOverTheCapIsDroppedWithoutThrowing() {
    int size = payloadSize();

    CapturingSink tooSmall = new CapturingSink();
    new MsgPackDatastreamsPayloadWriter(tooSmall, TAGS, "1.0", null, size - 1)
        .writePayload(Collections.<StatsBucket>emptyList(), null);
    assertTrue(tooSmall.payloads.isEmpty());

    CapturingSink exact = new CapturingSink();
    new MsgPackDatastreamsPayloadWriter(exact, TAGS, "1.0", null, size)
        .writePayload(Collections.<StatsBucket>emptyList(), null);
    assertEquals(Collections.singletonList(size), exact.payloads);
  }

  @Test
  void aPayloadAfterADroppedOneIsIntact() {
    byte[] expected = emptyPayloadBytes();

    // one writer, reused as in production, whose cap fits only the empty payload
    CapturingSink sink = new CapturingSink();
    MsgPackDatastreamsPayloadWriter writer =
        new MsgPackDatastreamsPayloadWriter(sink, TAGS, "1.0", null, expected.length);
    writer.writePayload(Collections.singletonList(bucketWithGroups(50)), null); // overflows
    assertTrue(sink.payloads.isEmpty());

    writer.writePayload(Collections.<StatsBucket>emptyList(), null);
    assertEquals(1, sink.bytes.size());
    assertArrayEquals(expected, sink.bytes.get(0));
  }

  @Test
  void aPayloadOverflowingPartwayThroughItsBucketsIsDropped() {
    byte[] expected = emptyPayloadBytes();
    int oneGroup = payloadBytes(Collections.singletonList(bucketWithGroups(1))).length;

    // fits the header and the first group, overflows inside writeBucket on a later one
    CapturingSink sink = new CapturingSink();
    MsgPackDatastreamsPayloadWriter writer =
        new MsgPackDatastreamsPayloadWriter(sink, TAGS, "1.0", null, oneGroup + 8);
    writer.writePayload(Collections.singletonList(bucketWithGroups(20)), null);
    assertTrue(sink.payloads.isEmpty());

    writer.writePayload(Collections.<StatsBucket>emptyList(), null);
    assertArrayEquals(expected, sink.bytes.get(0));
  }

  @Test
  void aPayloadAfterAFailedWriteIsIntact() {
    byte[] expected = emptyPayloadBytes();

    CapturingSink sink = new CapturingSink();
    MsgPackDatastreamsPayloadWriter writer =
        new MsgPackDatastreamsPayloadWriter(sink, TAGS, "1.0", null);
    StatsBucket failing =
        new StatsBucket(0, 10) {
          @Override
          public Collection<StatsGroup> getGroups() {
            throw new IllegalStateException("bucket failed mid-payload");
          }
        };
    assertThrows(
        IllegalStateException.class,
        () -> writer.writePayload(Collections.singletonList(failing), null));

    writer.writePayload(Collections.<StatsBucket>emptyList(), null);
    assertEquals(1, sink.bytes.size());
    assertArrayEquals(expected, sink.bytes.get(0));
  }

  private static byte[] emptyPayloadBytes() {
    return payloadBytes(Collections.<StatsBucket>emptyList());
  }

  /** The bytes a fresh writer, with the default cap, sends for {@code data}. */
  private static byte[] payloadBytes(List<StatsBucket> data) {
    CapturingSink sink = new CapturingSink();
    new MsgPackDatastreamsPayloadWriter(sink, TAGS, "1.0", null).writePayload(data, null);
    assertEquals(1, sink.bytes.size());
    return sink.bytes.get(0);
  }

  /** A bucket with {@code groups} distinct, first-node groups. */
  private static StatsBucket bucketWithGroups(int groups) {
    StatsBucket bucket = new StatsBucket(0, 10);
    for (int i = 1; i <= groups; ++i) {
      bucket.addPoint(new StatsPoint(DataStreamsTags.EMPTY, i, 0, i, 0, 1, 2, 3, null));
    }
    return bucket;
  }

  private static int payloadSize() {
    CapturingSink sink = new CapturingSink();
    new MsgPackDatastreamsPayloadWriter(sink, TAGS, "1.0", null)
        .writePayload(Collections.<StatsBucket>emptyList(), null);
    assertEquals(1, sink.payloads.size());
    return sink.payloads.get(0);
  }

  /** Records the size and bytes of each payload it receives. */
  private static final class CapturingSink implements Sink {
    final List<Integer> payloads = new ArrayList<>();
    final List<byte[]> bytes = new ArrayList<>();

    @Override
    public void accept(int messageCount, List<ByteBuffer> chunks) {
      int size = 0;
      for (ByteBuffer chunk : chunks) {
        size += chunk.remaining();
      }
      byte[] payload = new byte[size];
      int at = 0;
      for (ByteBuffer chunk : chunks) {
        int n = chunk.remaining();
        chunk.duplicate().get(payload, at, n);
        at += n;
      }
      payloads.add(size);
      bytes.add(payload);
    }

    @Override
    public void register(EventListener listener) {}
  }
}
