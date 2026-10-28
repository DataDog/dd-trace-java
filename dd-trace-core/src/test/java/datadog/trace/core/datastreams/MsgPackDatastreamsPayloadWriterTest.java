package datadog.trace.core.datastreams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.WellKnownTags;
import datadog.trace.common.metrics.EventListener;
import datadog.trace.common.metrics.Sink;
import java.nio.ByteBuffer;
import java.util.ArrayList;
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

  private static int payloadSize() {
    CapturingSink sink = new CapturingSink();
    new MsgPackDatastreamsPayloadWriter(sink, TAGS, "1.0", null)
        .writePayload(Collections.<StatsBucket>emptyList(), null);
    assertEquals(1, sink.payloads.size());
    return sink.payloads.get(0);
  }

  /** Records the size of each payload it receives. */
  private static final class CapturingSink implements Sink {
    final List<Integer> payloads = new ArrayList<>();

    @Override
    public void accept(int messageCount, List<ByteBuffer> chunks) {
      int size = 0;
      for (ByteBuffer chunk : chunks) {
        size += chunk.remaining();
      }
      payloads.add(size);
    }

    @Override
    public void register(EventListener listener) {}
  }
}
