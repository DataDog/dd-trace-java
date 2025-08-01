package datadog.trace.bootstrap.instrumentation.buffer;

import java.io.IOException;
import java.io.OutputStream;

/**
 * An OutputStream containing a circular buffer with a lookbehind buffer of n bytes. The first time
 * that the latest n bytes matches the marker, a content is injected before.
 */
public class InjectingPipeOutputStream extends OutputStream {
  private final byte[] lookbehind;
  private int pos;
  private boolean bufferFilled;
  private final byte[] marker;
  private final byte[] contentToInject;
  private boolean found = false;
  private int matchingPos = 0;
  private final Runnable onContentInjected;
  private final int bulkWriteThreshold;
  private final OutputStream downstream;

  /**
   * @param downstream the delegate output stream
   * @param marker the marker to find in the stream. Must at least be one byte.
   * @param contentToInject the content to inject once before the marker if found.
   * @param onContentInjected callback called when and if the content is injected.
   */
  public InjectingPipeOutputStream(
      final OutputStream downstream,
      final byte[] marker,
      final byte[] contentToInject,
      final Runnable onContentInjected) {
    this.downstream = downstream;
    this.marker = marker;
    this.lookbehind = new byte[marker.length];
    this.pos = 0;
    this.contentToInject = contentToInject;
    this.onContentInjected = onContentInjected;
    this.bulkWriteThreshold = marker.length * 2 - 2;
  }

  @Override
  public void write(int b) throws IOException {
    if (found) {
      downstream.write(b);
      return;
    }

    if (bufferFilled) {
      downstream.write(lookbehind[pos]);
    }

    lookbehind[pos] = (byte) b;
    pos = (pos + 1) % lookbehind.length;

    if (!bufferFilled) {
      bufferFilled = pos == 0;
    }

    if (marker[matchingPos++] == b) {
      if (matchingPos == marker.length) {
        found = true;
        downstream.write(contentToInject);
        if (onContentInjected != null) {
          onContentInjected.run();
        }
        drain();
      }
    } else {
      matchingPos = 0;
    }
  }

  @Override
  public void write(byte[] array, int off, int len) throws IOException {
    if (found) {
      downstream.write(array, off, len);
      return;
    }
    if (len > bulkWriteThreshold) {
      // if the content is large enough, we can bulk write everything but the N trail and tail.
      // This because the buffer can already contain some byte from a previous single write.
      // Also we need to fill the buffer with the tail since we don't know about the next write.
      int idx = arrayContains(array, off, len, marker);
      if (idx >= 0) {
        // we have a full match. just write everything
        found = true;
        drain();
        downstream.write(array, off, idx);
        downstream.write(contentToInject);
        if (onContentInjected != null) {
          onContentInjected.run();
        }
        downstream.write(array, off + idx, len - idx);
      } else {
        // we don't have a full match. write everything in a bulk except the lookbehind buffer
        // sequentially
        for (int i = off; i < off + marker.length - 1; i++) {
          write(array[i]);
        }
        drain();
        downstream.write(array, off + marker.length - 1, len - bulkWriteThreshold);
        for (int i = len - marker.length + 1; i < len; i++) {
          write(array[i]);
        }
      }
    } else {
      // use slow path because the length to write is small and within the lookbehind buffer size
      for (int i = off; i < off + len; i++) {
        write(array[i]);
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

  private void drain() throws IOException {
    if (bufferFilled) {
      for (int i = 0; i < lookbehind.length; i++) {
        downstream.write(lookbehind[(pos + i) % lookbehind.length]);
      }
    } else {
      downstream.write(this.lookbehind, 0, pos);
    }
    pos = 0;
    matchingPos = 0;
    bufferFilled = false;
  }

  @Override
  public void flush() throws IOException {
    downstream.flush();
  }

  @Override
  public void close() throws IOException {
    if (!found) {
      drain();
    }
    downstream.close();
  }
}
