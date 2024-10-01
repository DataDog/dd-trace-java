package datadog.trace.instrumentation.aws.v2;

import java.io.IOException;
import java.io.InputStream;
import okio.BufferedSource;

public class ConsumableInputStream extends InputStream {
  private final BufferedSource source;
  private final InputStream is;

  public ConsumableInputStream(BufferedSource source) {
    this.source = source;
    this.is = source.inputStream();
  }

  @Override
  public int read() throws IOException {
    return is.read();
  }

  @Override
  public int read(byte[] sink, int offset, int byteCount) throws IOException {
    return is.read(sink, offset, byteCount);
  }

  @Override
  public int available() throws IOException {
    return is.available();
  }

  @Override
  public void close() throws IOException {
    source.close();
  }
}
