package com.datadog.debugger.poller;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

class SizeCheckedInputStreamTest {
  private static final byte[] BUFFER_10 = "0123456789".getBytes();

  @Test
  public void maxSize_0() {
    IOException ioException =
        assertThrows(
            IOException.class,
            () -> {
              try (InputStream in =
                  new SizeCheckedInputStream(new ByteArrayInputStream(BUFFER_10), 0)) {
                in.read();
              }
            });
    assertEquals("Reached maximum bytes for this stream: 0", ioException.getMessage());
  }

  @Test
  public void maxSize_10() throws IOException {
    try (InputStream in = new SizeCheckedInputStream(new ByteArrayInputStream(BUFFER_10), 10)) {
      for (int i = 0; i < 10; i++) {
        assertTrue(in.read() > -1);
      }
    }
    try (InputStream in = new SizeCheckedInputStream(new ByteArrayInputStream(BUFFER_10), 10)) {
      byte[] buffer = new byte[10];
      assertEquals(10, in.read(buffer));
    }
  }

  @Test
  public void maxSize_10_throwsSimpleRead() {
    IOException ioException =
        assertThrows(
            IOException.class,
            () -> {
              try (InputStream in =
                  new SizeCheckedInputStream(new ByteArrayInputStream(BUFFER_10), 10)) {
                for (int i = 0; i < 10; i++) {
                  assertTrue(in.read() > -1);
                }
                in.read();
              }
            });
    assertEquals("Reached maximum bytes for this stream: 10", ioException.getMessage());
  }

  @Test
  public void maxSize_10_throwsBufferRead() {
    IOException ioException =
        assertThrows(
            IOException.class,
            () -> {
              try (InputStream in =
                  new SizeCheckedInputStream(new ByteArrayInputStream(BUFFER_10), 10)) {
                byte[] buffer = new byte[10];
                assertEquals(10, in.read(buffer));
                in.read(buffer);
              }
            });
    assertEquals("Reached maximum bytes for this stream: 10", ioException.getMessage());
  }
}
