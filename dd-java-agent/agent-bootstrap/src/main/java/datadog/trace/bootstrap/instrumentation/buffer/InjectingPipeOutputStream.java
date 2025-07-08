package datadog.trace.bootstrap.instrumentation.buffer;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * A circular buffer with a lookbehind buffer of n bytes. The first time that the latest n bytes
 * matches the marker, a content is injected before.
 */
public class InjectingPipeOutputStream extends FilterOutputStream {
  private final byte[] lookbehind;
  private int pos;
  private boolean bufferFilled;
  private final byte[] marker;
  private final byte[] contentToInject;
  private boolean found = false;
  private int matchingPos = 0;
  private final Runnable onContentInjected;

  /**
   * @param downstream the delegate output stream
   * @param marker the marker to find in the stream
   * @param contentToInject the content to inject once before the marker if found.
   * @param onContentInjected callback called when and if the content is injected.
   */
  public InjectingPipeOutputStream(
      final OutputStream downstream,
      final byte[] marker,
      final byte[] contentToInject,
      final Runnable onContentInjected) {
    super(downstream);
    this.marker = marker;
    this.lookbehind = new byte[marker.length];
    this.pos = 0;
    this.contentToInject = contentToInject;
    this.onContentInjected = onContentInjected;
  }

  @Override
  public void write(int b) throws IOException {
    if (found) {
      out.write(b);
      return;
    }

    if (bufferFilled) {
      out.write(lookbehind[pos]);
    }

    lookbehind[pos] = (byte) b;
    pos = (pos + 1) % lookbehind.length;

    if (!bufferFilled) {
      bufferFilled = pos == 0;
    }

    if (marker[matchingPos++] == b) {
      if (matchingPos == marker.length) {
        found = true;
        out.write(contentToInject);
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
  public void write(byte[] b, int off, int len) throws IOException {
    if (found) {
      out.write(b, off, len);
      return;
    }
    if (len > marker.length * 2 - 2) {
      // if the content is large enough, we can bulk write everything but the N trail and tail.
      // This because the buffer can already contain some byte from a previous single write.
      // Also we need to fill the buffer with the tail since we don't know about the next write.
      int idx = arrayContains(b, marker);
      if (idx >= 0) {
        // we have a full match. just write everything
        found = true;
        drain();
        out.write(b, off, idx);
        out.write(contentToInject);
        if (onContentInjected != null) {
          onContentInjected.run();
        }
        out.write(b, off + idx, len - idx);
      } else {
        // we don't have a full match. write everything in a bulk except the lookbehind buffer
        // sequentially
        for (int i = off; i < off + marker.length - 1; i++) {
          write(b[i]);
        }
        drain();
        out.write(b, off + marker.length - 1, len - marker.length * 2 + 2);
        for (int i = len - marker.length + 1; i < len; i++) {
          write(b[i]);
        }
      }
    } else {
      // use slow path because the length to write is small and within the lookbehind buffer size
      super.write(b, off, len);
    }
  }

  private int arrayContains(byte[] array, byte[] search) {
    for (int i = 0; i < array.length - search.length; i++) {
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
        out.write(lookbehind[(pos + i) % lookbehind.length]);
      }
    } else {
      out.write(this.lookbehind, 0, pos);
    }
    pos = 0;
    matchingPos = 0;
    bufferFilled = false;
  }

  @Override
  public void close() throws IOException {
    if (!found) {
      drain();
    }
    super.close();
  }
}
