package datadog.trace.util;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/** Wraps input stream to check a maximum allowed size */
public class SizeCheckedInputStream extends FilterInputStream {
  private final long maxSize;
  private long currentSize;

  public SizeCheckedInputStream(InputStream in, long maxSize) {
    super(in);
    this.maxSize = maxSize;
  }

  @Override
  public int read() throws IOException {
    checkSize(1);
    int v = super.read();
    if (v != -1) {
      updateCurrentSize(1);
    }
    return v;
  }

  @Override
  public int read(byte[] b) throws IOException {
    return read(b, 0, b.length);
  }

  @Override
  public int read(byte[] b, int off, int len) throws IOException {
    if (maxSize == currentSize) {
      throw new IOException("Reached maximum bytes for this stream: " + maxSize);
    }

    long safeLen = Math.min(len, maxSize - currentSize);
    checkSize(safeLen);
    return updateCurrentSize(super.read(b, off, (int) safeLen));
  }

  private int updateCurrentSize(int delta) {
    if (delta > 0) {
      currentSize += delta;
    }
    return delta;
  }

  private void checkSize(long neededBytes) throws IOException {
    if (currentSize + neededBytes > maxSize) {
      throw new IOException("Reached maximum bytes for this stream: " + maxSize);
    }
  }
}
