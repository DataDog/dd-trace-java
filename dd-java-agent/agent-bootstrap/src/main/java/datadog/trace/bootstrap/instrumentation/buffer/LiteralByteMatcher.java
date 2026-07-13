package datadog.trace.bootstrap.instrumentation.buffer;

import java.io.IOException;
import javax.annotation.concurrent.NotThreadSafe;

/**
 * Injects content right before the first literal occurrence of a fixed marker (e.g. {@code
 * </head>}), using a rolling match over a sliding lookbehind window the size of the marker. This is
 * the original RUM injection algorithm; only the buffering has been factored out into {@link
 * InjectingByteBuffer}.
 */
@NotThreadSafe
public final class LiteralByteMatcher implements ByteMatcher {
  private final byte[] marker;
  private int matchingPos;
  private final int bulkWriteThreshold;

  public LiteralByteMatcher(final byte[] marker) {
    this.marker = marker;
    this.matchingPos = 0;
    this.bulkWriteThreshold = marker.length * 2 - 2;
  }

  @Override
  public int lookbehindSize() {
    return marker.length;
  }

  @Override
  public void write(int b, InjectingByteBuffer buffer) throws IOException {
    if (!buffer.isFiltering()) {
      buffer.passthrough(b);
      return;
    }

    buffer.hold(b);

    if (marker[matchingPos++] == b) {
      if (matchingPos == marker.length) {
        buffer.setFilter(false);
        buffer.writeContent();
        buffer.drain();
      }
    } else {
      matchingPos = 0;
    }
  }

  @Override
  public void write(byte[] array, int off, int len, InjectingByteBuffer buffer) throws IOException {
    if (!buffer.isFiltering()) {
      buffer.passthrough(array, off, len);
      return;
    }

    if (len > bulkWriteThreshold) {
      // if the content is large enough, we can bulk write everything but the N trail and tail.
      // This because the buffer can already contain some byte from a previous single write.
      // Also we need to fill the buffer with the tail since we don't know about the next write.
      int idx = arrayContains(array, off, len, marker);
      if (idx >= 0) {
        // we have a full match. just write everything
        buffer.setFilter(false);
        buffer.drain();
        buffer.writeThrough(array, off, idx);
        buffer.writeContent();
        buffer.writeThrough(array, off + idx, len - idx);
      } else {
        // we don't have a full match. write everything in a bulk except the lookbehind buffer
        // sequentially
        for (int i = off; i < off + marker.length - 1; i++) {
          write(array[i], buffer);
        }
        buffer.drain();
        boolean wasFiltering = buffer.isFiltering();

        // will be reset if no errors after the following write
        buffer.setFilter(false);
        buffer.writeThrough(array, off + marker.length - 1, len - bulkWriteThreshold);
        buffer.setFilter(wasFiltering);
        for (int i = len - marker.length + 1; i < len; i++) {
          write(array[i], buffer);
        }
      }
    } else {
      // use slow path because the length to write is small and within the lookbehind buffer size
      for (int i = off; i < off + len; i++) {
        write(array[i], buffer);
      }
    }
  }

  private int arrayContains(byte[] array, int off, int len, byte[] search) {
    for (int i = off; i < len - search.length; i++) {
      if (array[i] == search[0]) {
        boolean found = true;
        int k = i;
        for (int j = 1; j < search.length; j++) {
          k++;
          if (array[k] != search[j]) {
            found = false;
            break;
          }
        }
        if (found) {
          return i;
        }
      }
    }
    return -1;
  }
}
