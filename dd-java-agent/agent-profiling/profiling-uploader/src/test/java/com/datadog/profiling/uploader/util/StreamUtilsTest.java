package com.datadog.profiling.uploader.util;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class StreamUtilsTest {

  @Test
  void gzipStream() throws IOException {
    InputStream is = StreamUtilsTest.class.getResourceAsStream("/test-recording.jfr");
    assertNotNull(is);
    byte[] original = StreamUtils.readStream(is);

    byte[] zipped =
        StreamUtils.isCompressed(is)
            ? original
            : StreamUtils.zipStream(new ByteArrayInputStream(original));
    assertNotNull(zipped);

    ByteArrayInputStream zippedStream = new ByteArrayInputStream(zipped);

    zippedStream.mark(IOToolkit.GZ_MAGIC.length + 1);
    assertTrue(IOToolkit.hasMagic(zippedStream, IOToolkit.GZ_MAGIC));
    zippedStream.reset();

    InputStream uncompressed = IOToolkit.openUncompressedStream(zippedStream);
    byte[] buffer = new byte[1024];
    int read = -1;
    int pos = 0;
    while ((read = uncompressed.read(buffer)) > 0) {
      assertArrayEquals(Arrays.copyOf(buffer, read), Arrays.copyOfRange(original, pos, pos + read));
      pos += read;
    }
  }
}
