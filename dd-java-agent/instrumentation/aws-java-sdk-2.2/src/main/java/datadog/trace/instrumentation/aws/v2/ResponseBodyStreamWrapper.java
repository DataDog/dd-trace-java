package datadog.trace.instrumentation.aws.v2;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

/**
 * Buffers stream data that starts with a '{' character, assuming it is a JSON object. This is used
 * to the response body from the AWS SDK after it has been read by the SDK.
 */
public final class ResponseBodyStreamWrapper extends InputStream {

  /**
   * The maximum number of bytes to buffer. Estimate based on an average key size of 50 characters,
   * value of 250 characters, no more than 10 levels deep, and no more than 750 leaf nodes.
   */
  private static final int BUFFERING_LIMIT = 256 * 1024; // 256 KB

  private static final class Buffer extends ByteArrayOutputStream {
    private static Buffer create() {
      return new Buffer();
    }

    public ByteArrayInputStream asInputStream() {
      return new ByteArrayInputStream(buf, 0, count);
    }
  }

  private final InputStream originalStream;
  private Buffer buffer;
  private long bufferedBytes;

  public ResponseBodyStreamWrapper(InputStream is) {
    super();
    this.originalStream = is;
  }

  @Override
  public int read() throws IOException {
    int value = originalStream.read();

    if (value < 0) {
      return -1;
    }

    allocateBufferIfNeeded(value);

    if (keepBuffering()) {
      buffer.write(value);
      bufferedBytes += 1;
    }

    return value;
  }

  @Override
  public int read(byte[] bytes, int offset, int length) throws IOException {
    int readBytes = originalStream.read(bytes, offset, length);

    if (readBytes < 0) {
      return -1;
    }

    allocateBufferIfNeeded(bytes[offset]);

    if (keepBuffering()) {
      buffer.write(bytes, offset, readBytes);
      bufferedBytes += readBytes;
    }

    return readBytes;
  }

  private boolean keepBuffering() {
    return buffer != null && bufferedBytes < BUFFERING_LIMIT;
  }

  private void allocateBufferIfNeeded(int firstByte) {
    if (bufferedBytes == 0 && firstByte == '{') {
      buffer = Buffer.create();
    }
  }

  public Optional<ByteArrayInputStream> toByteArrayInputStream() {
    if (buffer == null) {
      return Optional.empty();
    }
    return Optional.of(buffer.asInputStream());
  }

  @Override
  public void close() throws IOException {
    originalStream.close();
  }
}
