package datadog.trace.instrumentation.aws.v2;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

/**
 * Buffers stream data that starts with '{' character assuming it is a JSON object. This is used to
 * read the response body from AWS SDK after it has been read by the SDK.
 */
public class ResponseBodyStreamWrapper extends InputStream {

  private final InputStream originalStream;
  private ByteArrayOutputStream buffer;
  private boolean hasBeenRead;

  public ResponseBodyStreamWrapper(InputStream is) {
    super();
    this.originalStream = is;
  }

  @Override
  public int read() throws IOException {
    // TODO maybe there should be an upper bound limit to avoid buffering large data

    int value = originalStream.read();

    if (!hasBeenRead) {
      if (value == '{') {
        // Start buffering only if it starts with '{' to avoid buffering non-json data
        buffer = new ByteArrayOutputStream();
      }
      hasBeenRead = true;
    }

    if (buffer != null) {
      buffer.write(value);
    }

    return value;
  }

  public Optional<ByteArrayInputStream> toByteArrayInputStream() {
    if (buffer == null) {
      return Optional.empty();
    }
    return Optional.of(new ByteArrayInputStream(buffer.toByteArray()));
  }

  @Override
  public void close() throws IOException {
    originalStream.close();
  }
}
