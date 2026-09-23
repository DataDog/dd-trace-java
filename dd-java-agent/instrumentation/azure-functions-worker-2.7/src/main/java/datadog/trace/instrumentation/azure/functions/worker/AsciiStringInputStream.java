package datadog.trace.instrumentation.azure.functions.worker;

import java.io.InputStream;

/** Exposes an ASCII string as a stream without copying it into a byte array. */
public final class AsciiStringInputStream extends InputStream {
  private final String value;
  private int position;

  public AsciiStringInputStream(String value) {
    this.value = value;
  }

  @Override
  public int read() {
    return position < value.length() ? value.charAt(position++) : -1;
  }

  @Override
  public int read(byte[] buffer, int offset, int length) {
    if (length == 0) {
      return 0;
    }
    if (position >= value.length()) {
      return -1;
    }
    final int count = Math.min(length, value.length() - position);
    for (int index = 0; index < count; index++) {
      buffer[offset + index] = (byte) value.charAt(position + index);
    }
    position += count;
    return count;
  }
}
