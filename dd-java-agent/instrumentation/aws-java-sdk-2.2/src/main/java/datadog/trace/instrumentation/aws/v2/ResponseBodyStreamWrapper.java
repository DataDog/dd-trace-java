package datadog.trace.instrumentation.aws.v2;

import java.io.IOException;
import java.io.InputStream;
import okio.BufferedSource;
import okio.Okio;

/**
 * Buffers the response body stream so that it can be read later for tag extraction after it has
 * been consumed by the SDK.
 */
public class ResponseBodyStreamWrapper extends InputStream {
  private final BufferedSource source;
  private final InputStream sdkInputStream;

  public ResponseBodyStreamWrapper(InputStream origin) {
    source = Okio.buffer(Okio.source(origin));
    // Create a separate stream based on the source without consuming it.
    sdkInputStream = source.peek().inputStream();
  }

  @Override
  public int read() throws IOException {
    return sdkInputStream.read();
  }

  @Override
  public int read(byte[] sink, int offset, int byteCount) throws IOException {
    return sdkInputStream.read(sink, offset, byteCount);
  }

  @Override
  public int available() throws IOException {
    return sdkInputStream.available();
  }

  @Override
  public void close() {
    // doesn't close the source, so it can be read later
  }

  public InputStream consumeCapturedData() {
    return new ConsumableInputStream(source);
  }
}
