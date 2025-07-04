package datadog.trace.bootstrap.instrumentation.buffer;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.function.Consumer;

/**
 * A circular buffer that holds n+1 bytes and with a lookbehind buffer of n bytes. The first time
 * that the latest n bytes matches the marker, a content is injected before.
 */
public class InjectingPipeOutputStream extends FilterOutputStream {
  private final byte[] lookbehind;
  private int pos;
  private boolean bufferFilled;
  private final byte[] marker;
  private final byte[] contentToInject;
  private boolean found = false;
  private int matchingPos = 0;
  private final Consumer<Void> onContentInjected;

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
      final Consumer<Void> onContentInjected) {
    super(downstream);
    this.marker = marker;
    this.lookbehind = new byte[marker.length + 1];
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
    lookbehind[pos] = (byte) b;
    pos = (pos + 1) % lookbehind.length;

    if (marker[matchingPos++] == b) {
      if (matchingPos == marker.length) {
        found = true;
        out.write(contentToInject);
        if (onContentInjected != null) {
          onContentInjected.accept(null);
        }
        drain((pos + 1) % lookbehind.length, marker.length);
        return;
      }
    } else {
      matchingPos = 0;
    }

    if (!bufferFilled) {
      bufferFilled = pos == lookbehind.length - 1;
    }

    if (bufferFilled) {
      super.write(lookbehind[(pos + 1) % lookbehind.length]);
    }
  }

  private void drain(int from, int size) throws IOException {
    while (size-- > 0) {
      super.write(Character.valueOf((char) lookbehind[from]));
      from = (from + 1) % lookbehind.length;
    }
  }

  @Override
  public void close() throws IOException {
    if (!found) {
      if (bufferFilled) {
        drain((pos + 2) % lookbehind.length, marker.length - 1);
      } else {
        drain(0, pos);
      }
    }
    super.close();
  }
}
