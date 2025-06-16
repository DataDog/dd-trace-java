package com.datadog.profiling.uploader;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import org.junit.jupiter.api.Test;

public class ByteCountingInputStreamTest {

  @Test
  public void testDoesNotCountAfterEof() throws IOException {
    byte[] data = {1, 2, 3};

    ByteCountingInputStream in = new ByteCountingInputStream(new ByteArrayInputStream(data));
    for (byte datum : data) {
      int b = in.read();
      assertEquals(datum, b);
    }
    assertEquals(data.length, (int) in.getReadBytes());

    // read past EOF
    assertEquals(-1, in.read());
    assertEquals(data.length, (int) in.getReadBytes());
  }
}
