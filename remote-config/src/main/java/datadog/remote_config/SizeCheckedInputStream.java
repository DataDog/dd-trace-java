package datadog.remote_config;

import java.io.IOException;
import java.io.InputStream;

/** Wraps input stream to check a maximum allowed size */
class SizeCheckedInputStream extends InputStream {
  private final InputStream in;
  private final long maxSize;
  private long currentSize;

  public SizeCheckedInputStream(InputStream in, long maxSize) {
    this.in = in;
    this.maxSize = maxSize;
  }

  @Override
  public int read() throws IOException {
    checkSize(1);
    return in.read();
  }

  @Override
  public int read(byte[] b) throws IOException {
    checkSize(b.length);
    return in.read(b);
  }

  @Override
  public int read(byte[] b, int off, int len) throws IOException {
    checkSize(len);
    return in.read(b, off, len);
  }

  private void checkSize(long neededBytes) throws IOException {
    if (currentSize + neededBytes > maxSize) {
      throw new IOException("Reached maximum bytes for this stream: " + maxSize);
    }
    currentSize += neededBytes;
  }
}
