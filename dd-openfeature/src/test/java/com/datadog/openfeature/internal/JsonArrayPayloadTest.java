package com.datadog.openfeature.internal;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.List;
import org.junit.jupiter.api.Test;

class JsonArrayPayloadTest {
  private static final byte[] PREFIX = "{\"events\":[".getBytes(UTF_8);
  private static final byte[] SUFFIX = "]}".getBytes(UTF_8);

  @Test
  void writesEventsAsOneArrayBetweenThePrefixAndSuffix() {
    final JsonArrayPayload payload = new JsonArrayPayload(PREFIX, SUFFIX, 1 << 16);
    assertEquals("{\"events\":[]}", text(payload.body()));

    assertTrue(payload.append(generator -> generator.writeNumber(1)));
    assertTrue(payload.append(generator -> generator.writeString("two")));
    assertEquals(2, payload.count());
    assertEquals("{\"events\":[1,\"two\"]}", text(payload.body()));
  }

  @Test
  void leavesThePayloadUnchangedWhenAnEventDoesNotFit() {
    // Room for the prefix, the suffix and 10 bytes of events.
    final JsonArrayPayload payload =
        new JsonArrayPayload(PREFIX, SUFFIX, PREFIX.length + SUFFIX.length + 10);
    assertTrue(payload.append(generator -> generator.writeString("abc")));
    assertFalse(payload.append(generator -> generator.writeString("too long to fit")));
    assertEquals(1, payload.count());
    assertEquals("{\"events\":[\"abc\"]}", text(payload.body()));
  }

  @Test
  void leavesThePayloadUnchangedWhenTheWriterFails() {
    final JsonArrayPayload payload = new JsonArrayPayload(PREFIX, SUFFIX, 1 << 16);
    assertTrue(payload.append(generator -> generator.writeNumber(1)));
    assertThrows(
        IllegalStateException.class,
        () ->
            payload.append(generator -> {
              generator.writeStartObject();
              throw new IllegalStateException("unserializable");
            }));
    assertEquals(1, payload.count());
    assertEquals("{\"events\":[1]}", text(payload.body()));
  }

  @Test
  void resetReusesTheBuffersAndDiscardKeepsTheOldBodyIntact() {
    final JsonArrayPayload payload = new JsonArrayPayload(PREFIX, SUFFIX, 1 << 16);
    payload.append(generator -> generator.writeString("first"));
    payload.reset();
    assertEquals(0, payload.count());
    assertEquals("{\"events\":[]}", text(payload.body()));

    payload.append(generator -> generator.writeString("posted"));
    final List<ByteBuffer> inFlight = payload.body();
    payload.discard(); // the post failed, but a timed-out request may still read inFlight
    payload.append(generator -> generator.writeString("overwritten?"));
    assertEquals("{\"events\":[\"posted\"]}", text(inFlight));
    assertEquals("{\"events\":[\"overwritten?\"]}", text(payload.body()));
  }

  private static String text(final List<ByteBuffer> body) {
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (final ByteBuffer chunk : body) {
      final ByteBuffer view = chunk.duplicate();
      final byte[] bytes = new byte[view.remaining()];
      view.get(bytes);
      out.write(bytes, 0, bytes.length);
    }
    return new String(out.toByteArray(), UTF_8);
  }
}
