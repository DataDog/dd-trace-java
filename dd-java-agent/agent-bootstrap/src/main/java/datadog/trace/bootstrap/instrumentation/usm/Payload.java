package datadog.trace.bootstrap.instrumentation.usm;

import java.nio.ByteBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Payload implements Encodable {

  private static final Logger log = LoggerFactory.getLogger(Payload.class);

  // This determines the size of the payload fragment that is captured for each
  // HTTPS request
  // should be equal to:
  // https://github.com/DataDog/datadog-agent/blob/main/pkg/network/ebpf/c/protocols/http-types.h#L7
  public static final int MAX_HTTPS_BUFFER_SIZE = 8 * 20;
  private final byte[] payload;
  private final int offset;
  private final int length;

  public Payload(byte[] buffer, int bufferOffset, int len) {
    payload = buffer;
    offset = bufferOffset;
    length = len;
  }

  @Override
  public int size() {
    // payload length + the actual payload limited to MAX_HTTPS_BUFFER_SIZE
    return MAX_HTTPS_BUFFER_SIZE + Integer.BYTES;
  }

  @Override
  public void encode(ByteBuffer buffer) {
    log.debug("encoding payload of size {}", (length - offset));
    // check the buffer is not larger than max allowed,
    if (length - offset <= MAX_HTTPS_BUFFER_SIZE) {
      buffer.putInt(length);
      buffer.put(payload, offset, length);
    }
    // if actual payload is larger, use only max allowed bytes
    else {
      buffer.putInt(MAX_HTTPS_BUFFER_SIZE);
      buffer.put(payload, offset, MAX_HTTPS_BUFFER_SIZE);
    }
  }
}
