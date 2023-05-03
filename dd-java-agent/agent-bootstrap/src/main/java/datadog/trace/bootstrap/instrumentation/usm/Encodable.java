package datadog.trace.bootstrap.instrumentation.usm;

import java.nio.ByteBuffer;

//An interface for USM entities that should be encoded into a native buffer and passed to SystemProbe via ioctl
public interface Encodable {
  int size();

  void encode(ByteBuffer buffer);
}
