package datadog.trace.bootstrap.instrumentation.usm;

import java.nio.ByteBuffer;

// An interface for USM entities that should be encoded into a native buffer and passed to
// SystemProbe via ioctl
public interface Encodable {
  // Returns the full size of the object in bytes
  int size();

  // Encodes the object into a provided java.nio.ByteBuffer, assuming the buffer was pre-allocated
  // with enough space left.
  void encode(ByteBuffer buffer);
}
