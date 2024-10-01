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
public class ResponseBodyStreamWrapper extends InputStream {

  private static final class Buffer extends ByteArrayOutputStream {
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
    // TODO maybe there should be an upper bound limit to avoid buffering large data

    int value = originalStream.read();

    if (value < 0) {
      return -1;
    }

    startBufferingIfNeeded(value);

    if (buffer != null) {
      buffer.write(value);
      bufferedBytes += 1;
    }

    return value;
  }

  @Override
  public int read(byte[] b, int off, int len) throws IOException {
    int readBytes = super.read(b, off, len);

    if (readBytes < 0) {
      return -1;
    }

    startBufferingIfNeeded(b[off]);

    if (buffer != null) {
      buffer.write(b, off, readBytes);
      bufferedBytes += readBytes;
    }

    return readBytes;
  }

  private void startBufferingIfNeeded(int firstByte) {
    if (bufferedBytes == 0) {
      if (firstByte == '{') {
        // Start buffering only if it starts with '{' to avoid buffering non-json data
        buffer = new Buffer();
      }
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
