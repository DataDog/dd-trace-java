package datadog.trace.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SizeCheckedInputStreamTest {

  private final ByteArrayInputStream stream = mock(ByteArrayInputStream.class);

  @Test
  void sizedCheckedStreamDoesNotReadMoreThanItNeeds() throws IOException {
    SizeCheckedInputStream sizedStream = new SizeCheckedInputStream(stream, 500);
    byte[] buffer = new byte[1000];
    when(stream.read(any(byte[].class), eq(0), eq(500))).thenReturn(500);

    sizedStream.read(buffer);

    verify(stream, times(1)).read(any(byte[].class), eq(0), eq(500));

    IOException exception = assertThrows(IOException.class, () -> sizedStream.read(buffer));
    assertEquals("Reached maximum bytes for this stream: 500", exception.getMessage());
  }

  @Test
  void sizedCheckedStreamReadMinBufferSizeLeftCapacity() throws IOException {
    SizeCheckedInputStream sizedStream = new SizeCheckedInputStream(stream, 499);
    byte[] buffer = new byte[100];
    when(stream.read(any(byte[].class), eq(0), eq(100))).thenReturn(100);
    when(stream.read(any(byte[].class), eq(0), eq(99))).thenReturn(99);

    sizedStream.read(buffer);
    sizedStream.read(buffer);
    sizedStream.read(buffer);
    sizedStream.read(buffer);
    sizedStream.read(buffer);

    verify(stream, times(4)).read(any(byte[].class), eq(0), eq(100));
    verify(stream, times(1)).read(any(byte[].class), eq(0), eq(99));

    IOException exception = assertThrows(IOException.class, () -> sizedStream.read(buffer));
    assertEquals("Reached maximum bytes for this stream: 499", exception.getMessage());
  }

  @Test
  void sizedCheckedStreamRespectEndOfStream() throws IOException {
    SizeCheckedInputStream sizedStream = new SizeCheckedInputStream(stream, 100);
    byte[] buffer = new byte[100];
    when(stream.read(any(byte[].class), eq(0), eq(100))).thenReturn(70);
    when(stream.read(any(byte[].class), eq(0), eq(30))).thenReturn(-1);

    sizedStream.read(buffer);
    sizedStream.read(buffer);
    sizedStream.read(buffer);

    verify(stream, times(1)).read(any(byte[].class), eq(0), eq(100));
    verify(stream, times(2)).read(any(byte[].class), eq(0), eq(30));
  }

  @Test
  void sizedCheckedStreamCreatedByteByByte() throws IOException {
    SizeCheckedInputStream sizedStream = new SizeCheckedInputStream(stream, 200);
    AtomicInteger callCount = new AtomicInteger();
    // returns 1 for the first 100 calls, -1 on the 101st call, then 1 again
    when(stream.read()).thenAnswer(invocation -> callCount.getAndIncrement() == 100 ? -1 : 1);

    int firstPassReads = 0;
    while (sizedStream.read() != -1) {
      firstPassReads++;
    }
    assertEquals(100, firstPassReads);

    AtomicInteger secondPassReads = new AtomicInteger();
    IOException exception =
        assertThrows(
            IOException.class,
            () -> {
              while (sizedStream.read() != -1) {
                secondPassReads.incrementAndGet();
              }
            });
    assertEquals(100, secondPassReads.get());
    assertEquals("Reached maximum bytes for this stream: 200", exception.getMessage());
  }
}
