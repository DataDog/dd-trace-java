package datadog.trace.bootstrap.instrumentation.buffer;

import java.io.IOException;

/**
 * The matching half of RUM injection for char streams: decides <em>where</em> to inject by driving
 * an {@link InjectingCharBuffer}. Implementations are stateful (one per stream) and not thread
 * safe.
 *
 * @see LiteralCharMatcher literal marker matching
 * @see HtmlCharMatcher structure-aware HTML parsing
 */
public interface CharMatcher {
  /**
   * @return the lookbehind buffer size this matcher needs.
   */
  int lookbehindSize();

  /** Processes a single char, driving the buffer as needed. */
  void write(int c, InjectingCharBuffer buffer) throws IOException;

  /** Processes a range of chars, driving the buffer as needed. */
  void write(char[] array, int off, int len, InjectingCharBuffer buffer) throws IOException;
}
