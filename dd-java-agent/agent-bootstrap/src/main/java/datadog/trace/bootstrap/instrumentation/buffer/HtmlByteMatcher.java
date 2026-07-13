package datadog.trace.bootstrap.instrumentation.buffer;

import java.io.IOException;
import javax.annotation.concurrent.NotThreadSafe;

/**
 * Injects content right before the real {@code </head>} end tag located by a structure-aware {@link
 * HtmlHeadMatcher} (comment/script/style/CDATA aware, case- and whitespace-tolerant), driving an
 * {@link InjectingByteBuffer}.
 */
@NotThreadSafe
public final class HtmlByteMatcher implements ByteMatcher {
  private final HtmlHeadMatcher head = new HtmlHeadMatcher();

  @Override
  public int lookbehindSize() {
    return HtmlHeadMatcher.LOOKBEHIND_SIZE;
  }

  @Override
  public void write(int b, InjectingByteBuffer buffer) throws IOException {
    if (!buffer.isFiltering()) {
      buffer.passthrough(b);
      return;
    }
    apply(head.accept(b & 0xFF), b, buffer);
  }

  @Override
  public void write(byte[] array, int off, int len, InjectingByteBuffer buffer) throws IOException {
    if (!buffer.isFiltering()) {
      buffer.passthrough(array, off, len);
      return;
    }
    final int end = off + len;
    int i = off;
    while (i < end) {
      if (head.inData()) {
        // Fast path: in the plain data state we can bulk-emit everything up to the next '<'.
        int runEnd = i;
        while (runEnd < end && array[runEnd] != '<') {
          runEnd++;
        }
        if (runEnd > i) {
          buffer.writeThrough(array, i, runEnd - i);
          i = runEnd;
          if (i == end) {
            break;
          }
        }
      }
      apply(head.accept(array[i] & 0xFF), array[i], buffer);
      i++;
      if (!buffer.isFiltering()) {
        // Injection point reached: pass the remainder through in bulk.
        break;
      }
    }
    if (i < end) {
      buffer.writeThrough(array, i, end - i);
    }
  }

  private static void apply(final int action, final int unit, final InjectingByteBuffer buffer)
      throws IOException {
    switch (action) {
      case HtmlHeadMatcher.EMIT:
        buffer.emit(unit);
        break;
      case HtmlHeadMatcher.HOLD:
        buffer.hold(unit);
        break;
      case HtmlHeadMatcher.FLUSH_THEN_EMIT:
        buffer.drain();
        buffer.emit(unit);
        break;
      case HtmlHeadMatcher.FLUSH_THEN_HOLD:
        buffer.drain();
        buffer.hold(unit);
        break;
      case HtmlHeadMatcher.HOLD_THEN_INJECT:
        buffer.hold(unit);
        buffer.setFilter(false);
        buffer.writeContent();
        buffer.drain();
        break;
      default:
        break;
    }
  }
}
