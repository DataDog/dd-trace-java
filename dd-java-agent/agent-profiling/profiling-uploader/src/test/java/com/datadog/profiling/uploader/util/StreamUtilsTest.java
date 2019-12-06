package com.datadog.profiling.uploader.util;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class StreamUtilsTest {

  final StreamUtils.BytesConsumer<byte[]> CONSUME_TO_BYTES =
      (bytes, offset, length) -> Arrays.copyOfRange(bytes, offset, offset + length);

  @Test
  void gzipStream() throws IOException {
    final InputStream is = StreamUtilsTest.class.getResourceAsStream("/test-recording.jfr");
    assertNotNull(is);
    final byte[] original = StreamUtils.readStream(is, 10, CONSUME_TO_BYTES);

    final byte[] zipped =
        StreamUtils.isCompressed(is)
            ? original
            : StreamUtils.zipStream(new ByteArrayInputStream(original), 10, CONSUME_TO_BYTES);
    assertNotNull(zipped);

    final ByteArrayInputStream zippedStream = new ByteArrayInputStream(zipped);

    zippedStream.mark(IOToolkit.GZ_MAGIC.length + 1);
    assertTrue(IOToolkit.hasMagic(zippedStream, IOToolkit.GZ_MAGIC));
    zippedStream.reset();

    final InputStream uncompressed = IOToolkit.openUncompressedStream(zippedStream);
    final byte[] buffer = new byte[1024];
    int read = -1;
    int pos = 0;
    while ((read = uncompressed.read(buffer)) > 0) {
      assertArrayEquals(Arrays.copyOf(buffer, read), Arrays.copyOfRange(original, pos, pos + read));
      pos += read;
    }
  }
}
