package datadog.smoketest;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Deterministic reproduction for the dominant flake of "check raw file injection" in
 * LogInjectionSmokeTest (31 of 41 reports in CI Visibility):
 *
 * <pre>
 * java.lang.IndexOutOfBoundsException: toIndex = 3
 *   at java.util.AbstractList.subListRangeCheck(...)
 *   at datadog.smoketest.LogInjectionSmokeTest.parseTraceFromStdOut(LogInjectionSmokeTest.groovy:416)
 * </pre>
 *
 * <p>Root cause lives in {@link OutputThreads.ProcessOutputRunnable#run()}: when {@code
 * rc.read(buffer)} returns a chunk with no newline AND the inner loop has consumed no lines yet,
 * the fall-through branch decodes the partial buffer and adds it to {@code testLogMessages} as if
 * it were a complete line. The next read delivers the remainder of the same logical line, which is
 * then added as another "line".
 *
 * <p>In the smoke test this turns a single child-process println of {@code "THIRDTRACEID <traceId>
 * <spanId>\n"} into two captured "lines" — {@code "THIRDTRACEID 12345"} and {@code " 67890"} — when
 * the OS pipe splits the write under CI load. The smoke test's {@code stdOutLines.find {
 * it.contains("THIRDTRACEID") }} then returns the truncated first chunk, and {@code split("
 * ")[1..2]} throws IOOBE.
 *
 * <p>The tests below assert the buggy behavior and pass today. When {@link OutputThreads} is fixed
 * to buffer partial lines until a newline arrives, {@link
 * #partialFirstReadIsIncorrectlyTreatedAsCompleteLine} will start failing — turning this into a
 * regression test for the fix.
 */
class OutputThreadsTest {

  @Test
  void singleCompleteLineIsCapturedAsOneMessage(@TempDir Path tempDir) throws Exception {
    List<String> msgs =
        capture(new ByteArrayInputStream("THIRDTRACEID 12345 67890\n".getBytes()), tempDir);

    assertEquals(1, msgs.size(), "messages: " + msgs);
    assertEquals("THIRDTRACEID 12345 67890", msgs.get(0));
  }

  @Test
  void partialFirstReadIsIncorrectlyTreatedAsCompleteLine(@TempDir Path tempDir) throws Exception {
    // First chunk has no newline; second chunk completes the line. A correct implementation
    // would emit "THIRDTRACEID 12345 67890" as a single message.
    List<String> msgs = capture(new ChunkedInputStream("THIRDTRACEID 12345", " 67890\n"), tempDir);

    assertEquals(
        2,
        msgs.size(),
        "expected the buggy behavior to split one line into two; messages: " + msgs);
    assertEquals("THIRDTRACEID 12345", msgs.get(0));
    assertEquals("67890", msgs.get(1));
  }

  private static List<String> capture(InputStream is, Path tempDir) throws Exception {
    File outFile = tempDir.resolve("out.log").toFile();
    OutputThreads threads = new OutputThreads();
    OutputThreads.ProcessOutputRunnable r = threads.new ProcessOutputRunnable(is, outFile);
    try {
      r.run();
      return new ArrayList<>(threads.testLogMessages);
    } finally {
      // ProcessOutputRunnable holds a FileOutputStream-backed channel that production code
      // never closes (the JVM closes it at process exit). Closing here keeps Windows
      // @TempDir cleanup from emitting IOException noise.
      r.rc.close();
      r.wc.close();
      threads.close();
    }
  }

  /**
   * Returns each pre-supplied chunk on a separate read() call, so the consumer observes the exact
   * byte boundaries that cause the partial-line bug.
   */
  private static final class ChunkedInputStream extends InputStream {
    private final String[] chunks;
    private int chunkIdx = 0;
    private int offset = 0;

    ChunkedInputStream(String... chunks) {
      this.chunks = chunks;
    }

    @Override
    public int read() {
      while (chunkIdx < chunks.length) {
        String c = chunks[chunkIdx];
        if (offset < c.length()) {
          return c.charAt(offset++) & 0xff;
        }
        chunkIdx++;
        offset = 0;
      }
      return -1;
    }

    // read(byte[], int, int) returns ONLY the bytes from the current chunk, even if the buffer
    // has room for more. This is what the OS pipe does under load.
    @Override
    public int read(byte[] b, int off, int len) {
      if (chunkIdx >= chunks.length) {
        return -1;
      }
      String c = chunks[chunkIdx];
      int remaining = c.length() - offset;
      int n = Math.min(len, remaining);
      for (int i = 0; i < n; i++) {
        b[off + i] = (byte) c.charAt(offset + i);
      }
      offset += n;
      if (offset >= c.length()) {
        chunkIdx++;
        offset = 0;
      }
      return n;
    }
  }
}
