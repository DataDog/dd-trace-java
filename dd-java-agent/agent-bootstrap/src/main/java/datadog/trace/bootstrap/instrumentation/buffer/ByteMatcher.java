package datadog.trace.bootstrap.instrumentation.buffer;

import java.io.IOException;

/**
 * The matching half of RUM injection for byte streams: decides <em>where</em> to inject by driving
 * an {@link InjectingByteBuffer}. Implementations are stateful (one per stream) and not thread
 * safe.
 *
 * @see LiteralByteMatcher literal marker matching
 * @see HtmlByteMatcher structure-aware HTML parsing
 */
public interface ByteMatcher {
  /**
   * @return the lookbehind buffer size this matcher needs.
   */
  int lookbehindSize();

  /** Processes a single byte, driving the buffer as needed. */
  void write(int b, InjectingByteBuffer buffer) throws IOException;

  /** Processes a range of bytes, driving the buffer as needed. */
  void write(byte[] array, int off, int len, InjectingByteBuffer buffer) throws IOException;
}
