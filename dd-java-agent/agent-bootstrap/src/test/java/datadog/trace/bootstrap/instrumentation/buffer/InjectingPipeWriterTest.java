package datadog.trace.bootstrap.instrumentation.buffer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.longThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.io.FilterWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class InjectingPipeWriterTest {
  static final char[] MARKER_CHARS = "</head>".toCharArray();
  static final char[] CONTEXT_CHARS = "<script></script>".toCharArray();

  static class GlitchedWriter extends FilterWriter {
    int glitchesPos;
    int count;

    GlitchedWriter(Writer out, int glitchesPos) {
      super(out);
      this.glitchesPos = glitchesPos;
    }

    @Override
    public void write(char[] c, int off, int len) throws IOException {
      count += len;
      if (count >= glitchesPos) {
        glitchesPos = Integer.MAX_VALUE;
        throw new IOException("Glitched after " + count + " bytes");
      }
      out.write(c, off, len);
    }

    @Override
    public void write(int c) throws IOException {
      if (++count == glitchesPos) {
        throw new IOException("Glitched after " + glitchesPos + " bytes");
      }
      out.write(c);
    }
  }

  @TableTest({
    "scenario      | body                                      | marker            | contentToInject         | expected                                                      ",
    "found         | '<html><head><foo/></head><body/></html>' | '</head>'         | '<script>true</script>' | '<html><head><foo/><script>true</script></head><body/></html>'",
    "not found     | '<html><body/></html>'                    | '</head>'         | '<something/>'          | '<html><body/></html>'                                        ",
    "longer marker | '<foo/>'                                  | '<longerThanFoo>' | '<nothing>'             | '<foo/>'                                                      "
  })
  void shouldFilterABufferAndInjectIfFoundUsingWrite(
      String body, String marker, String contentToInject, String expected) {
    StringWriter downstream = new StringWriter();
    try (PrintWriter piped =
        new PrintWriter(
            new InjectingPipeWriter(
                downstream, marker.toCharArray(), contentToInject.toCharArray()))) {
      piped.write(body);
    }
    assertEquals(expected, downstream.toString());
  }

  @TableTest({
    "scenario      | body                                      | marker            | contentToInject         | expected                                                      ",
    "found         | '<html><head><foo/></head><body/></html>' | '</head>'         | '<script>true</script>' | '<html><head><foo/><script>true</script></head><body/></html>'",
    "not found     | '<html><body/></html>'                    | '</head>'         | '<something/>'          | '<html><body/></html>'                                        ",
    "longer marker | '<foo/>'                                  | '<longerThanFoo>' | '<nothing>'             | '<foo/>'                                                      "
  })
  void shouldFilterABufferAndInjectIfFoundUsingAppend(
      String body, String marker, String contentToInject, String expected) {
    StringWriter downstream = new StringWriter();
    try (PrintWriter piped =
        new PrintWriter(
            new InjectingPipeWriter(
                downstream, marker.toCharArray(), contentToInject.toCharArray()))) {
      piped.append(body);
    }
    assertEquals(expected, downstream.toString());
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
    StringWriter writer = new StringWriter();
    GlitchedWriter downstream = new GlitchedWriter(writer, glitchesAt);
    InjectingPipeWriter piped =
        new InjectingPipeWriter(downstream, marker.toCharArray(), contentToInject.toCharArray());
    try {
      for (String line : body) {
        char[] chars = line.toCharArray();
        try {
          piped.write(chars);
        } catch (IOException ioe) {
          ioe.printStackTrace();
          piped.write(chars);
        }
      }
    } finally {
      // it can throw when draining at close
      try {
        piped.close();
      } catch (IOException ignored) {
      }
    }
    assertEquals(expected, writer.toString());
  }

  @Test
  void shouldCountBytesCorrectlyWhenWritingCharacters() throws IOException {
    StringWriter downstream = new StringWriter();
    AtomicLong counter = new AtomicLong();
    InjectingPipeWriter piped =
        new InjectingPipeWriter(
            downstream, MARKER_CHARS, CONTEXT_CHARS, null, counter::addAndGet, null);
    String testContent = "test content";

    piped.write(testContent.toCharArray());
    piped.close();

    assertEquals(testContent.length(), counter.get());
    assertEquals(testContent, downstream.toString());
  }

  @Test
  void shouldCountBytesCorrectlyWhenWritingCharactersIndividually() throws IOException {
    StringWriter downstream = new StringWriter();
    AtomicLong counter = new AtomicLong();
    InjectingPipeWriter piped =
        new InjectingPipeWriter(
            downstream, MARKER_CHARS, CONTEXT_CHARS, null, counter::addAndGet, null);
    String testContent = "test";

    for (char testChar : testContent.toCharArray()) {
      piped.write(testChar);
    }
    piped.close();

    assertEquals(testContent.length(), counter.get());
    assertEquals(testContent, downstream.toString());
  }

  @Test
  void shouldCountBytesCorrectlyWithMultipleWrites() throws IOException {
    StringWriter downstream = new StringWriter();
    AtomicLong counter = new AtomicLong();
    InjectingPipeWriter piped =
        new InjectingPipeWriter(
            downstream, MARKER_CHARS, CONTEXT_CHARS, null, counter::addAndGet, null);
    String testContent = "test content";

    piped.write(testContent.substring(0, 5).toCharArray());
    piped.write(testContent.substring(5, 6).toCharArray());
    piped.write(testContent.substring(6).toCharArray());
    piped.close();

    assertEquals(testContent.length(), counter.get());
    assertEquals(testContent, downstream.toString());
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
    char[] source = (prefix + payload + "ignored-suffix").toCharArray();
    StringWriter downstream = new StringWriter();
    AtomicLong counter = new AtomicLong();
    AtomicInteger injections = new AtomicInteger();
    InjectingPipeWriter piped =
        new InjectingPipeWriter(
            downstream,
            MARKER_CHARS,
            CONTEXT_CHARS,
            injections::incrementAndGet,
            counter::addAndGet,
            null);

    piped.write(source, prefix.length(), payload.length());
    piped.close();

    assertEquals(expected, downstream.toString());
    assertEquals(expectedInjections, injections.get());
    assertEquals(payload.length(), counter.get());
  }

  @Test
  void shouldPrioritizeAMarkerSpanningABulkWriteBoundary() throws IOException {
    String prefix = "ignored-prefix";
    String payload = ">0123456789</head>";
    char[] source = (prefix + payload + "ignored-suffix").toCharArray();
    StringWriter downstream = new StringWriter();
    InjectingPipeWriter piped = new InjectingPipeWriter(downstream, MARKER_CHARS, CONTEXT_CHARS);

    piped.write("abc</head".toCharArray());
    piped.write(source, prefix.length(), payload.length());
    piped.close();

    assertEquals("abc<script></script></head>0123456789</head>", downstream.toString());
  }

  @Test
  void shouldBeResilientToExceptionsWhenOnBytesWrittenCallbackIsNull() {
    StringWriter downstream = new StringWriter();
    InjectingPipeWriter piped = new InjectingPipeWriter(downstream, MARKER_CHARS, CONTEXT_CHARS);
    String testContent = "test content";

    assertDoesNotThrow(() -> {
      piped.write(testContent.toCharArray());
      piped.close();
    });
    assertEquals(testContent, downstream.toString());
  }

  @Test
  void shouldCallTimingCallbackWhenInjectionHappens() throws IOException {
    Writer downstream = mock(Writer.class);
    doAnswer(
            invocation -> {
              Thread.sleep(1); // simulate slow write
              return null;
            })
        .when(downstream)
        .write(any(char[].class));
    LongConsumer timingCallback = mock(LongConsumer.class);
    InjectingPipeWriter piped =
        new InjectingPipeWriter(
            downstream, MARKER_CHARS, CONTEXT_CHARS, null, null, timingCallback);

    piped.write("<html><head></head><body></body></html>".toCharArray());
    piped.close();

    verify(timingCallback).accept(longThat(duration -> duration > 0));
  }

  @Test
  void shouldNotCallTimingCallbackWhenNoInjectionHappens() throws IOException {
    StringWriter downstream = new StringWriter();
    LongConsumer timingCallback = mock(LongConsumer.class);
    InjectingPipeWriter piped =
        new InjectingPipeWriter(
            downstream, MARKER_CHARS, CONTEXT_CHARS, null, null, timingCallback);

    piped.write("no marker here".toCharArray());
    piped.close();

    verify(timingCallback, never()).accept(anyLong());
  }

  @Test
  void shouldBeResilientToExceptionsWhenTimingCallbackIsNull() {
    StringWriter downstream = new StringWriter();
    InjectingPipeWriter piped =
        new InjectingPipeWriter(downstream, MARKER_CHARS, CONTEXT_CHARS, null, null, null);

    assertDoesNotThrow(() -> {
      piped.write("<html><head></head><body></body></html>".toCharArray());
      piped.close();
    });
  }
}
