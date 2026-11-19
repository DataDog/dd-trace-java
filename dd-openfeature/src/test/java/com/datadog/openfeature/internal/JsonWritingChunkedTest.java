package com.datadog.openfeature.internal;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.util.ChunkedBuffer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

/** Writing JSON documents as messages of a {@link ChunkedBuffer}. */
class JsonWritingChunkedTest {

  @Test
  void writesADocumentAsOneMessage() {
    final ChunkedBuffer buffer = new ChunkedBuffer(1 << 20);

    assertTrue(JsonWriting.write(buffer, generator -> generator.writeNumber(1)));
    assertTrue(JsonWriting.write(buffer, generator -> generator.writeString("x")));

    assertEquals(2, buffer.messageCount());
    assertEquals("1\"x\"", contents(buffer));
  }

  @Test
  void rewindsAPartialMessageWhenTheWriterFails() {
    final ChunkedBuffer buffer = new ChunkedBuffer(1 << 20);
    JsonWriting.write(buffer, generator -> generator.writeNumber(1));

    assertThrows(
        UncheckedIOException.class,
        () ->
            JsonWriting.write(
                buffer,
                generator -> {
                  generator.writeStartArray();
                  generator.writeNumber(2);
                  generator.flush();
                  throw new IOException("failed");
                }));
    assertThrows(
        IllegalStateException.class,
        () ->
            JsonWriting.write(
                buffer,
                generator -> {
                  generator.writeString("partial");
                  generator.flush();
                  throw new IllegalStateException("failed");
                }));

    assertEquals(1, buffer.messageCount());
    assertEquals("1", contents(buffer));
  }

  @Test
  void writesSingleBytes() {
    final ChunkedBuffer buffer = new ChunkedBuffer(1 << 20);
    final JsonWriting.ChunkedOutput output = new JsonWriting.ChunkedOutput(buffer);

    output.write('[');
    output.write(new byte[] {'1', ']'}, 0, 2);
    buffer.mark();

    assertEquals("[1]", contents(buffer));
  }

  private static String contents(final ChunkedBuffer buffer) {
    final StringBuilder s = new StringBuilder();
    for (final ByteBuffer chunk : buffer.completedChunks()) {
      final byte[] bytes = new byte[chunk.remaining()];
      chunk.duplicate().get(bytes);
      s.append(new String(bytes, UTF_8));
    }
    return s.toString();
  }
}
