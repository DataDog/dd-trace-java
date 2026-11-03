package datadog.trace.bootstrap.instrumentation.buffer;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.longThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import datadog.trace.test.util.DDJavaSpecification;
import java.io.ByteArrayOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class InjectingPipeOutputStreamTest extends DDJavaSpecification {
  static final byte[] MARKER_BYTES = "</head>".getBytes(UTF_8);
  static final byte[] CONTEXT_BYTES = "<script></script>".getBytes(UTF_8);

  static class GlitchedOutputStream extends FilterOutputStream {
    int glitchesPos;
    int count;

    GlitchedOutputStream(OutputStream out, int glitchesPos) {
      super(out);
      this.glitchesPos = glitchesPos;
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
      count += len;
      if (count >= glitchesPos) {
        glitchesPos = Integer.MAX_VALUE;
        throw new IOException("Glitched after " + count + " bytes");
      }
      out.write(b, off, len);
    }

    @Override
    public void write(int b) throws IOException {
      if (++count == glitchesPos) {
        throw new IOException("Glitched after " + glitchesPos + " bytes");
      }
      out.write(b);
    }
  }

  @TableTest({
    "scenario      | body                                      | marker            | contentToInject         | expected                                                      ",
    "found         | '<html><head><foo/></head><body/></html>' | '</head>'         | '<script>true</script>' | '<html><head><foo/><script>true</script></head><body/></html>'",
    "not found     | '<html><body/></html>'                    | '</head>'         | '<something/>'          | '<html><body/></html>'                                        ",
    "longer marker | '<foo/>'                                  | '<longerThanFoo>' | '<nothing>'             | '<foo/>'                                                      "
  })
  void shouldFilterABufferAndInjectIfFound(
      String body, String marker, String contentToInject, String expected) throws IOException {
    ByteArrayOutputStream downstream = new ByteArrayOutputStream();
    try (Writer piped =
        new OutputStreamWriter(
            new InjectingPipeOutputStream(
                downstream, marker.getBytes(UTF_8), contentToInject.getBytes(UTF_8)),
            UTF_8)) {
      piped.write(body);
    }
    assertArrayEquals(expected.getBytes(UTF_8), downstream.toByteArray());
  }

  @TableTest({
    "scenario                  | body                                                            | marker            | contentToInject         | glitchesAt | expected                                                      ",
    // write fails after the content has been injected
    "fails after injection     | ['<html>', '<head>', '<foo/>', '</head>', '<body/>', '</html>'] | '</head>'         | '<script>true</script>' | 60         | '<html><head><foo/><script>true</script></head><body/></html>'",
    // write fails before the content has been injected
    "fails before injection    | ['<html>', '<head>', '<foo/>', '</head>', '<body/>', '</html>'] | '</head>'         | '<script>true</script>' | 20         | '<html><head><foo/></head><body/></html>'                     ",
    // write fails after having filled the buffer. The last line is written twice
    "fails after buffer filled | ['<html>', '<body/>', '</html>']                                | '</head>'         | '<something/>'          | 10         | '<html><body/></h</html>'                                     ",
    // expected broken since the real write happens at close (drain) being the content smaller
    // than the buffer. And retry on close is not a common practice. Hence, we suppose loosing
    // content
    "fails at close            | ['<foo/>']                                                      | '<longerThanFoo>' | '<nothing>'             | 3          | '<f'                                                          "
  })
  void shouldBeResilientToExceptionsWhenWriting(
      List<String> body, String marker, String contentToInject, int glitchesAt, String expected)
      throws IOException {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    GlitchedOutputStream downstream = new GlitchedOutputStream(baos, glitchesAt);
    InjectingPipeOutputStream piped =
        new InjectingPipeOutputStream(
            downstream, marker.getBytes(UTF_8), contentToInject.getBytes(UTF_8));
    try {
      for (String line : body) {
        byte[] bytes = line.getBytes(UTF_8);
        try {
          piped.write(bytes);
        } catch (IOException ioe) {
          ioe.printStackTrace();
          piped.write(bytes);
        }
      }
    } finally {
      // it can throw when draining at close
      try {
        piped.close();
      } catch (IOException ignored) {
      }
    }
    assertArrayEquals(expected.getBytes(UTF_8), baos.toByteArray());
  }

  @Test
  void shouldCountBytesCorrectlyWhenWritingByteArrays() throws IOException {
    byte[] testBytes = "test content".getBytes(UTF_8);
    ByteArrayOutputStream downstream = new ByteArrayOutputStream();
    AtomicLong counter = new AtomicLong();
    InjectingPipeOutputStream piped =
        new InjectingPipeOutputStream(
            downstream, MARKER_BYTES, CONTEXT_BYTES, null, counter::addAndGet, null);

    piped.write(testBytes);
    piped.close();

    assertEquals(testBytes.length, counter.get());
    assertArrayEquals(testBytes, downstream.toByteArray());
  }

  @Test
  void shouldCountBytesCorrectlyWhenWritingBytesIndividually() throws IOException {
    byte[] testBytes = "test".getBytes(UTF_8);
    ByteArrayOutputStream downstream = new ByteArrayOutputStream();
    AtomicLong counter = new AtomicLong();
    InjectingPipeOutputStream piped =
        new InjectingPipeOutputStream(
            downstream, MARKER_BYTES, CONTEXT_BYTES, null, counter::addAndGet, null);

    for (byte testByte : testBytes) {
      piped.write(testByte);
    }
    piped.close();

    assertEquals(testBytes.length, counter.get());
    assertArrayEquals(testBytes, downstream.toByteArray());
  }

  @Test
  void shouldCountBytesCorrectlyWithMultipleWrites() throws IOException {
    String testContent = "test content";
    ByteArrayOutputStream downstream = new ByteArrayOutputStream();
    AtomicLong counter = new AtomicLong();
    InjectingPipeOutputStream piped =
        new InjectingPipeOutputStream(
            downstream, MARKER_BYTES, CONTEXT_BYTES, null, counter::addAndGet, null);

    piped.write(testContent.substring(0, 5).getBytes(UTF_8));
    piped.write(testContent.substring(5, 6).getBytes(UTF_8));
    piped.write(testContent.substring(6).getBytes(UTF_8));
    piped.close();

    assertEquals(testContent.length(), counter.get());
    assertArrayEquals(testContent.getBytes(UTF_8), downstream.toByteArray());
  }

  @TableTest({
    "scenario                 | payload                                              | expected                                                              | expectedInjections",
    "without a marker         | '<html><body>safe</body></html>'                     | '<html><body>safe</body></html>'                                      | 0                 ",
    "with a marker            | '<html><head>dynamic</head><body>safe</body></html>' | '<html><head>dynamic<script></script></head><body>safe</body></html>' | 1                 ",
    "with a marker at the end | '<html><head>dynamic-content</head>'                 | '<html><head>dynamic-content<script></script></head>'                 | 1                 "
  })
  void shouldHonorNonZeroOffsetsInBulkWrites(
      String payload, String expected, int expectedInjections) throws IOException {
    String prefix = "ignored-prefix";
    byte[] payloadBytes = payload.getBytes(UTF_8);
    byte[] source = (prefix + payload + "ignored-suffix").getBytes(UTF_8);
    ByteArrayOutputStream downstream = new ByteArrayOutputStream();
    AtomicLong counter = new AtomicLong();
    AtomicInteger injections = new AtomicInteger();
    InjectingPipeOutputStream piped =
        new InjectingPipeOutputStream(
            downstream,
            MARKER_BYTES,
            CONTEXT_BYTES,
            injections::incrementAndGet,
            counter::addAndGet,
            null);

    piped.write(source, prefix.getBytes(UTF_8).length, payloadBytes.length);
    piped.close();

    assertArrayEquals(expected.getBytes(UTF_8), downstream.toByteArray());
    assertEquals(expectedInjections, injections.get());
    assertEquals(payloadBytes.length, counter.get());
  }

  @Test
  void shouldPrioritizeAMarkerSpanningABulkWriteBoundary() throws IOException {
    String prefix = "ignored-prefix";
    String payload = ">0123456789</head>";
    byte[] source = (prefix + payload + "ignored-suffix").getBytes(UTF_8);
    ByteArrayOutputStream downstream = new ByteArrayOutputStream();
    InjectingPipeOutputStream piped =
        new InjectingPipeOutputStream(downstream, MARKER_BYTES, CONTEXT_BYTES);

    piped.write("abc</head".getBytes(UTF_8));
    piped.write(source, prefix.getBytes(UTF_8).length, payload.getBytes(UTF_8).length);
    piped.close();

    assertArrayEquals(
        "abc<script></script></head>0123456789</head>".getBytes(UTF_8), downstream.toByteArray());
  }

  @Test
  void shouldBeResilientToExceptionsWhenOnBytesWrittenCallbackIsNull() {
    byte[] testBytes = "test content".getBytes(UTF_8);
    ByteArrayOutputStream downstream = new ByteArrayOutputStream();
    InjectingPipeOutputStream piped =
        new InjectingPipeOutputStream(downstream, MARKER_BYTES, CONTEXT_BYTES);

    assertDoesNotThrow(
        () -> {
          piped.write(testBytes);
          piped.close();
        });
    assertArrayEquals(testBytes, downstream.toByteArray());
  }

  @Test
  void shouldCallTimingCallbackWhenInjectionHappens() throws IOException {
    OutputStream downstream = mock(OutputStream.class);
    doAnswer(
            invocation -> {
              Thread.sleep(1); // simulate slow write
              return null;
            })
        .when(downstream)
        .write(any(byte[].class));
    LongConsumer timingCallback = mock(LongConsumer.class);
    InjectingPipeOutputStream piped =
        new InjectingPipeOutputStream(
            downstream, MARKER_BYTES, CONTEXT_BYTES, null, null, timingCallback);

    piped.write("<html><head></head><body></body></html>".getBytes(UTF_8));
    piped.close();

    verify(timingCallback).accept(longThat(duration -> duration > 0));
  }

  @Test
  void shouldNotCallTimingCallbackWhenNoInjectionHappens() throws IOException {
    ByteArrayOutputStream downstream = new ByteArrayOutputStream();
    LongConsumer timingCallback = mock(LongConsumer.class);
    InjectingPipeOutputStream piped =
        new InjectingPipeOutputStream(
            downstream, MARKER_BYTES, CONTEXT_BYTES, null, null, timingCallback);

    piped.write("no marker here".getBytes(UTF_8));
    piped.close();

    verify(timingCallback, never()).accept(anyLong());
  }

  @Test
  void shouldBeResilientToExceptionsWhenTimingCallbackIsNull() {
    ByteArrayOutputStream downstream = new ByteArrayOutputStream();
    InjectingPipeOutputStream piped =
        new InjectingPipeOutputStream(downstream, MARKER_BYTES, CONTEXT_BYTES, null, null, null);

    assertDoesNotThrow(
        () -> {
          piped.write("<html><head></head><body></body></html>".getBytes(UTF_8));
          piped.close();
        });
  }
}
